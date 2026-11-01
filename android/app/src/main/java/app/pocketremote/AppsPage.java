package app.pocketremote;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.pocketremote.core.PcSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tiles for the apps you open on the laptop. Tap one to open it there; press and hold to remove it. "+ Add app" lists
 * the laptop's own Start menu apps to pick from (desktop and Store apps), or takes any path, folder or web link.
 */
final class AppsPage {
    private static final String PREF = "pcApps";
    /** Between a program and its arguments in a saved entry (a control character can't be stored in preferences). */
    private static final String ARGS = "¶";

    private final Context ctx;
    private final SharedPreferences prefs;
    private final PcLink link;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ScrollView view;                 // the launcher
    private final LinearLayout list;
    private final LinearLayout root;               // what the tab shows: a switch between Running and Launcher
    private final FrameLayout content;
    private final WindowsPane windows;
    private final TextView runTab, launchTab;

    AppsPage(Context ctx, SharedPreferences prefs, PcLink link) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.link = link;
        view = new ScrollView(ctx);
        view.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(10));
        view.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuild();

        // "Running" lists the open windows to switch between; "Launcher" is the tiles for opening apps
        windows = new WindowsPane(ctx, link);
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setBackground(Ui.round(Ui.SURFACE, 18));
        pill.setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), Ui.dp(2));
        runTab = paneTab("Running", "run");
        launchTab = paneTab("Launcher", "launch");
        pill.addView(runTab, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pill.addView(launchTab, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.addView(pill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(34)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.dp(6);
        root.addView(head, hp);
        content = new FrameLayout(ctx);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        showPane(prefs.getString("pcAppsPane", "launch"));
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                windows.setActive("run".equals(AppsPage.this.prefs.getString("pcAppsPane", "launch")));
            }

            @Override public void onViewDetachedFromWindow(View v) {
                windows.setActive(false);
            }
        });
    }

    private TextView paneTab(String label, final String pane) {
        TextView t = Ui.text(ctx, label, 14, Ui.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setOnClickListener(v -> showPane(pane));
        return t;
    }

    private void showPane(String pane) {
        boolean run = "run".equals(pane);
        prefs.edit().putString("pcAppsPane", run ? "run" : "launch").apply();
        content.removeAllViews();
        content.addView(run ? windows.view() : view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        runTab.setTextColor(run ? Ui.TEXT : Ui.MUTED);
        runTab.setBackground(run ? Ui.round(Ui.SURFACE_HI, 15) : null);
        launchTab.setTextColor(run ? Ui.MUTED : Ui.TEXT);
        launchTab.setBackground(run ? null : Ui.round(Ui.SURFACE_HI, 15));
        windows.setActive(run && root.isAttachedToWindow());
    }

    View view() {
        return root;
    }

    private List<String[]> load() {
        List<String[]> out = new ArrayList<>();
        for (String line : prefs.getString(PREF, "").split("\n")) {
            String[] p = line.split("\t", 3);
            if (p.length >= 2 && !p[0].isEmpty() && !p[1].isEmpty()) out.add(new String[]{p[0], p[1], p.length > 2 ? p[2] : ""});
        }
        return out;
    }

    private void save(List<String[]> items) {
        StringBuilder sb = new StringBuilder();
        for (String[] i : items) sb.append(i[0]).append('\t').append(i[1]).append('\t').append(i.length > 2 ? i[2] : "").append('\n');
        prefs.edit().putString(PREF, sb.toString()).apply();
    }

    private void rebuild() {
        list.removeAllViews();
        final List<String[]> items = load();
        if (items.isEmpty()) {
            TextView empty = Ui.text(ctx, "Add the apps you open on the laptop most. One tap opens them there.", 14, Ui.MUTED);
            empty.setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(6));
            list.addView(empty);
        } else {
            TextView hint = Ui.text(ctx, "Tap to open, or switch to it if it is already open. Press and hold for options.", 12, Ui.MUTED);
            hint.setPadding(Ui.dp(4), 0, 0, Ui.dp(6));
            list.addView(hint);
        }
        for (int i = 0; i < items.size(); i += 3) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 3; j++) {
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                cp.leftMargin = cp.rightMargin = Ui.dp(3);
                if (j < items.size()) {
                    final int index = j;
                    final String[] item = items.get(j);
                    row.addView(new KeyButton(ctx, item[0], false, () -> open(item)).onLongPress(() -> itemMenu(index)), cp);
                } else {
                    row.addView(new View(ctx), cp);
                }
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(56));
            rp.topMargin = Ui.dp(6);
            list.addView(row, rp);
        }
        TextView add = Ui.button(ctx, "+ Add app", false);
        add.setOnClickListener(v -> new AlertDialog.Builder(ctx).setItems(
                new String[]{"Choose from the laptop's apps", "A path, folder or web link"},
                (d, which) -> {
                    if (which == 0) pick();
                    else custom();
                }).show());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(48));
        ap.topMargin = Ui.dp(14);
        list.addView(add, ap);
    }

    /**
     * Tap = "take me there": if a window whose title contains the tile's match text (its own name unless you changed
     * it) is already open, that window comes to the front; otherwise the app is opened. An older laptop app that can't
     * list windows just opens it.
     */
    private void open(final String[] app) {
        final PcSession s = link.session();
        if (s == null) {
            Toast.makeText(ctx, "The laptop isn't connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (s.agentVersion() < 2) {
            Toast.makeText(ctx, "The laptop app is out of date: run its latest setup (Update)", Toast.LENGTH_LONG).show();
            return;
        }
        final String match = app.length > 2 ? app[2] : "";
        final String id = app[1].replace(ARGS, "\u0001");      // the agent's own separator between a program and its arguments
        if ("-".equals(match) || s.agentVersion() < 4) {
            launchNew(s, app[0], id);
            return;
        }
        final String needle = (match.isEmpty() ? app[0] : match).toLowerCase(Locale.ROOT);
        final boolean[] done = {false};
        ui.postDelayed(() -> {                                  // no answer in time: just open it
            if (!done[0]) {
                done[0] = true;
                launchNew(s, app[0], id);
            }
        }, 900);
        s.requestWindows(wins -> ui.post(() -> {
            if (done[0]) return;
            done[0] = true;
            PcSession.Win hit = null;
            for (PcSession.Win w : wins) {
                if (w.title.toLowerCase(Locale.ROOT).contains(needle)) {
                    hit = w;
                    break;
                }
            }
            if (hit == null) {
                launchNew(s, app[0], id);
                return;
            }
            s.focusWindow(hit.handle);
            Toast.makeText(ctx, "Switched to " + app[0], Toast.LENGTH_SHORT).show();
        }));
    }

    private void launchNew(PcSession s, String name, String id) {
        s.launch(id);
        Toast.makeText(ctx, "Opening " + name, Toast.LENGTH_SHORT).show();
    }

    private void itemMenu(final int index) {
        final List<String[]> items = load();
        new AlertDialog.Builder(ctx).setTitle(items.get(index)[0])
                .setItems(new String[]{"Which window counts as open…", "Remove"}, (d, which) -> {
                    if (which == 0) matchDialog(index);
                    else remove(index);
                }).show();
    }

    private void matchDialog(final int index) {
        final List<String[]> items = load();
        final String[] item = items.get(index);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), 0);
        TextView why = Ui.text(ctx, "Tapping the tile brings an open window to the front when its title contains this text, instead of "
                + "opening the app again. Leave it empty to use \"" + item[0] + "\".", 13, Ui.MUTED);
        col.addView(why);
        final EditText field = Ui.style(new EditText(ctx), item[0]);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText("-".equals(item[2]) ? "" : item[2]);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(10);
        col.addView(field, fp);
        final android.widget.Switch always = new android.widget.Switch(ctx);
        always.setText("Always open a new one");
        always.setTextColor(Ui.TEXT);
        always.setChecked("-".equals(item[2]));
        always.setPadding(Ui.dp(4), Ui.dp(12), Ui.dp(4), Ui.dp(4));
        col.addView(always);
        new AlertDialog.Builder(ctx).setTitle(item[0]).setView(col)
                .setPositiveButton("Save", (d, w) -> {
                    String m = always.isChecked() ? "-" : field.getText().toString().trim().replace('\t', ' ').replace('\n', ' ');
                    items.set(index, new String[]{item[0], item[1], m});
                    save(items);
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void remove(final int index) {
        final List<String[]> items = load();
        new AlertDialog.Builder(ctx).setMessage("Remove " + items.get(index)[0] + " from this list?")
                .setPositiveButton("Remove", (d, w) -> {
                    items.remove(index);
                    save(items);
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- choosing from the laptop's own list ----

    private void pick() {
        final PcSession s = link.session();
        if (s == null) {
            Toast.makeText(ctx, "The laptop isn't connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (s.agentVersion() < 2) {
            new AlertDialog.Builder(ctx)
                    .setTitle("The laptop app is out of date")
                    .setMessage("It can't list or open apps yet. On the laptop, download the latest Pocket Remote for PC setup, run it and "
                            + "choose Update (your pairing is kept), then try again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final List<String[]> all = new ArrayList<>();
        final List<String[]> shown = new ArrayList<>();
        final Set<String> have = new HashSet<>();
        for (String[] a : load()) have.add(a[1]);
        final Set<String> picked = new HashSet<>();

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), 0);
        final EditText filter = Ui.style(new EditText(ctx), "Search the laptop's apps");
        filter.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(filter);
        final TextView status = Ui.text(ctx, "Asking the laptop…", 13, Ui.MUTED);
        status.setPadding(Ui.dp(4), Ui.dp(8), 0, Ui.dp(4));
        col.addView(status);
        final ListView lv = new ListView(ctx);
        lv.setDividerHeight(0);
        col.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(320)));

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }

            @Override public Object getItem(int i) { return shown.get(i); }

            @Override public long getItemId(int i) { return i; }

            @Override public View getView(int i, View convert, ViewGroup parent) {
                TextView t = convert instanceof TextView ? (TextView) convert : new TextView(ctx);
                String[] a = shown.get(i);
                boolean already = have.contains(a[0]), on = picked.contains(a[0]);
                t.setText((already || on ? "✓  " : "      ") + a[1] + (already ? "   (added)" : ""));
                t.setTextColor(already ? Ui.MUTED : on ? Ui.ACCENT : Ui.TEXT);
                t.setTextSize(15);
                t.setPadding(Ui.dp(8), Ui.dp(12), Ui.dp(8), Ui.dp(12));
                t.setGravity(Gravity.CENTER_VERTICAL);
                return t;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, i, id) -> {
            String key = shown.get(i)[0];
            if (have.contains(key)) return;
            if (!picked.remove(key)) picked.add(key);
            adapter.notifyDataSetChanged();
        });
        final Runnable refilter = () -> {
            String q = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (String[] a : all) if (q.isEmpty() || a[1].toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
            adapter.notifyDataSetChanged();
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s2, int a, int b, int c) {}

            @Override public void afterTextChanged(Editable e) { refilter.run(); }
        });

        final AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle("Add apps").setView(col)
                .setPositiveButton("Add", null)
                .setNeutralButton("Refresh", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String[]> items = load();
            for (String[] a : all) if (picked.contains(a[0])) items.add(new String[]{a[1], a[0]});
            save(items);
            rebuild();
            dialog.dismiss();
        });

        // Asks the laptop for its apps. Refresh makes it look again instead of using what it remembers, so apps
        // installed a moment ago show up. Your ticks are kept across a refresh.
        final int[] round = {0};
        final java.util.function.Consumer<Boolean> scan = refresh -> {
            final int mine = ++round[0];
            final PcSession cur = link.session();
            all.clear();
            refilter.run();
            if (cur == null) {
                status.setText("The laptop isn't connected.");
                status.setTextColor(Ui.AMBER);
                return;
            }
            status.setText(refresh ? "Looking for newly installed apps…" : "Asking the laptop…");
            status.setTextColor(Ui.MUTED);
            final boolean[] got = {false};
            // the first list can take a few seconds on the laptop; after ten, something is wrong and we say so
            ui.postDelayed(() -> {
                if (!got[0] && round[0] == mine && dialog.isShowing()) {
                    status.setText("The laptop isn't answering. Check that its Pocket Remote for PC is running and up to date, then try again.");
                    status.setTextColor(Ui.AMBER);
                }
            }, 10000);
            final boolean canRescan = cur.agentVersion() >= 3;
            cur.requestApps(refresh && canRescan, apps -> ui.post(() -> {
                if (round[0] != mine) return;
                got[0] = true;
                if (!dialog.isShowing()) return;
                all.clear();
                all.addAll(apps);
                String note = refresh && !canRescan ? " The laptop app is older than this phone app: update it to rescan." : "";
                status.setText(apps.isEmpty() ? "The laptop returned no apps." : apps.size() + " apps. Tap to choose, then Add." + note);
                status.setTextColor(Ui.MUTED);
                refilter.run();
            }));
        };
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> scan.accept(true));
        scan.accept(false);
    }

    // ---- anything else ----

    private void custom() {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), 0);
        final EditText name = Ui.style(new EditText(ctx), "Name, e.g. Notes");
        col.addView(name);
        final EditText target = Ui.style(new EditText(ctx), "C:\\path\\app.exe, a folder, or https://…");
        target.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Ui.dp(10);
        col.addView(target, tp);
        final EditText args = Ui.style(new EditText(ctx), "Arguments (optional)");
        args.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(args, tp);

        final AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle("Add to the list").setView(col)
                .setPositiveButton("Add", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim().replace('\t', ' ').replace('\n', ' ');
            String t = target.getText().toString().trim().replace('\n', ' ').replace('\t', ' ');
            String a = args.getText().toString().trim().replace('\n', ' ').replace('\t', ' ');
            if (n.isEmpty()) {
                name.setError("Give it a name");
                return;
            }
            if (t.isEmpty()) {
                target.setError("Enter a path or link");
                return;
            }
            List<String[]> items = load();
            items.add(new String[]{n, "run:" + t + (a.isEmpty() ? "" : ARGS + a)});
            save(items);
            rebuild();
            dialog.dismiss();
        });
    }
}
