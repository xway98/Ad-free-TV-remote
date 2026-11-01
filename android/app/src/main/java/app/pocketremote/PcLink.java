package app.pocketremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import app.pocketremote.core.PcSession;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the connection to the paired laptop up for as long as something wants it: it connects as soon as the laptop
 * answers, notices within a few seconds when it doesn't, and reconnects at once. The laptop's announcement wakes it
 * immediately, so a laptop that has just been switched on is picked up in about a second.
 *
 * There is one of these per app ({@link #shared}). The screen and the background service each {@link #acquire} it and
 * {@link #release} it when they are done; the connection closes when nobody holds it any more.
 */
final class PcLink {
    interface Status {
        /** Always called on the main thread. */
        void onStatus(String text, int color);
    }

    private static final String HOST = "pcHost", TOKEN = "pcToken", FP = "pcFp", NAME = "pcName";
    private static PcLink instance;

    static synchronized PcLink shared(Context c) {
        if (instance == null) {
            instance = new PcLink(c.getApplicationContext(), c.getApplicationContext().getSharedPreferences("pocketremote", Context.MODE_PRIVATE));
        }
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final PcBeacon beacon;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Status> listeners = new CopyOnWriteArrayList<>();
    private final Object wake = new Object();
    private boolean wakeFlag;
    private int generation;
    private int holders;
    private boolean running;
    private String lastText = "";
    private int lastColor = Ui.AMBER;
    private WifiManager.WifiLock wifiLock;
    private volatile PcSession session;
    private volatile PcBeacon.Found extra;

    private PcLink(Context c, SharedPreferences prefs) {
        this.ctx = c;
        this.prefs = prefs;
        beacon = new PcBeacon(c);
        beacon.setFound((name, host, fp) -> {
            PcBeacon.Found e = extra;
            if (e != null) e.onFound(name, host, fp);
            String saved = prefs.getString(FP, null);
            if (saved == null || !saved.startsWith(fp)) return;
            if (!host.equals(prefs.getString(HOST, ""))) prefs.edit().putString(HOST, host).apply();
            PcSession s = session;
            if (s == null || !s.isOpen()) wake();
        });
    }

    boolean isPaired() {
        return prefs.getString(TOKEN, null) != null && prefs.getString(HOST, null) != null && prefs.getString(FP, null) != null;
    }

    String name() {
        return prefs.getString(NAME, "Laptop");
    }

    String host() {
        return prefs.getString(HOST, "");
    }

    /** The open connection, or null while the laptop can't be reached. */
    PcSession session() {
        PcSession s = session;
        return s != null && s.isOpen() ? s : null;
    }

    /** Lists laptops announcing themselves, for the pairing screen. */
    void setFoundListener(PcBeacon.Found f) {
        extra = f;
    }

    void save(String host, PcSession.Paired p) {
        prefs.edit().putString(HOST, host).putString(TOKEN, Hex.of(p.token)).putString(FP, Hex.of(p.fingerprint))
                .putString(NAME, p.laptopName).apply();
    }

    void forget() {
        closeSession();
        prefs.edit().remove(HOST).remove(TOKEN).remove(FP).remove(NAME).apply();
    }

    // ---- who is listening ----

    /** The status is replayed to a new listener at once, so a screen that opens later shows the current state. */
    void addStatusListener(Status s) {
        listeners.addIfAbsent(s);
        final String t = lastText;
        final int c = lastColor;
        ui.post(() -> s.onStatus(t, c));
    }

    void removeStatusListener(Status s) {
        listeners.remove(s);
    }

    // ---- who needs the connection ----

    synchronized void acquire() {
        holders++;
        start();
    }

    synchronized void release() {
        holders = Math.max(0, holders - 1);
        if (holders == 0) stop();
    }

    private synchronized void start() {
        if (running) return;
        running = true;
        final int gen = ++generation;
        beacon.start();
        Thread t = new Thread(() -> run(gen), "pc-link");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void stop() {
        running = false;
        generation++;
        beacon.stop();
        closeSession();
        releaseWifi();
        wake();
    }

    /** The user asked to retry: drop the connection and try again straight away. */
    void reconnect() {
        closeSession();
        wake();
    }

    private void closeSession() {
        PcSession s = session;
        session = null;
        if (s != null) s.close();
    }

    /** While connected, keep the Wi-Fi radio from napping: that is where stray 50-100 ms pauses come from. */
    private synchronized void holdWifi() {
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "pocketremote-laptop");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (RuntimeException ignored) {
            // a missing permission or an unsupported mode only costs a little latency
        }
    }

    private synchronized void releaseWifi() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (RuntimeException ignored) {
        }
    }

    private void wake() {
        synchronized (wake) {
            wakeFlag = true;
            wake.notifyAll();
        }
    }

    private void pause(long ms) {
        synchronized (wake) {
            if (!wakeFlag) {
                try {
                    wake.wait(ms);
                } catch (InterruptedException ignored) {
                }
            }
            wakeFlag = false;
        }
    }

    private void post(String text, int color) {
        ui.post(() -> {
            lastText = text;
            lastColor = color;
            for (Status s : listeners) s.onStatus(text, color);
        });
    }

    private boolean alive(int gen) {
        synchronized (this) {
            return running && gen == generation;
        }
    }

    private void run(int gen) {
        int fails = 0;
        while (alive(gen)) {
            if (!isPaired()) {
                pause(1000);
                continue;
            }
            PcSession open = session;
            if (open != null && open.isOpen()) {
                pause(1000);
                continue;
            }
            final PcSession next = new PcSession(prefs.getString(HOST, ""), PcSession.PORT,
                    Hex.parse(prefs.getString(TOKEN, "")), Hex.parse(prefs.getString(FP, "")), new PcSession.Listener() {
                @Override public void onReady(String laptopName) {
                    prefs.edit().putString(NAME, laptopName).apply();
                    holdWifi();
                    post("Connected", Ui.GREEN);
                }

                @Override public void onClosed(Exception reason) {
                    releaseWifi();
                    post("Reconnecting…", Ui.AMBER);
                    wake();
                }
            });
            session = next;
            try {
                next.connect(1200);
                fails = 0;
            } catch (SecurityException e) {
                session = null;
                post("The laptop doesn't know this phone: pair again", Ui.RED);
                pause(4000);
            } catch (Exception e) {
                session = null;
                fails++;
                if (fails >= 2) post("Can't reach the laptop (" + brief(e) + ")", Ui.RED);
                else post("Connecting…", Ui.AMBER);
                pause(1000);
            }
        }
    }

    private static String brief(Throwable e) {
        String m = e.getMessage();
        String s = e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() > 70 ? s.substring(0, 70) : s;
    }
}
