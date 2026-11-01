package app.pocketremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;
import app.pocketremote.core.PcSession;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import app.pocketremote.core.Identity;
import app.pocketremote.core.Keys;
import app.pocketremote.core.Pairing;
import app.pocketremote.core.RemoteSession;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    /** What the TV app shortcuts start as. You change the list in the app: "+ Add app", press and hold a tile to remove it. */
    private static final String[][] DEFAULT_TV_APPS = {
            {"YouTube", "com.google.android.youtube.tv"},
            {"Netflix", "com.netflix.ninja"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom"},
            {"Disney+", "com.disney.disneyplus"},
            {"Spotify", "com.spotify.tv.android"},
            {"YouTube Music", "com.google.android.youtube.tvmusic"},
            {"Plex", "com.plexapp.android"},
            {"Play Store", "com.android.vending"},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private Identity identity;
    private ViewGroup root;
    private Discovery discovery;

    private volatile RemoteSession session;
    private boolean started;
    private boolean connecting;
    private boolean onRemote;
    private Pairing pairing;

    private TextView statusText, volumeText;
    private View statusDot;
    private TextView pairStatus;
    private LinearLayout foundList;
    private final Set<String> foundHosts = new HashSet<>();

    private Pager pager;
    private LinearLayout banner;
    private TextView bannerChange, tvTab, pcTab, statusName;
    private View powerBtn, kbBtn;
    /** What this phone controls: "tv", "pc" (the Windows laptop) or "both". Chosen on first run, changeable later. */
    private String devices;
    private LinearLayout tvAppsBox;
    private FrameLayout body;
    private View tvBody;
    private LaptopUi laptop;
    private PcLink pcLink;
    private boolean pcHeld;
    private String pendingShare;
    private final PcLink.Status pcListener = (text, color) -> {
        pcStatusText = text;
        pcStatusColor = color;
        if (pcMode()) showStatus(false);
    };
    private String mode = "tv";
    private String tvStatusText = "", pcStatusText = "";
    private int tvStatusColor = Ui.AMBER, pcStatusColor = Ui.AMBER;
    private MirrorEditText typingInput;
    /** What we believe the TV's text field holds; always equal to the local box so edits can be sent as diffs. */
    private String mirror = "";
    private boolean syncing;
    private long lastEdit;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = getSharedPreferences("pocketremote", MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        pcLink = PcLink.shared(this);
        route();
        handleIntent(getIntent());
    }

    // ---- what the phone controls: TV, laptop or both ----------------------------------------------

    private boolean hasTv() {
        return "tv".equals(devices) || "both".equals(devices);
    }

    private boolean hasPc() {
        return "pc".equals(devices) || "both".equals(devices);
    }

    /** First run asks what to control; after that it opens straight to the right place. */
    private void route() {
        devices = prefs.getString("devices", null);
        if (devices == null) {
            // someone who paired before this choice existed keeps what they have
            boolean tv = isPaired(), pc = pcLink.isPaired();
            if (tv || pc) {
                setDevices(tv && pc ? "both" : tv ? "tv" : "pc");
                return;
            }
            showWelcome();
            return;
        }
        openHome();
    }

    private void setDevices(String d) {
        devices = d;
        prefs.edit().putString("devices", d).apply();
        openHome();
    }

    private void openHome() {
        if (hasTv() && !hasPc() && !isPaired()) showPair();
        else showRemote();
    }

    /** "More > Change what I control": drop the connections, then ask again. Pairings are kept. */
    private void chooseDevices() {
        closeSession();
        releaseLaptop();
        stopService(new Intent(this, BackgroundService.class));
        showWelcome();
    }

    private void showWelcome() {
        onRemote = false;
        ui.removeCallbacksAndMessages(null);
        statusText = null;
        statusDot = null;
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(24), Ui.dp(56), Ui.dp(24), Ui.dp(32));
        sv.addView(col, new ViewGroup.LayoutParams(MATCH, WRAP));

        TextView title = text("What do you want to control?", 28, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        col.addView(title);
        TextView sub = text("Pick what you use. You can change this later from the ⋯ menu, and your pairings are kept.", 14, Ui.MUTED);
        sub.setPadding(0, Ui.dp(8), 0, Ui.dp(22));
        col.addView(sub);
        choiceCard(col, "TV", "Android TV or Google TV, over your Wi-Fi.", "tv");
        choiceCard(col, "Laptop", "A Windows laptop: mouse, keyboard, shortcuts and apps. Needs the free Pocket Remote app on the laptop.", "pc");
        choiceCard(col, "TV and laptop", "Both, with a switch at the top.", "both");
        root.removeAllViews();
        root.addView(sv);
    }

    private void choiceCard(LinearLayout col, String title, String detail, final String value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Ui.SURFACE, 18));
        card.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
        card.setClickable(true);
        TextView t = text(title, 18, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(t);
        TextView d = text(detail, 13, Ui.MUTED);
        d.setPadding(0, Ui.dp(4), 0, 0);
        card.addView(d);
        card.setOnClickListener(v -> setDevices(value));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = Ui.dp(10);
        col.addView(card, lp);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (onRemote) {
            connect();
            holdLaptop();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        ui.removeCallbacksAndMessages(null);
        closeSession();
        releaseLaptop();
        if (discovery != null) discovery.stop();
    }

    /** The laptop connection is shared with the background service: each holds it, and it closes when neither does. */
    private void holdLaptop() {
        if (!hasPc()) return;
        if (!pcHeld) {
            pcHeld = true;
            pcLink.addStatusListener(pcListener);
            pcLink.acquire();
        }
        applyBackground();
    }

    private void releaseLaptop() {
        if (pcHeld) {
            pcHeld = false;
            pcLink.removeStatusListener(pcListener);
            pcLink.release();
        }
    }

    /** Starts or stops the notification service that keeps the laptop connected while the app is closed. */
    private void applyBackground() {
        boolean want = onRemote && hasPc() && pcLink.isPaired() && prefs.getBoolean("pcBackground", true);
        Intent svc = new Intent(this, BackgroundService.class);
        if (!want) {
            stopService(svc);
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        try {
            startForegroundService(svc);
        } catch (RuntimeException e) {
            // Android refused to start it just now; the screen still keeps the connection while it is open
        }
    }

    // ---- sharing a link or text from another app ----

    private void handleIntent(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        setIntent(new Intent());                   // handled: a rotation must not offer it a second time
        if (t == null || t.trim().isEmpty()) return;
        pendingShare = t.trim();
        if (onRemote) offerShare();
    }

    private void offerShare() {
        final String text = pendingShare;
        pendingShare = null;
        if (text == null) return;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        final String url = m.find() ? m.group() : null;
        final List<String> names = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (url != null && hasTv() && isPaired()) {
            names.add("TV: open the link");
            acts.add(() -> shareToTv(url));
        }
        if (hasPc()) {
            if (url != null) {
                names.add("Laptop: open the link");
                acts.add(() -> shareToLaptop(2, s -> s.launch("run:" + url), "Opened on the laptop"));
            }
            names.add("Laptop: copy the text");
            acts.add(() -> shareToLaptop(4, s -> s.setClipboard(text), "Copied on the laptop"));
            names.add("Laptop: type the text");
            acts.add(() -> shareToLaptop(1, s -> s.text(text), "Typed on the laptop"));
        }
        if (names.isEmpty()) {
            Toast.makeText(this, "Pair a TV or a laptop first", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Send to…")
                .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    /** Waits (up to about six seconds) for a connection that is still coming up, then runs the action. */
    private void whenReady(final java.util.function.BooleanSupplier ready, final Runnable then, final String failure, final int tries) {
        if (ready.getAsBoolean()) {
            then.run();
        } else if (tries <= 0) {
            Toast.makeText(this, failure, Toast.LENGTH_LONG).show();
        } else {
            ui.postDelayed(() -> whenReady(ready, then, failure, tries - 1), 250);
        }
    }

    private void shareToTv(final String url) {
        connect();
        whenReady(() -> {
            RemoteSession s = session;
            return s != null && s.isOpen();
        }, () -> {
            send(url);
            Toast.makeText(this, "Sent to the TV", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
        }, "Couldn't reach the TV", 24);
    }

    private void shareToLaptop(final int needsVersion, final java.util.function.Consumer<PcSession> work, final String done) {
        if (!pcLink.isPaired()) {
            Toast.makeText(this, "Pair the laptop first (Laptop tab)", Toast.LENGTH_LONG).show();
            return;
        }
        whenReady(() -> pcLink.session() != null, () -> {
            PcSession s = pcLink.session();
            if (s == null) return;
            if (s.agentVersion() < needsVersion) {
                Toast.makeText(this, "The laptop app is out of date: run its latest setup (Update)", Toast.LENGTH_LONG).show();
                return;
            }
            work.accept(s);
            Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
        }, "Couldn't reach the laptop", 24);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
        sender.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (onRemote && pcMode() && laptop != null && laptop.onBack()) return;
        super.onBackPressed();
    }

    /** The phone's volume buttons control the TV, or the laptop while that is showing. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (onRemote && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            boolean up = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
            if (pcMode()) laptop.volume(up);
            else key(up ? Keys.VOLUME_UP : Keys.VOLUME_DOWN);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (onRemote && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private boolean isPaired() {
        return prefs.getString("host", null) != null && prefs.getString("pin", null) != null;
    }

    // ---- connection ------------------------------------------------------------------------------

    private Identity identity() throws Exception {
        if (identity != null) return identity;
        File dir = new File(getFilesDir(), "identity");
        if (Identity.exists(dir)) {
            identity = Identity.load(dir);
        } else {
            identity = Identity.create("pocketremote");
            identity.save(dir);
        }
        return identity;
    }

    private void connect() {
        if (!started || connecting || !onRemote || !isPaired()) return;
        RemoteSession old = session;
        if (old != null && old.isOpen()) return;
        connecting = true;
        setStatus("Connecting…", Ui.AMBER);
        final String host = prefs.getString("host", "");
        final byte[] pin = unhex(prefs.getString("pin", ""));
        net.execute(() -> {
            try {
                RemoteSession s = new RemoteSession(identity(), host, pin, new RemoteSession.Listener() {
                    @Override public void onReady() {
                        ui.post(() -> {
                            connecting = false;
                            setStatus("Connected", Ui.GREEN);
                        });
                    }

                    @Override public void onVolume(int level, int max, boolean muted) {
                        ui.post(() -> {
                            if (volumeText != null) volumeText.setText(muted ? "Mute" : String.valueOf(level));
                        });
                    }

                    @Override public void onPower(boolean on) {}

                    @Override public void onKeyboard() {
                        ui.post(() -> {
                            lastEdit = 0;
                            openTyping();
                        });
                    }

                    @Override public void onTextField(String text) {
                        ui.post(() -> applyTv(text));
                    }

                    @Override public void onClosed(Exception reason) {
                        final String why = brief(reason);
                        ui.post(() -> {
                            connecting = false;
                            session = null;
                            setStatus("Disconnected (" + why + ")", Ui.RED);
                            retryLater();
                        });
                    }
                });
                s.connect();
                session = s;
            } catch (Exception e) {
                final String why = brief(e);
                ui.post(() -> {
                    connecting = false;
                    setStatus("Can't reach the TV (" + why + ")", Ui.RED);
                    retryLater();
                });
            }
        });
    }

    private void retryLater() {
        ui.removeCallbacks(retry);
        if (started && onRemote) ui.postDelayed(retry, 3000);
    }

    private final Runnable retry = this::connect;

    private void closeSession() {
        RemoteSession s = session;
        session = null;
        connecting = false;
        if (s != null) net.execute(s::close);
    }

    /** The dot always shows the state; the banner under the top bar appears only while the TV can't be reached. */
    private void setStatus(String text, int color) {
        tvStatusText = text;
        tvStatusColor = color;
        if (!pcMode()) showStatus(false);
    }

    /** Presses or releases a key for a long press (hold OK on the touchpad). */
    private void hold(final int code, final boolean start) {
        final RemoteSession s = session;
        if (s == null || !s.isOpen()) {
            if (start) connect();
            return;
        }
        sender.execute(() -> s.sendKey(code, start ? RemoteSession.START_LONG : RemoteSession.END_LONG));
    }

    private void key(final int code) {
        final RemoteSession s = session;
        if (s == null || !s.isOpen()) {
            connect();
            return;
        }
        sender.execute(() -> s.sendKey(code));
    }

    private void launch(final String pkg) {
        send("market://launch?id=" + pkg);
    }

    private void send(final String link) {
        final RemoteSession s = session;
        if (s == null || !s.isOpen()) {
            connect();
            return;
        }
        sender.execute(() -> s.launchApp(link));
    }

    // ---- pairing screen --------------------------------------------------------------------------

    private void showPair() {
        onRemote = false;
        ui.removeCallbacksAndMessages(null);
        statusText = null;
        statusDot = null;
        foundHosts.clear();

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(24), Ui.dp(56), Ui.dp(24), Ui.dp(32));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Pair your TV", 30, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        col.addView(title);
        TextView hint = text("Turn the TV on and join this phone to the same Wi-Fi. Pick your TV below, or type its IP address.", 15, Ui.MUTED);
        hint.setPadding(0, Ui.dp(8), 0, Ui.dp(20));
        col.addView(hint);

        col.addView(text("TVs found on your Wi-Fi", 13, Ui.MUTED));
        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        col.addView(foundList);
        TextView searching = text("Searching…", 14, Ui.MUTED);
        searching.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        foundList.addView(searching);

        TextView ipLabel = text("Or enter the IP address", 13, Ui.MUTED);
        ipLabel.setPadding(0, Ui.dp(20), 0, Ui.dp(6));
        col.addView(ipLabel);
        final EditText ip = field("192.168.0.5");
        ip.setInputType(InputType.TYPE_CLASS_TEXT);
        ip.setText(prefs.getString("lastIp", ""));
        col.addView(ip);

        TextView go = button("Pair", true);
        col.addView(go, buttonParams(14));
        pairStatus = text("", 14, Ui.MUTED);
        pairStatus.setPadding(0, Ui.dp(12), 0, 0);
        col.addView(pairStatus);

        final LinearLayout codeBox = new LinearLayout(this);
        codeBox.setOrientation(LinearLayout.VERTICAL);
        codeBox.setVisibility(View.GONE);
        TextView codeLabel = text("Enter the 6-character code shown on your TV", 15, Ui.TEXT);
        codeLabel.setPadding(0, Ui.dp(24), 0, Ui.dp(8));
        codeBox.addView(codeLabel);
        final EditText code = field("A1B2C3");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6), new InputFilter.AllCaps()});
        code.setTextSize(28);
        code.setTypeface(Typeface.MONOSPACE);
        code.setLetterSpacing(0.2f);
        code.setGravity(Gravity.CENTER);
        codeBox.addView(code);
        TextView confirm = button("Confirm", true);
        codeBox.addView(confirm, buttonParams(14));
        col.addView(codeBox);

        // ways out of this screen
        if (hasPc()) {
            TextView back = text("Back to the laptop", 14, Ui.ACCENT2);
            back.setPadding(0, Ui.dp(24), 0, Ui.dp(8));
            back.setOnClickListener(v -> showRemote());
            col.addView(back);
        }
        TextView change = text("Change what I control", 14, Ui.ACCENT2);
        change.setPadding(0, Ui.dp(hasPc() ? 4 : 24), 0, Ui.dp(8));
        change.setOnClickListener(v -> chooseDevices());
        col.addView(change);

        go.setOnClickListener(v -> {
            String host = ip.getText().toString().trim();
            if (host.isEmpty()) {
                pairStatus.setText("Enter the TV's IP address first.");
                return;
            }
            prefs.edit().putString("lastIp", host).apply();
            hideKeyboard(ip);
            startPairing(host, "TV", codeBox, code);
        });
        confirm.setOnClickListener(v -> {
            hideKeyboard(code);
            finishPairing(code.getText().toString().trim());
        });

        root.removeAllViews();
        root.addView(sv);

        if (discovery == null) discovery = new Discovery(this);
        discovery.start((name, host) -> {
            if (!foundHosts.add(host)) return;
            if (foundList.getChildCount() == 1 && foundList.getChildAt(0) instanceof TextView
                    && "Searching…".contentEquals(((TextView) foundList.getChildAt(0)).getText())) {
                foundList.removeAllViews();
            }
            TextView b = button(name + "  •  " + host, false);
            b.setOnClickListener(v -> {
                ip.setText(host);
                prefs.edit().putString("lastIp", host).apply();
                startPairing(host, name, codeBox, code);
            });
            foundList.addView(b, buttonParams(8));
        });
    }

    private String pendingHost, pendingName;

    private void startPairing(final String host, final String name, final View codeBox, final EditText code) {
        pendingHost = host;
        pendingName = name;
        pairStatus.setText("Connecting to the TV…");
        net.execute(() -> {
            try {
                if (pairing != null) pairing.close();
                Pairing p = new Pairing(identity(), host, Build.MODEL == null ? "Phone" : Build.MODEL);
                p.start();
                pairing = p;
                ui.post(() -> {
                    pairStatus.setText("Look at your TV for the code.");
                    codeBox.setVisibility(View.VISIBLE);
                    code.requestFocus();
                });
            } catch (Exception e) {
                final String why = brief(e);
                ui.post(() -> pairStatus.setText("Couldn't reach the TV at " + host + ". Check it is on and on the same Wi-Fi. (" + why + ")"));
            }
        });
    }

    private void finishPairing(final String code) {
        final Pairing p = pairing;
        if (p == null) {
            pairStatus.setText("Start pairing first.");
            return;
        }
        pairStatus.setText("Checking the code…");
        net.execute(() -> {
            try {
                byte[] pin = p.finish(code);
                p.close();
                pairing = null;
                prefs.edit().putString("host", pendingHost).putString("name", pendingName).putString("pin", hex(pin)).apply();
                ui.post(this::showRemote);
            } catch (SecurityException e) {
                ui.post(() -> pairStatus.setText(e.getMessage()));
            } catch (IOException e) {
                p.close();
                pairing = null;
                final String why = brief(e);
                ui.post(() -> pairStatus.setText("Pairing failed. Start again. (" + why + ")"));
            }
        });
    }

    // ---- remote screen ---------------------------------------------------------------------------

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /**
     * The top bar over a body that is either the TV remote (two pages you swipe between, so nothing needs scrolling)
     * or the laptop controls. Both stay connected while the other one is showing.
     */
    private void showRemote() {
        onRemote = true;
        pager = null;                     // views from an earlier build of this screen are gone
        typingInput = null;
        if (discovery != null) discovery.stop();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(topBar(), new LinearLayout.LayoutParams(MATCH, Ui.dp(44)));
        col.addView(banner(), new LinearLayout.LayoutParams(MATCH, WRAP));

        // only what you chose to control is built at all
        body = new FrameLayout(this);
        tvBody = hasTv() ? tvBody() : null;
        laptop = hasPc() ? new LaptopUi(this, prefs, pcLink, this::applyBackground) : null;
        if (tvBody != null) body.addView(tvBody, new FrameLayout.LayoutParams(MATCH, MATCH));
        if (laptop != null) body.addView(laptop.view(), new FrameLayout.LayoutParams(MATCH, MATCH));
        col.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        root.removeAllViews();
        root.addView(col);
        setMode(prefs.getString("mode", "tv"));
        if (started) {
            connect();
            holdLaptop();
        }
        if (pendingShare != null) offerShare();
    }

    /** With both devices chosen and no TV paired yet, the TV side is an invitation instead of a remote. */
    private View pairTvPrompt() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(Ui.dp(32), 0, Ui.dp(32), Ui.dp(32));
        TextView t = text("No TV paired yet", 20, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        TextView d = text("Pair an Android TV or Google TV to use it as a remote here.", 14, Ui.MUTED);
        d.setGravity(Gravity.CENTER);
        d.setPadding(0, Ui.dp(6), 0, Ui.dp(18));
        col.addView(d);
        TextView go = button("Pair a TV", true);
        go.setOnClickListener(v -> showPair());
        col.addView(go, new LinearLayout.LayoutParams(Ui.dp(200), Ui.dp(50)));
        return col;
    }

    private View tvBody() {
        if (!isPaired()) return pairTvPrompt();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        pager = new Pager(this);
        pager.addPage(remotePage());
        pager.addPage(morePage());
        col.addView(pager, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        final Dots dots = new Dots(this, 2);
        pager.setListener(p -> {
            dots.setPosition(p);
            if (p < 0.4f && typingInput != null && typingInput.hasFocus()) {
                hideKeyboard(typingInput);
                typingInput.clearFocus();
            }
        });
        col.addView(dots, new LinearLayout.LayoutParams(MATCH, Ui.dp(16)));
        return col;
    }

    private void setMode(String m) {
        mode = "pc".equals(m) ? "pc" : "tv";
        if (!hasPc()) mode = "tv";
        if (!hasTv()) mode = "pc";
        if (hasTv() && hasPc()) prefs.edit().putString("mode", mode).apply();
        boolean pc = pcMode();
        if (tvBody != null) tvBody.setVisibility(pc ? View.GONE : View.VISIBLE);
        if (laptop != null) laptop.view().setVisibility(pc ? View.VISIBLE : View.GONE);
        if (tvTab != null) styleMode(tvTab, !pc);
        if (pcTab != null) styleMode(pcTab, pc);
        if (pc && typingInput != null) hideKeyboard(typingInput);
        if (!pc && laptop != null) laptop.onHidden();
        if (powerBtn != null) powerBtn.setVisibility(pc ? View.GONE : View.VISIBLE);
        if (kbBtn != null) kbBtn.setVisibility(pc ? View.GONE : View.VISIBLE);
        showStatus(true);
    }

    private boolean pcMode() {
        return "pc".equals(mode);
    }

    private void styleMode(TextView t, boolean on) {
        t.setTextColor(on ? Ui.TEXT : Ui.MUTED);
        t.setBackground(on ? Ui.round(Ui.SURFACE_HI, 17) : null);
    }

    /** The dot always shows the state of whatever is on screen; the banner appears only when it can't be reached. */
    private void showStatus(boolean modeSwitched) {
        if (statusDot == null || banner == null || statusText == null) return;
        boolean pc = pcMode();
        String text = pc ? pcStatusText : tvStatusText;
        int color = pc ? pcStatusColor : tvStatusColor;
        if (pc && !pcLink.isPaired()) {
            color = 0x668D8D9B;
            text = "";
        }
        statusDot.setBackground(Ui.round(color, 4));
        if (statusName != null) statusName.setText(pc ? (pcLink.isPaired() ? pcLink.name() : "") : prefs.getString("name", "TV"));
        statusText.setText(text);
        if (color == Ui.RED) banner.setVisibility(View.VISIBLE);
        else if (color == Ui.GREEN || modeSwitched || color == 0x668D8D9B) banner.setVisibility(View.GONE);
        bannerChange.setText(pc ? "Pair again" : "Change TV");
    }

    /** A slim bar: the TV | Laptop switch, then the connection (dot and name) and a few quiet icons. */
    private View topBar() {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(14), 0, Ui.dp(6), 0);

        tvTab = null;
        pcTab = null;
        if (hasTv() && hasPc()) {
            LinearLayout pill = row();
            pill.setBackground(Ui.round(Ui.SURFACE, 18));
            pill.setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), Ui.dp(2));
            tvTab = modeTab("TV", "tv");
            pcTab = modeTab("Laptop", "pc");
            pill.addView(tvTab, new LinearLayout.LayoutParams(WRAP, MATCH));
            pill.addView(pcTab, new LinearLayout.LayoutParams(WRAP, MATCH));
            bar.addView(pill, new LinearLayout.LayoutParams(WRAP, Ui.dp(34)));
        } else {
            TextView only = text(hasTv() ? "TV" : "Laptop", 18, Ui.TEXT);       // one device: no switch, just its name
            only.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            only.setPadding(Ui.dp(4), 0, 0, 0);
            bar.addView(only, new LinearLayout.LayoutParams(WRAP, WRAP));
        }

        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        statusDot = new View(this);
        statusDot.setBackground(Ui.round(Ui.AMBER, 4));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(8), Ui.dp(8));
        dp.rightMargin = Ui.dp(6);
        bar.addView(statusDot, dp);
        statusName = text("", 12, Ui.MUTED);
        statusName.setSingleLine(true);
        statusName.setMaxWidth(Ui.dp(96));
        statusName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(statusName, new LinearLayout.LayoutParams(WRAP, WRAP));

        powerBtn = icon(IconButton.Kind.POWER, null, false, false, () -> key(Keys.POWER)).bare(true).tint(Ui.MUTED);
        bar.addView(powerBtn, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        kbBtn = icon(IconButton.Kind.KEYBOARD, null, false, false, this::openTyping).bare(true).tint(Ui.MUTED);
        bar.addView(kbBtn, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        bar.addView(icon(IconButton.Kind.MORE, null, false, false, this::showMore).bare(true).tint(Ui.MUTED),
                new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        return bar;
    }

    /** The "more" button: everything that isn't needed all the time, in one sheet. */
    private void showMore() {
        if (pcMode()) laptop.showMore(this::reconnect, this::changeDevice, this::chooseDevices);
        else showTvMore();
    }

    private void showTvMore() {
        final Dialog[] dlg = new Dialog[1];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // the standard buttons a TV remote has; what each one does depends on the TV
        TextView label = text("TV buttons", 12, Ui.MUTED);
        label.setPadding(Ui.dp(4), 0, 0, 0);
        col.addView(label);
        LinearLayout menus = row();
        menus.addView(new KeyButton(this, "Inputs", false, () -> {
            dlg[0].dismiss();
            key(Keys.TV_INPUT);
        }), cell());
        menus.addView(new KeyButton(this, "Settings", false, () -> {
            dlg[0].dismiss();
            key(Keys.SETTINGS);
        }), cell());
        menus.addView(new KeyButton(this, "Info", false, () -> {
            dlg[0].dismiss();
            key(Keys.INFO);
        }), cell());
        col.addView(menus, rowParams(6, 46));
        TextView conn = text("Connection", 12, Ui.MUTED);
        conn.setPadding(Ui.dp(4), Ui.dp(18), 0, 0);
        col.addView(conn);
        LinearLayout foot = row();
        foot.addView(new KeyButton(this, "Reconnect", false, () -> {
            dlg[0].dismiss();
            reconnect();
        }), cell());
        foot.addView(new KeyButton(this, "Change TV", false, () -> {
            dlg[0].dismiss();
            changeDevice();
        }), cell());
        col.addView(foot, rowParams(6, 46));
        col.addView(new KeyButton(this, "Change what I control", false, () -> {
            dlg[0].dismiss();
            chooseDevices();
        }), rowParams(8, 46));
        dlg[0] = Sheet.show(this, col);
    }

    private TextView modeTab(String label, final String m) {
        TextView t = text(label, 14, Ui.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setOnClickListener(v -> setMode(m));
        return t;
    }

    private void reconnect() {
        if (pcMode()) {
            pcLink.reconnect();
        } else {
            closeSession();
            connect();
        }
    }

    private void changeDevice() {
        if (pcMode()) {
            pcLink.forget();
            applyBackground();
            pcStatusText = "";
            pcStatusColor = Ui.AMBER;
            laptop.refresh();
            showStatus(true);
        } else {
            changeTv();
        }
    }

    /** Only shown when the TV or laptop can't be reached. */
    private View banner() {
        banner = row();
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(Ui.dp(18), Ui.dp(2), Ui.dp(10), Ui.dp(6));
        banner.setVisibility(View.GONE);
        statusText = text("", 12, Ui.MUTED);
        banner.addView(statusText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView retry = text("Retry", 13, Ui.ACCENT2);
        retry.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        retry.setOnClickListener(v -> reconnect());
        banner.addView(retry);
        bannerChange = text("Change TV", 13, Ui.ACCENT2);
        bannerChange.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        bannerChange.setOnClickListener(v -> changeDevice());
        banner.addView(bannerChange);
        return banner;
    }

    private void changeTv() {
        closeSession();
        prefs.edit().remove("host").remove("pin").remove("name").apply();
        showPair();
    }

    private View remotePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(14), Ui.dp(2), Ui.dp(14), Ui.dp(2));

        // D-pad or touchpad takes whatever height is left, with the volume rocker beside it
        LinearLayout padArea = row();
        final FrameLayout holder = new FrameLayout(this);
        padArea.addView(holder, new LinearLayout.LayoutParams(0, MATCH, 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.dp(56), MATCH);
        rp.leftMargin = Ui.dp(10);
        padArea.addView(volumeRocker(), rp);
        LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        pa.topMargin = Ui.dp(4);
        page.addView(padArea, pa);

        final TextView toggle = text("", 12, Ui.TEXT);
        toggle.setGravity(Gravity.CENTER);
        toggle.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        toggle.setBackground(Ui.round(Ui.SURFACE_HI, 15));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(WRAP, Ui.dp(30), Gravity.TOP | Gravity.END);
        tl.topMargin = tl.rightMargin = Ui.dp(8);
        holder.addView(toggle, tl);
        setPad(holder, toggle, !"touch".equals(prefs.getString("pad", "dpad")));
        toggle.setOnClickListener(v -> setPad(holder, toggle, "touch".equals(prefs.getString("pad", "dpad"))));

        LinearLayout nav = row();
        nav.addView(icon(IconButton.Kind.BACK, null, false, false, () -> key(Keys.BACK)), cell());
        nav.addView(icon(IconButton.Kind.HOME, null, false, false, () -> key(Keys.HOME)), cell());
        nav.addView(icon(IconButton.Kind.SEARCH, null, false, false, () -> key(Keys.SEARCH)), cell());
        nav.addView(icon(IconButton.Kind.MENU, null, false, false, () -> key(Keys.MENU)), cell());
        page.addView(nav, rowParams(10, 50));

        LinearLayout media = row();
        media.addView(icon(IconButton.Kind.REWIND, null, true, false, () -> key(Keys.REWIND)), cell());
        media.addView(icon(IconButton.Kind.PLAY_PAUSE, null, false, false, () -> key(Keys.PLAY_PAUSE)), cell());
        media.addView(icon(IconButton.Kind.FORWARD, null, true, false, () -> key(Keys.FAST_FORWARD)), cell());
        media.addView(icon(IconButton.Kind.MUTE, null, false, false, () -> key(Keys.VOLUME_MUTE)), cell());
        page.addView(media, rowParams(8, 46));
        return page;
    }

    private View volumeRocker() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.addView(icon(IconButton.Kind.PLUS, null, true, false, () -> key(Keys.VOLUME_UP)), new LinearLayout.LayoutParams(MATCH, 0, 1f));
        volumeText = text("Vol", 11, Ui.MUTED);
        volumeText.setGravity(Gravity.CENTER);
        volumeText.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        r.addView(volumeText, new LinearLayout.LayoutParams(MATCH, WRAP));
        r.addView(icon(IconButton.Kind.MINUS, null, true, false, () -> key(Keys.VOLUME_DOWN)), new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return r;
    }

    /** Page two: type on the TV, then every app. */
    private View morePage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(10));
        sv.addView(page, new ViewGroup.LayoutParams(MATCH, WRAP));

        TextView typeLabel = text("Type on the TV", 12, Ui.MUTED);
        typeLabel.setPadding(Ui.dp(4), 0, 0, Ui.dp(6));
        page.addView(typeLabel);

        LinearLayout typing = row();
        typing.setGravity(Gravity.CENTER_VERTICAL);
        final MirrorEditText input = new MirrorEditText(this);
        style(input, "Starts typing on the TV as you type");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            enter();
            return true;
        });
        input.setOnEmptyBackspace(() -> key(Keys.DEL));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(Editable s) {
                if (syncing) return;
                String now = s.toString();
                lastEdit = System.currentTimeMillis();
                pushText(mirror, now);
                mirror = now;
            }
        });
        typing.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
        TextView enter = button("Enter", true);
        enter.setOnClickListener(v -> enter());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(Ui.dp(78), Ui.dp(48));
        ep.leftMargin = Ui.dp(8);
        typing.addView(enter, ep);
        page.addView(typing, new LinearLayout.LayoutParams(MATCH, WRAP));
        typingInput = input;
        mirror = "";

        TextView hint = text("Shows what is already in the TV's box, so backspace works on it.", 11, 0x80FFFFFF);
        hint.setPadding(Ui.dp(4), Ui.dp(6), 0, 0);
        page.addView(hint);

        LinearLayout keys = row();
        keys.addView(textButton("Search", () -> key(Keys.SEARCH)), cell());
        keys.addView(textButton("Clear box", () -> input.setText("")), cell());
        page.addView(keys, rowParams(10, 46));

        TextView appsLabel = text("Open an app on the TV", 12, Ui.MUTED);
        appsLabel.setPadding(Ui.dp(4), Ui.dp(20), 0, Ui.dp(2));
        page.addView(appsLabel);
        tvAppsBox = new LinearLayout(this);
        tvAppsBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(tvAppsBox, new LinearLayout.LayoutParams(MATCH, WRAP));
        rebuildTvApps();
        return sv;
    }

    // ---- the TV's app shortcuts: yours to edit ----

    private List<String[]> loadTvApps() {
        List<String[]> out = new ArrayList<>();
        String s = prefs.getString("tvApps", null);
        if (s == null) {
            for (String[] d : DEFAULT_TV_APPS) out.add(d.clone());
            return out;
        }
        for (String line : s.split("\n")) {
            String[] p = line.split("\t", 2);
            if (p.length == 2 && !p[0].isEmpty() && !p[1].isEmpty()) out.add(p);
        }
        return out;
    }

    private void saveTvApps(List<String[]> items) {
        StringBuilder sb = new StringBuilder();
        for (String[] i : items) sb.append(i[0]).append('\t').append(i[1]).append('\n');
        prefs.edit().putString("tvApps", sb.toString()).apply();
    }

    private void rebuildTvApps() {
        tvAppsBox.removeAllViews();
        final List<String[]> items = loadTvApps();
        for (int i = 0; i < items.size(); i += 3) {
            LinearLayout line = row();
            for (int j = i; j < i + 3; j++) {
                if (j < items.size()) {
                    final int index = j;
                    final String[] app = items.get(j);
                    line.addView(new KeyButton(this, app[0], false, () -> launch(app[1])).onLongPress(() -> removeTvApp(index)), cell());
                } else {
                    line.addView(new View(this), cell());
                }
            }
            tvAppsBox.addView(line, rowParams(8, 52));
        }
        TextView add = button("+ Add app", false);
        add.setOnClickListener(v -> addTvApp());
        tvAppsBox.addView(add, rowParams(12, 48));
        TextView hint = text("Press and hold a tile to remove it. To add an app you need its package name, e.g. com.spotify.tv.android "
                + "(it is the id in the app's Play Store address).", 11, Ui.MUTED);
        hint.setPadding(Ui.dp(4), Ui.dp(8), Ui.dp(4), 0);
        tvAppsBox.addView(hint);
        TextView reset = text("Reset to the default apps", 12, Ui.ACCENT2);
        reset.setPadding(Ui.dp(4), Ui.dp(12), Ui.dp(4), Ui.dp(8));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Replace your app list with the defaults?")
                .setPositiveButton("Reset", (d, w) -> {
                    prefs.edit().remove("tvApps").apply();
                    rebuildTvApps();
                })
                .setNegativeButton("Cancel", null)
                .show());
        tvAppsBox.addView(reset);
    }

    private void removeTvApp(final int index) {
        final List<String[]> items = loadTvApps();
        new AlertDialog.Builder(this).setMessage("Remove " + items.get(index)[0] + " from this list?")
                .setPositiveButton("Remove", (d, w) -> {
                    items.remove(index);
                    saveTvApps(items);
                    rebuildTvApps();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addTvApp() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), 0);
        final EditText name = field("Name, e.g. Plex");
        col.addView(name);
        final EditText pkg = field("Package name, e.g. com.plexapp.android");
        pkg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(MATCH, WRAP);
        pp.topMargin = Ui.dp(10);
        col.addView(pkg, pp);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Add an app").setView(col)
                .setPositiveButton("Add", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim().replace('\t', ' ').replace('\n', ' ');
            String p = pkg.getText().toString().trim();
            if (n.isEmpty()) {
                name.setError("Give it a name");
                return;
            }
            if (!p.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
                pkg.setError("A package name looks like com.example.app");
                return;
            }
            List<String[]> items = loadTvApps();
            items.add(new String[]{n, p});
            saveTvApps(items);
            rebuildTvApps();
            dialog.dismiss();
        });
    }

    private void setPad(FrameLayout holder, TextView toggle, boolean dpad) {
        prefs.edit().putString("pad", dpad ? "dpad" : "touch").apply();
        if (holder.getChildCount() > 1) holder.removeViewAt(0);
        if (dpad) {
            holder.addView(new DPadView(this, this::key), 0, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        } else {
            holder.addView(new TouchpadView(this, new TouchpadView.Callback() {
                @Override public void key(int code) {
                    MainActivity.this.key(code);
                }

                @Override public void hold(int code, boolean start) {
                    MainActivity.this.hold(code, start);
                }
            }), 0, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        toggle.setText(dpad ? "Touchpad" : "D-pad");
    }

    private void openTyping() {
        if (pager == null || typingInput == null) return;
        pager.setPage(1, true);
        ui.postDelayed(() -> {
            if (typingInput == null) return;
            typingInput.requestFocus();
            InputMethodManager m = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (m != null) m.showSoftInput(typingInput, InputMethodManager.SHOW_IMPLICIT);
        }, 180);
    }

    /**
     * The box mirrors the TV's text field: what you type, delete or replace here is sent as the smallest change
     * (backspaces for what went, text for what is new), so it always edits what is really in the TV.
     */
    private void pushText(String before, String now) {
        int p = 0, max = Math.min(before.length(), now.length());
        while (p < max && before.charAt(p) == now.charAt(p)) p++;
        if (p > 0 && Character.isHighSurrogate(before.charAt(p - 1))) p--;
        final int del = before.codePointCount(p, before.length());
        final String add = now.substring(p);
        if (del == 0 && add.isEmpty()) return;
        final RemoteSession s = session;
        if (s == null || !s.isOpen()) {
            connect();
            return;
        }
        sender.execute(() -> {
            for (int i = 0; i < del; i++) s.sendKey(Keys.DEL);
            if (!add.isEmpty()) s.sendText(add);
        });
    }

    /** The TV reported what its field holds. Our own typing is ahead of the TV's echo, so it wins for a moment. */
    private void applyTv(String value) {
        if (typingInput == null || value.equals(typingInput.getText().toString())) return;
        if (System.currentTimeMillis() - lastEdit < 800) return;
        syncing = true;
        typingInput.setText(value);
        typingInput.setSelection(value.length());
        mirror = value;
        syncing = false;
    }

    private void enter() {
        key(Keys.ENTER);
        syncing = true;
        typingInput.setText("");
        mirror = "";
        syncing = false;
    }

    // ---- small view helpers ----------------------------------------------------------------------

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String label, boolean primary) {
        TextView t = text(label, 15, primary ? Ui.BG : Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        t.setBackground(Ui.round(primary ? Ui.ACCENT : Ui.KEY, 14));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setClickable(true);
        return t;
    }

    private TextView tab(String label) {
        TextView t = text(label, 14, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        return t;
    }

    private void styleTab(TextView t, boolean on) {
        t.setTextColor(on ? Ui.BG : Ui.MUTED);
        t.setBackground(Ui.round(on ? Ui.ACCENT : Ui.SURFACE, 18));
    }

    /** A plain text button the size of an icon button. */
    private TextView textButton(String label, final Runnable run) {
        TextView t = text(label, 15, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(Ui.KEY, 14));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setClickable(true);
        t.setOnClickListener(v -> run.run());
        return t;
    }

    private LinearLayout.LayoutParams buttonParams(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52));
        p.topMargin = Ui.dp(topDp);
        return p;
    }

    private EditText field(String hint) {
        return style(new EditText(this), hint);
    }

    private EditText style(EditText e, String hint) {
        e.setHint(hint);
        e.setHintTextColor(0x668D8D9B);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        e.setBackground(Ui.round(Ui.SURFACE_HI, 14));
        return e;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams rowParams(int topDp, int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(heightDp));
        p.topMargin = Ui.dp(topDp);
        return p;
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p.leftMargin = Ui.dp(4);
        p.rightMargin = Ui.dp(4);
        return p;
    }

    private IconButton icon(IconButton.Kind kind, String label, boolean repeat, boolean accent, IconButton.Press press) {
        return new IconButton(this, kind, label, repeat, accent, press);
    }

    private void hideKeyboard(View v) {
        InputMethodManager m = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) m.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** A short reason for an error line, so a failure on the phone can be told apart without a debugger. */
    private static String brief(Throwable e) {
        if (e == null) return "closed";
        String m = e.getMessage();
        String s = e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() > 90 ? s.substring(0, 90) : s;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
