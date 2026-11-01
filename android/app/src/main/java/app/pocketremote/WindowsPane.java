package app.pocketremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.pocketremote.core.PcSession;

import java.util.List;

/**
 * The laptop's open windows, front-most first (the order Alt+Tab shows). Tap one to bring it to the front, restoring it
 * if it is minimised; the cross asks it to close. The list refreshes by itself every couple of seconds while it is on
 * screen, and is left alone (so it doesn't jump while you scroll) when nothing has changed.
 */
final class WindowsPane {
    private final Context ctx;
    private final PcLink link;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ScrollView view;
    private final LinearLayout list;
    private final TextView status;
    private boolean active;
    private String shown = "";

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refresh();
            ui.postDelayed(this, 2500);
        }
    };

    WindowsPane(Context ctx, PcLink link) {
        this.ctx = ctx;
        this.link = link;
        view = new ScrollView(ctx);
        view.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(10));
        view.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = Ui.text(ctx, "Looking at the laptop…", 12, Ui.MUTED);
        status.setPadding(Ui.dp(4), 0, 0, Ui.dp(4));
        col.addView(status);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
    }

    View view() {
        return view;
    }

    /** Polls only while the pane is on screen. */
    void setActive(boolean on) {
        if (on == active) return;
        active = on;
        ui.removeCallbacks(poll);
        if (on) {
            shown = "";
            ui.post(poll);
        }
    }

    private void message(String text) {
        status.setText(text);
        shown = "";
        list.removeAllViews();
    }

    private void refresh() {
        PcSession s = link.session();
        if (s == null) {
            message("The laptop isn't connected.");
            return;
        }
        if (s.agentVersion() < 4) {
            message("The laptop app is out of date. Run its latest setup (Update) to see its windows here.");
            return;
        }
        s.requestWindows(w -> ui.post(() -> {
            if (active) render(w);
        }));
    }

    private void render(List<PcSession.Win> wins) {
        StringBuilder sig = new StringBuilder();
        for (PcSession.Win w : wins) sig.append(w.handle).append(w.foreground ? '*' : '-').append(w.title).append('\n');
        if (sig.toString().equals(shown)) return;
        shown = sig.toString();
        list.removeAllViews();
        status.setText(wins.isEmpty() ? "No open windows." : "Tap a window to bring it to the front. ✕ closes it.");
        for (final PcSession.Win w : wins) list.addView(row(w), rowParams());
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(6);
        return p;
    }

    private View row(final PcSession.Win w) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(w.foreground ? 0xFF2A1240 : Ui.SURFACE, 16));
        row.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(4), Ui.dp(6));
        row.setClickable(true);

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(ctx, w.title, 15, w.foreground ? Ui.ACCENT : Ui.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);
        String proc = w.process.isEmpty() ? "" : Character.toUpperCase(w.process.charAt(0)) + w.process.substring(1);
        TextView sub = Ui.text(ctx, (w.foreground ? "In front  •  " : "") + proc, 12, Ui.MUTED);
        sub.setSingleLine(true);
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = Ui.text(ctx, "✕", 18, Ui.MUTED);
        close.setGravity(Gravity.CENTER);
        close.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        close.setContentDescription("Close " + w.title);
        close.setOnClickListener(v -> {
            PcSession s = link.session();
            if (s != null) s.closeWindow(w.handle);
            ui.postDelayed(this::refresh, 600);
        });
        row.addView(close);

        row.setOnClickListener(v -> {
            PcSession s = link.session();
            if (s == null) return;
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            s.focusWindow(w.handle);
            ui.postDelayed(this::refresh, 400);
        });
        return row;
    }
}
