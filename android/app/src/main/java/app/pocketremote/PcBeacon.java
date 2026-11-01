package app.pocketremote;

import android.content.Context;
import android.net.wifi.WifiManager;

import app.pocketremote.core.PcSession;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Listens for the PC agent's UDP announcement ("TVRPC1|name|port|certificate id"), which it broadcasts every two
 * seconds and again the moment the laptop's network comes back. That is how the phone finds the laptop within a
 * moment of it waking up, even when its IP address has changed.
 */
final class PcBeacon {
    interface Found {
        void onFound(String name, String host, String fingerprintPrefix);
    }

    private final Context ctx;
    private volatile Found found;
    private volatile boolean running;
    private volatile DatagramSocket socket;
    private WifiManager.MulticastLock lock;

    PcBeacon(Context c) {
        ctx = c.getApplicationContext();
    }

    void setFound(Found f) {
        found = f;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("pocketremote-beacon");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (RuntimeException ignored) {
            // broadcasts usually arrive without it
        }
        Thread t = new Thread(this::loop, "pc-beacon");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
    }

    private void loop() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PcSession.BEACON_PORT));
            s.setBroadcast(true);
            socket = s;
            byte[] buf = new byte[256];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String[] f = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\\|");
                Found cb = found;
                if (cb != null && f.length >= 4 && f[0].equals("TVRPC1")) {
                    cb.onFound(f[1], p.getAddress().getHostAddress(), f[3]);
                }
            }
        } catch (IOException ignored) {
            // closed by stop(), or the port is busy: the saved address still works
        } finally {
            if (s != null) s.close();
        }
    }
}
