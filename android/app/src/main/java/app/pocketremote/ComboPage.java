package app.pocketremote;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.pocketremote.core.PcSession;

import java.util.ArrayList;
import java.util.List;

/**
 * A pad of shortcut buttons, each one a key combination sent to the laptop as a real chord (Win+Shift+R and so on).
 * Press and hold a button to edit or delete it; "+ Add shortcut" makes a new one. They are kept on the phone.
 */
final class ComboPage {
    private static final String PREF = "pcCombos";

    /** {name, keys}: the ones you are likely to want first. */
    private static final String[][] DEFAULTS = {
            {"Back", "alt+left"}, {"Forward", "alt+right"}, {"Snip", "win+shift+s"},
            {"Record", "win+shift+r"}, {"Alt+Tab", "alt+tab"}, {"Tasks", "win+tab"},
            {"Desktop", "win+d"}, {"Start", "win"}, {"Task mgr", "ctrl+shift+esc"}, {"Lock", "win+l"},
            {"Next desk", "win+ctrl+right"}, {"Prev desk", "win+ctrl+left"}, {"Close", "alt+f4"},
            {"Copy", "ctrl+c"}, {"Paste", "ctrl+v"}, {"Cut", "ctrl+x"},
            {"Undo", "ctrl+z"}, {"Redo", "ctrl+y"}, {"Select all", "ctrl+a"},
            {"Save", "ctrl+s"}, {"Find", "ctrl+f"}, {"Refresh", "f5"},
            {"New tab", "ctrl+t"}, {"Close tab", "ctrl+w"}, {"Reopen tab", "ctrl+shift+t"},
            {"Emoji", "win+."}, {"Clipboard", "win+v"}, {"Explorer", "win+e"},
            {"Run", "win+r"}, {"Settings", "win+i"}, {"Full screen", "f11"},
    };

    private final Context ctx;
    private final SharedPreferences prefs;
    private final PcLink link;
    private final ScrollView view;
    private final LinearLayout list;

    ComboPage(Context ctx, SharedPreferences prefs, PcLink link) {
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
    }

    View view() {
        return view;
    }

    private List<String[]> load() {
        String s = prefs.getString(PREF, null);
        List<String[]> out = new ArrayList<>();
        if (s == null) {
            for (String[] d : DEFAULTS) out.add(d.clone());
            return out;
        }
        for (String line : s.split("\n")) {
            String[] p = line.split("\t", 2);
            if (p.length == 2 && !p[0].isEmpty()) out.add(p);
        }
        return out;
    }

    private void save(List<String[]> items) {
        StringBuilder sb = new StringBuilder();
        for (String[] i : items) sb.append(i[0]).append('\t').append(i[1]).append('\n');
        prefs.edit().putString(PREF, sb.toString()).apply();
    }

    private void rebuild() {
        list.removeAllViews();
        final List<String[]> items = load();
        TextView hint = Ui.text(ctx, "Tap to send. Press and hold a button to edit it.", 12, Ui.MUTED);
        hint.setPadding(Ui.dp(4), 0, 0, Ui.dp(6));
        list.addView(hint);

        for (int i = 0; i < items.size(); i += 3) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 3; j++) {
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                cp.leftMargin = cp.rightMargin = Ui.dp(3);
                if (j < items.size()) {
                    final int index = j;
                    final String[] item = items.get(j);
                    row.addView(new KeyButton(ctx, item[0], false, () -> fire(item[1])).onLongPress(() -> edit(index)), cp);
                } else {
                    row.addView(new View(ctx), cp);
                }
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(48));
            rp.topMargin = Ui.dp(6);
            list.addView(row, rp);
        }

        TextView add = Ui.button(ctx, "+ Add shortcut", false);
        add.setOnClickListener(v -> edit(-1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(48));
        ap.topMargin = Ui.dp(14);
        list.addView(add, ap);
        TextView reset = Ui.text(ctx, "Reset to the standard set", 12, Ui.ACCENT2);
        reset.setPadding(Ui.dp(4), Ui.dp(14), Ui.dp(4), Ui.dp(8));
        reset.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                .setMessage("Replace all shortcuts with the standard set?")
                .setPositiveButton("Reset", (d, w) -> {
                    prefs.edit().remove(PREF).apply();
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show());
        list.addView(reset);
    }

    private void fire(String keys) {
        int[] vks = KeyNames.parse(keys);
        PcSession s = link.session();
        if (vks != null && s != null) s.chord(vks);
    }

    /** index -1 adds a new shortcut. */
    private void edit(final int index) {
        final List<String[]> items = load();
        final String[] current = index >= 0 ? items.get(index) : new String[]{"", ""};

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), 0);
        final EditText name = Ui.style(new EditText(ctx), "Name, e.g. Record");
        name.setText(current[0]);
        col.addView(name);
        final EditText keys = Ui.style(new EditText(ctx), "Keys, e.g. win+shift+r");
        keys.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keys.setText(current[1]);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.topMargin = Ui.dp(10);
        col.addView(keys, kp);
        final TextView preview = Ui.text(ctx, "", 14, Ui.GREEN);
        preview.setPadding(Ui.dp(4), Ui.dp(8), 0, 0);
        col.addView(preview);
        TextView help = Ui.text(ctx, KeyNames.HELP, 11, 0x99FFFFFF);
        help.setPadding(Ui.dp(4), Ui.dp(8), 0, Ui.dp(4));
        col.addView(help);

        keys.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(Editable s) {
                boolean ok = KeyNames.parse(s.toString()) != null;
                preview.setTextColor(ok ? Ui.GREEN : Ui.AMBER);
                preview.setText(ok ? "Sends " + KeyNames.pretty(s.toString()) : s.length() == 0 ? "" : "Not a known key combination");
            }
        });
        keys.setText(keys.getText());     // show the preview for the current text

        AlertDialog.Builder b = new AlertDialog.Builder(ctx).setTitle(index >= 0 ? "Edit shortcut" : "New shortcut")
                .setView(col)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (index >= 0) b.setNeutralButton("Delete", (d, w) -> {
            items.remove(index);
            save(items);
            rebuild();
        });
        final AlertDialog dialog = b.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim().replace('\t', ' ').replace('\n', ' ');
            String k = keys.getText().toString().trim().replace('\t', ' ').replace('\n', ' ');
            if (n.isEmpty()) {
                name.setError("Give it a name");
                return;
            }
            if (KeyNames.parse(k) == null) {
                keys.setError("Not a known key combination");
                return;
            }
            if (index >= 0) items.set(index, new String[]{n, k});
            else items.add(new String[]{n, k});
            save(items);
            rebuild();
            dialog.dismiss();
        });
    }
}
