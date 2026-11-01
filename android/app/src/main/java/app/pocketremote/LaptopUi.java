package app.pocketremote;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import app.pocketremote.core.PcSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The laptop side of the app. Pairing first; then one calm screen: the mouse (with the keyboard sliding up over it
 * when you want to type), shortcuts, and apps, switched from a small dock. Everything else (media, power, settings)
 * is one sheet away, behind the "more" button in the top bar.
 */
final class LaptopUi {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int CTRL = 0x11, SHIFT = 0x10, ALT = 0x12, WIN = 0x5B, BACKSPACE = 0x08, TAB = 0x09,
            ENTER = 0x0D, ESC = 0x1B, PGUP = 0x21, PGDN = 0x22, END = 0x23, HOME = 0x24,
            LEFT = 0x25, UP = 0x26, RIGHT = 0x27, DOWN = 0x28, DELETE = 0x2E, F1 = 0x70,
            MEDIA_NEXT = 0xB0, MEDIA_PREV = 0xB1, MEDIA_PLAY = 0xB3, VOL_MUTE = 0xAD, VOL_DOWN = 0xAE, VOL_UP = 0xAF;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final PcLink link;
    private final Runnable onBackgroundChanged;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final FrameLayout root;

    private FrameLayout pages;
    private final View[] pageViews = new View[3];            // mouse, shortcuts, apps
    private final DockButton[] dock = new DockButton[4];     // mouse, keyboard, shortcuts, apps
    private LinearLayout keySheet, extraKeys;
    private View dockBar;
    private boolean keysOpen;

    private MousePadView pad;
    private AirPadView air;
    private FrameLayout padHolder;
    private int padUses;
    private MirrorEditText input;
    private String mirror = "";
    private boolean syncing;
    private final int[] modVk = {CTRL, SHIFT, ALT, WIN};
    private final int[] modState = new int[4];
    private final KeyButton[] modButtons = new KeyButton[4];

    private TextView pairStatus;
    private LinearLayout foundList;
    private final Set<String> found = new HashSet<>();

    LaptopUi(Context ctx, SharedPreferences prefs, PcLink link, Runnable onBackgroundChanged) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.link = link;
        this.onBackgroundChanged = onBackgroundChanged;
        root = new FrameLayout(ctx);
        refresh();
    }

    View view() {
        return root;
    }

    /** Shows pairing when no laptop is paired yet, the controls when one is. */
    void refresh() {
        keysOpen = false;
        root.removeAllViews();
        if (link.isPaired()) {
            link.setFoundListener(null);
            root.addView(buildMain());
        } else {
            root.addView(buildPair());
        }
    }

    /** The keyboard icon: slides the typing sheet up and opens the phone's keyboard. */
    void showKeys() {
        if (link.isPaired() && !keysOpen) openKeys();
    }

    /** The system Back button closes the typing sheet first. */
    boolean onBack() {
        if (!keysOpen) return false;
        closeKeys();
        return true;
    }

    /** The phone's volume buttons while the laptop is showing. */
    void volume(boolean up) {
        PcSession s = link.session();
        if (s != null) s.chord(up ? VOL_UP : VOL_DOWN);
    }

    // ---- pairing ---------------------------------------------------------------------------------------

    private View buildPair() {
        found.clear();
        ScrollView sv = new ScrollView(ctx);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(22), Ui.dp(16), Ui.dp(22), Ui.dp(20));
        sv.addView(col, new ViewGroup.LayoutParams(MATCH, WRAP));

        TextView title = Ui.text(ctx, "Pair your laptop", 26, Ui.TEXT);
        col.addView(title);
        TextView hint = Ui.text(ctx, "On the laptop, start Pocket Remote for PC (a tray icon appears). Its window shows a 6-digit code and the laptop's IP address.", 14, Ui.MUTED);
        hint.setPadding(0, Ui.dp(8), 0, Ui.dp(16));
        col.addView(hint);

        col.addView(Ui.text(ctx, "Laptops found on your Wi-Fi", 13, Ui.MUTED));
        foundList = new LinearLayout(ctx);
        foundList.setOrientation(LinearLayout.VERTICAL);
        TextView searching = Ui.text(ctx, "Searching…", 14, Ui.MUTED);
        searching.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        foundList.addView(searching);
        col.addView(foundList);

        TextView ipLabel = Ui.text(ctx, "Laptop IP address", 13, Ui.MUTED);
        ipLabel.setPadding(0, Ui.dp(14), 0, Ui.dp(6));
        col.addView(ipLabel);
        final EditText ip = Ui.style(new EditText(ctx), "192.168.0.4");
        ip.setInputType(InputType.TYPE_CLASS_TEXT);
        ip.setText(prefs.getString("lastPcIp", ""));
        col.addView(ip);

        TextView codeLabel = Ui.text(ctx, "Code shown on the laptop", 13, Ui.MUTED);
        codeLabel.setPadding(0, Ui.dp(14), 0, Ui.dp(6));
        col.addView(codeLabel);
        final EditText code = Ui.style(new EditText(ctx), "123456");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setTextSize(24);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.2f);
        col.addView(code);

        TextView go = Ui.button(ctx, "Pair", true);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(MATCH, Ui.dp(52));
        gp.topMargin = Ui.dp(16);
        col.addView(go, gp);
        pairStatus = Ui.text(ctx, "", 14, Ui.MUTED);
        pairStatus.setPadding(0, Ui.dp(10), 0, 0);
        col.addView(pairStatus);

        go.setOnClickListener(v -> {
            final String host = ip.getText().toString().trim();
            final String c = code.getText().toString().trim();
            if (host.isEmpty() || c.length() != 6) {
                pairStatus.setText("Enter the laptop's IP address and the 6-digit code.");
                return;
            }
            prefs.edit().putString("lastPcIp", host).apply();
            pairStatus.setText("Pairing…");
            net.execute(() -> {
                try {
                    final PcSession.Paired p = PcSession.pair(host, PcSession.PORT, c, Build.MODEL == null ? "Phone" : Build.MODEL);
                    link.save(host, p);
                    ui.post(() -> {
                        refresh();
                        link.reconnect();
                        onBackgroundChanged.run();
                    });
                } catch (SecurityException e) {
                    ui.post(() -> pairStatus.setText("The laptop says: " + e.getMessage() + "."));
                } catch (Exception e) {
                    ui.post(() -> pairStatus.setText("Couldn't reach the laptop at " + host + ". Is Pocket Remote for PC running, on the same Wi-Fi? (" + e.getClass().getSimpleName() + ")"));
                }
            });
        });

        link.setFoundListener((name, host, fp) -> ui.post(() -> {
            if (!found.add(host)) return;
            if (found.size() == 1) foundList.removeAllViews();
            TextView b = Ui.button(ctx, name + "  •  " + host, false);
            b.setOnClickListener(v -> ip.setText(host));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, Ui.dp(48));
            bp.topMargin = Ui.dp(6);
            foundList.addView(b, bp);
        }));
        return sv;
    }

    // ---- the screen: pages, the keyboard sheet and the dock ---------------------------------------------

    private View buildMain() {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        pages = new FrameLayout(ctx);
        col.addView(pages, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        keySheet = buildKeySheet();
        keySheet.setVisibility(View.GONE);
        col.addView(keySheet, new LinearLayout.LayoutParams(MATCH, WRAP));

        pageViews[0] = mousePage();
        pageViews[1] = new ComboPage(ctx, prefs, link).view();
        pageViews[2] = new AppsPage(ctx, prefs, link).view();

        // the dock: four icons; the page you are on shows its name
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(Ui.SURFACE, 26));
        bar.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
        dock[0] = new DockButton(ctx, IconButton.Kind.MOUSE, "Mouse", () -> selectPage(0));
        dock[1] = new DockButton(ctx, IconButton.Kind.KEYBOARD, "Keyboard", this::openKeys);
        dock[2] = new DockButton(ctx, IconButton.Kind.BOLT, "Shortcuts", () -> selectPage(1));
        dock[3] = new DockButton(ctx, IconButton.Kind.APPS, "Apps", () -> selectPage(2));
        bar.addView(spacer());
        for (DockButton d : dock) {
            bar.addView(d, new LinearLayout.LayoutParams(WRAP, WRAP));
            bar.addView(spacer());
        }
        dockBar = bar;
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(MATCH, Ui.dp(50));
        dp.leftMargin = dp.rightMargin = Ui.dp(12);
        dp.topMargin = Ui.dp(2);
        dp.bottomMargin = Ui.dp(8);
        col.addView(bar, dp);

        selectPage(0);
        return col;
    }

    private View spacer() {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    private void selectPage(int i) {
        if (keysOpen) closeKeys();
        pages.removeAllViews();
        pages.addView(pageViews[i], new FrameLayout.LayoutParams(MATCH, MATCH));
        dock[0].setState(i == 0 ? DockButton.ACTIVE : DockButton.IDLE);
        dock[1].setState(DockButton.IDLE);
        dock[2].setState(i == 1 ? DockButton.ACTIVE : DockButton.IDLE);
        dock[3].setState(i == 2 ? DockButton.ACTIVE : DockButton.IDLE);
    }

    /** Slides the typing sheet up (the dock steps aside for it) and opens the phone's keyboard. */
    private void openKeys() {
        if (keysOpen) return;
        keysOpen = true;
        keySheet.setVisibility(View.VISIBLE);
        dockBar.setVisibility(View.GONE);
        ui.postDelayed(() -> {
            if (!keysOpen) return;
            input.requestFocus();
            InputMethodManager m = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (m != null) m.showSoftInput(input, 0);
        }, 120);
    }

    /** Puts the sheet away; what was typed is already on the laptop, so the box starts empty next time. */
    private void closeKeys() {
        if (!keysOpen) return;
        keysOpen = false;
        resetTyping();
        hideKeyboard();
        input.clearFocus();
        keySheet.setVisibility(View.GONE);
        dockBar.setVisibility(View.VISIBLE);
    }

    /** The laptop screen is going out of view (the TV is showing): put the typing sheet away. */
    void onHidden() {
        if (input != null) closeKeys();
    }

    private void hideKeyboard() {
        InputMethodManager m = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) m.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    // ---- mouse -----------------------------------------------------------------------------------------

    private View mousePage() {
        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(6));
        padUses = prefs.getInt("pcPadUses", 0);

        LinearLayout area = new LinearLayout(ctx);
        area.setOrientation(LinearLayout.HORIZONTAL);
        pad = new MousePadView(ctx, new MousePadView.Callback() {
            @Override public void move(int dx, int dy) {
                PcSession s = link.session();
                if (s != null) s.move(dx, dy);
            }

            @Override public void click(int button) {
                noteUse();
                PcSession s = link.session();
                if (s != null) s.click(button);
            }

            @Override public void button(int button, boolean down) {
                PcSession s = link.session();
                if (s != null) s.button(button, down);
            }

            @Override public void scroll(int vertical, int horizontal) {
                PcSession s = link.session();
                if (s != null) s.scroll(vertical, horizontal);
            }
        });
        pad.setSpeed(prefs.getFloat("pcSpeed", 2.4f));
        pad.setNaturalScroll(prefs.getBoolean("pcNatural", true));
        pad.setHint(padUses < 6);
        air = new AirPadView(ctx, new AirPadView.Callback() {
            @Override public void move(int dx, int dy) {
                PcSession s = link.session();
                if (s != null) s.move(dx, dy);
            }

            @Override public void click(int button) {
                noteUse();
                PcSession s = link.session();
                if (s != null) s.click(button);
            }

            @Override public void button(int button, boolean down) {
                PcSession s = link.session();
                if (s != null) s.button(button, down);
            }
        });
        air.setSensitivity(prefs.getFloat("pcAirSens", 4500f));
        air.setHint(padUses < 6);

        // the pad and the air mouse take the same space; a small switch in the corner changes between them
        padHolder = new FrameLayout(ctx);
        final TextView mode = Ui.text(ctx, "", 12, Ui.MUTED);
        mode.setGravity(Gravity.CENTER);
        mode.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        mode.setBackground(Ui.round(Ui.SURFACE, 15));
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(WRAP, Ui.dp(30), Gravity.TOP | Gravity.END);
        ml.topMargin = ml.rightMargin = Ui.dp(10);
        padHolder.addView(mode, ml);
        showPad(mode, prefs.getBoolean("pcAir", false));
        mode.setOnClickListener(v -> showPad(mode, !prefs.getBoolean("pcAir", false)));
        area.addView(padHolder, new LinearLayout.LayoutParams(0, MATCH, 1f));
        ScrollStrip strip = new ScrollStrip(ctx, v -> {
            PcSession s = link.session();
            if (s != null) s.scroll(v, 0);
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(46), MATCH);
        sp.leftMargin = Ui.dp(8);
        area.addView(strip, sp);
        page.addView(area, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // Back and Forward are small icons; Left and Right take the room. (Middle click is a three-finger tap.)
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(mouseButton(IconButton.Kind.ARROW_LEFT, "Back", PcSession.BACK), fixed(54));
        buttons.addView(mouseButton("Left", "Left button", PcSession.LEFT), weighted(1f));
        buttons.addView(mouseButton("Right", "Right button", PcSession.RIGHT), weighted(1f));
        buttons.addView(mouseButton(IconButton.Kind.ARROW_RIGHT, "Forward", PcSession.FORWARD), fixed(54));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, Ui.dp(46));
        bp.topMargin = Ui.dp(8);
        page.addView(buttons, bp);
        return page;
    }

    /** The how-to text on the pads goes away once you have clicked a handful of times. */
    private void noteUse() {
        padUses++;
        if (padUses == 6) {
            pad.setHint(false);
            air.setHint(false);
        }
        if (padUses <= 6 || padUses % 10 == 0) prefs.edit().putInt("pcPadUses", padUses).apply();
    }

    private void showPad(TextView mode, boolean useAir) {
        prefs.edit().putBoolean("pcAir", useAir).apply();
        if (padHolder.getChildCount() > 1) padHolder.removeViewAt(0);
        padHolder.addView(useAir ? air : pad, 0, new FrameLayout.LayoutParams(MATCH, MATCH));
        mode.setText(useAir ? "Trackpad" : "Air mouse");
    }

    private View mouseButton(String label, String description, final int button) {
        return new HoldButton(ctx, label, description, down -> {
            PcSession s = link.session();
            if (s != null) s.button(button, down);
        });
    }

    private View mouseButton(IconButton.Kind icon, String description, final int button) {
        return new HoldButton(ctx, icon, description, down -> {
            PcSession s = link.session();
            if (s != null) s.button(button, down);
        });
    }

    private LinearLayout.LayoutParams weighted(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, MATCH, w);
        p.leftMargin = p.rightMargin = Ui.dp(3);
        return p;
    }

    private LinearLayout.LayoutParams fixed(int wDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(wDp), MATCH);
        p.leftMargin = p.rightMargin = Ui.dp(3);
        return p;
    }

    // ---- the typing sheet ------------------------------------------------------------------------------

    private LinearLayout buildKeySheet() {
        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Ui.sheet(Ui.SURFACE, 22));
        sheet.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(8));

        LinearLayout typing = new LinearLayout(ctx);
        typing.setOrientation(LinearLayout.HORIZONTAL);
        typing.setGravity(Gravity.CENTER_VERTICAL);
        input = Ui.style(new MirrorEditText(ctx), "Type here, it goes to the laptop");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            tapKey(ENTER);
            syncing = true;
            input.setText("");
            mirror = "";
            syncing = false;
            return true;
        });
        input.setOnEmptyBackspace(() -> {
            PcSession s = link.session();
            if (s != null) s.chord(BACKSPACE);
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(Editable s) {
                if (syncing) return;
                String now = s.toString();
                pushText(mirror, now);
                mirror = now;
            }
        });
        typing.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(46), 1f));
        final IconButton[] clipAnchor = new IconButton[1];
        clipAnchor[0] = new IconButton(ctx, IconButton.Kind.CLIPBOARD, null, false, false, () -> clipboardMenu(clipAnchor[0])).bare(true).tint(Ui.MUTED);
        typing.addView(clipAnchor[0], new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        typing.addView(new IconButton(ctx, IconButton.Kind.CHEVRON_DOWN, null, false, false, this::closeKeys).bare(true).tint(Ui.MUTED),
                new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        sheet.addView(typing, new LinearLayout.LayoutParams(MATCH, WRAP));

        // sticky modifiers: tap = for the next key, tap again = locked, once more = off
        LinearLayout mods = new LinearLayout(ctx);
        mods.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Ctrl", "Shift", "Alt", "Win"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            modButtons[i] = key(names[i], false, () -> cycleMod(idx));
            mods.addView(modButtons[i], cell());
        }
        sheet.addView(mods, rowLp(8, 42));

        LinearLayout special = new LinearLayout(ctx);
        special.setOrientation(LinearLayout.HORIZONTAL);
        special.addView(key("Esc", false, () -> tapKey(ESC)), cell());
        special.addView(key("Tab", false, () -> tapKey(TAB)), cell());
        special.addView(key("⌫", true, () -> tapKey(BACKSPACE)), cell());
        special.addView(key("Del", true, () -> tapKey(DELETE)), cell());
        special.addView(key("Enter", false, () -> tapKey(ENTER)), cell());
        special.addView(key("⋯", false, () -> extraKeys.setVisibility(extraKeys.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), cell());
        sheet.addView(special, rowLp(6, 42));

        // arrows, navigation and function keys stay tucked away until the "⋯" key is tapped
        extraKeys = new LinearLayout(ctx);
        extraKeys.setOrientation(LinearLayout.VERTICAL);
        extraKeys.setVisibility(View.GONE);
        LinearLayout nav = new LinearLayout(ctx);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.addView(key("←", true, () -> tapKey(LEFT)), cell());
        nav.addView(key("↑", true, () -> tapKey(UP)), cell());
        nav.addView(key("↓", true, () -> tapKey(DOWN)), cell());
        nav.addView(key("→", true, () -> tapKey(RIGHT)), cell());
        nav.addView(key("Home", false, () -> tapKey(HOME)), cell());
        nav.addView(key("End", false, () -> tapKey(END)), cell());
        nav.addView(key("PgUp", true, () -> tapKey(PGUP)), cell());
        nav.addView(key("PgDn", true, () -> tapKey(PGDN)), cell());
        extraKeys.addView(nav, rowLp(6, 40));
        HorizontalScrollView fs = new HorizontalScrollView(ctx);
        fs.setHorizontalScrollBarEnabled(false);
        LinearLayout fkeys = new LinearLayout(ctx);
        fkeys.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 12; i++) {
            final int vk = F1 + i;
            fkeys.addView(key("F" + (i + 1), false, () -> tapKey(vk)), sized(54, 40, 3));
        }
        fs.addView(fkeys);
        extraKeys.addView(fs, rowLp(6, 40));
        sheet.addView(extraKeys, new LinearLayout.LayoutParams(MATCH, WRAP));
        return sheet;
    }

    private void clipboardMenu(View anchor) {
        PopupMenu pm = new PopupMenu(ctx, anchor);
        pm.getMenu().add("Send clipboard to the laptop");
        pm.getMenu().add("Paste it on the laptop");
        pm.getMenu().add("Copy the laptop's clipboard here");
        pm.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.startsWith("Send")) sendClipboard(false);
            else if (t.startsWith("Paste")) sendClipboard(true);
            else fetchClipboard();
            return true;
        });
        pm.show();
    }

    private KeyButton key(String label, boolean repeat, KeyButton.Press p) {
        return new KeyButton(ctx, label, repeat, p);
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, MATCH, 1f);
        p.leftMargin = p.rightMargin = Ui.dp(3);
        return p;
    }

    private LinearLayout.LayoutParams sized(int wDp, int hDp, int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(wDp), Ui.dp(hDp));
        p.leftMargin = p.rightMargin = Ui.dp(marginDp);
        return p;
    }

    private LinearLayout.LayoutParams rowLp(int topDp, int hDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, Ui.dp(hDp));
        p.topMargin = Ui.dp(topDp);
        return p;
    }

    private void cycleMod(int i) {
        modState[i] = (modState[i] + 1) % 3;
        modButtons[i].setState(modState[i]);
    }

    private int[] activeMods() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < 4; i++) if (modState[i] != KeyButton.OFF) l.add(modVk[i]);
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    /** One-shot modifiers are used up by the key they were armed for; locked ones stay on. */
    private void consumeArmed() {
        for (int i = 0; i < 4; i++) {
            if (modState[i] == KeyButton.ARMED) {
                modState[i] = KeyButton.OFF;
                modButtons[i].setState(KeyButton.OFF);
            }
        }
    }

    /** Empties the typing box without sending anything (what it held is already on the laptop) and lets go of modifiers. */
    private void resetTyping() {
        syncing = true;
        input.setText("");
        mirror = "";
        syncing = false;
        for (int i = 0; i < 4; i++) {
            modState[i] = KeyButton.OFF;
            modButtons[i].setState(KeyButton.OFF);
        }
    }

    /** Presses a key together with whatever modifiers are on. */
    private void tapKey(int vk) {
        PcSession s = link.session();
        if (s == null) return;
        int[] mods = activeMods();
        if (mods.length == 0) {
            s.chord(vk);
            return;
        }
        int[] all = new int[mods.length + 1];
        System.arraycopy(mods, 0, all, 0, mods.length);
        all[mods.length] = vk;
        s.chord(all);
        consumeArmed();
    }

    /**
     * The box is a scratch pad whose changes are sent as they happen: backspaces for what was deleted, text for what
     * was added. A backspace on an empty box goes straight through, so it still deletes on the laptop.
     */
    private void pushText(String before, String now) {
        int p = 0, max = Math.min(before.length(), now.length());
        while (p < max && before.charAt(p) == now.charAt(p)) p++;
        if (p > 0 && Character.isHighSurrogate(before.charAt(p - 1))) p--;
        int del = before.codePointCount(p, before.length());
        String add = now.substring(p);
        PcSession s = link.session();
        if (s == null) return;
        for (int i = 0; i < del; i++) s.chord(BACKSPACE);
        if (add.isEmpty()) return;
        // Ctrl+C and friends: a modifier is on and one letter or digit was typed
        if (add.length() == 1 && activeMods().length > 0) {
            char c = Character.toUpperCase(add.charAt(0));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                tapKey(c);
                return;
            }
        }
        s.text(add);
    }

    // ---- the "more" sheet: media, power, settings --------------------------------------------------------

    /** What the top bar's "more" button opens while the laptop is showing. */
    void showMore(final Runnable reconnect, final Runnable forget, final Runnable changeDevices) {
        if (!link.isPaired()) {
            toast("Pair the laptop first");
            return;
        }
        final Dialog[] dlg = new Dialog[1];
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(sheetLabel("Media and volume", 0));
        LinearLayout media = new LinearLayout(ctx);
        media.setOrientation(LinearLayout.HORIZONTAL);
        media.addView(icon(IconButton.Kind.REWIND, false, () -> tapKey(MEDIA_PREV)), cell());
        media.addView(icon(IconButton.Kind.PLAY_PAUSE, false, () -> tapKey(MEDIA_PLAY)), cell());
        media.addView(icon(IconButton.Kind.FORWARD, false, () -> tapKey(MEDIA_NEXT)), cell());
        col.addView(media, rowLp(6, 48));
        LinearLayout vol = new LinearLayout(ctx);
        vol.setOrientation(LinearLayout.HORIZONTAL);
        vol.addView(icon(IconButton.Kind.MINUS, true, () -> tapKey(VOL_DOWN)), cell());
        vol.addView(icon(IconButton.Kind.MUTE, false, () -> tapKey(VOL_MUTE)), cell());
        vol.addView(icon(IconButton.Kind.PLUS, true, () -> tapKey(VOL_UP)), cell());
        col.addView(vol, rowLp(6, 48));

        col.addView(sheetLabel("Power", 18));
        LinearLayout power = new LinearLayout(ctx);
        power.setOrientation(LinearLayout.HORIZONTAL);
        power.addView(key("Lock", false, () -> system(PcSession.SYS_LOCK)), cell());
        power.addView(key("Sleep", false, () -> system(PcSession.SYS_SLEEP)), cell());
        power.addView(key("Screen off", false, () -> system(PcSession.SYS_SCREEN_OFF)), cell());
        col.addView(power, rowLp(6, 44));
        LinearLayout power2 = new LinearLayout(ctx);
        power2.setOrientation(LinearLayout.HORIZONTAL);
        power2.addView(key("Restart", false, () -> confirm("Restart the laptop?", "Restart", PcSession.SYS_RESTART)), cell());
        power2.addView(key("Shut down", false, () -> confirm("Shut down the laptop?", "Shut down", PcSession.SYS_SHUTDOWN)), cell());
        col.addView(power2, rowLp(6, 44));

        col.addView(sheetLabel("Pointer", 18));
        col.addView(slider("Trackpad speed", 40, Math.round((prefs.getFloat("pcSpeed", 2.4f) - 1f) * 10f), progress -> {
            float v = 1f + progress / 10f;
            prefs.edit().putFloat("pcSpeed", v).apply();
            if (pad != null) pad.setSpeed(v);
        }));
        col.addView(slider("Air mouse speed", 75, Math.round((prefs.getFloat("pcAirSens", 4500f) - 1500f) / 100f), progress -> {
            float v = 1500f + progress * 100f;
            prefs.edit().putFloat("pcAirSens", v).apply();
            if (air != null) air.setSensitivity(v);
        }));
        col.addView(toggle("Two-finger scroll follows your fingers", prefs.getBoolean("pcNatural", true), on -> {
            prefs.edit().putBoolean("pcNatural", on).apply();
            if (pad != null) pad.setNaturalScroll(on);
        }));

        col.addView(sheetLabel("Connection", 18));
        col.addView(toggle("Stay connected when the app is closed", prefs.getBoolean("pcBackground", true), on -> {
            prefs.edit().putBoolean("pcBackground", on).apply();
            onBackgroundChanged.run();
        }));
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.addView(key("Reconnect", false, () -> {
            dlg[0].dismiss();
            reconnect.run();
        }), cell());
        foot.addView(key("Forget this laptop", false, () -> {
            dlg[0].dismiss();
            forget.run();
        }), cell());
        col.addView(foot, rowLp(10, 44));
        col.addView(key("Change what I control", false, () -> {
            dlg[0].dismiss();
            changeDevices.run();
        }), rowLp(8, 44));

        dlg[0] = Sheet.show(ctx, col);
    }

    private TextView sheetLabel(String s, int topDp) {
        TextView t = Ui.text(ctx, s, 12, Ui.MUTED);
        t.setPadding(Ui.dp(4), Ui.dp(topDp), 0, 0);
        return t;
    }

    private interface IntChange {
        void on(int value);
    }

    private interface BoolChange {
        void on(boolean value);
    }

    private View slider(String label, int max, int progress, final IntChange change) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(ctx, label, 14, Ui.TEXT);
        t.setPadding(Ui.dp(4), Ui.dp(10), 0, 0);
        box.addView(t);
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                change.on(p);
            }

            @Override public void onStartTrackingTouch(SeekBar b) {}

            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        box.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        return box;
    }

    private View toggle(String label, boolean on, final BoolChange change) {
        Switch sw = new Switch(ctx);
        sw.setText(label);
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(14);
        sw.setChecked(on);
        sw.setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(10));
        sw.setOnCheckedChangeListener((b, checked) -> change.on(checked));
        return sw;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** The phone's clipboard text onto the laptop's clipboard, and optionally pasted there with Ctrl+V. */
    private void sendClipboard(boolean paste) {
        PcSession s = link.session();
        if (s == null) {
            toast("The laptop isn't connected");
            return;
        }
        if (s.agentVersion() < 4) {
            toast("The laptop app is out of date: run its latest setup (Update)");
            return;
        }
        android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData d = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = d == null || d.getItemCount() == 0 ? null : d.getItemAt(0).coerceToText(ctx);
        if (text == null || text.length() == 0) {
            toast("The phone's clipboard is empty");
            return;
        }
        String t = text.toString();
        if (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) {
            toast("That is too long to send (about 60,000 bytes at most)");
            return;
        }
        s.setClipboard(t);
        if (paste) s.chord(CTRL, 0x56);
        toast(paste ? "Pasted " + t.length() + " characters" : "Sent " + t.length() + " characters to the laptop");
    }

    /** The laptop's clipboard text onto the phone's clipboard. */
    private void fetchClipboard() {
        PcSession s = link.session();
        if (s == null) {
            toast("The laptop isn't connected");
            return;
        }
        if (s.agentVersion() < 4) {
            toast("The laptop app is out of date: run its latest setup (Update)");
            return;
        }
        s.requestClipboard(text -> ui.post(() -> {
            if (text.isEmpty()) {
                toast("The laptop's clipboard has no text");
                return;
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("laptop", text));
            toast("Copied " + text.length() + " characters from the laptop");
        }));
    }

    private IconButton icon(IconButton.Kind kind, boolean repeat, IconButton.Press press) {
        return new IconButton(ctx, kind, null, repeat, false, press);
    }

    private void system(int action) {
        PcSession s = link.session();
        if (s != null) s.system(action);
    }

    private void confirm(String message, String action, final int code) {
        new AlertDialog.Builder(ctx).setMessage(message)
                .setPositiveButton(action, (d, w) -> system(code))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
