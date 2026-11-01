package app.pocketremote;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** A panel that slides up from the bottom edge over a dimmed screen: the one place the less-used controls live. */
final class Sheet {
    private Sheet() {}

    static Dialog show(Context ctx, View content) {
        Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.sheet(Ui.SURFACE, 26));
        box.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(20));

        View grab = new View(ctx);
        grab.setBackground(Ui.round(Ui.FAINT, 2));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = Ui.dp(12);
        box.addView(grab, gp);

        int cap = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.78f);
        box.addView(new Capped(ctx, cap, content), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(box);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.55f);
        }
        d.show();
        return d;
    }

    /** A scroll view that never grows past a fixed height, so a long sheet scrolls instead of covering the screen. */
    private static final class Capped extends ScrollView {
        private final int cap;

        Capped(Context c, int cap, View content) {
            super(c);
            this.cap = cap;
            setVerticalScrollBarEnabled(false);
            addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }
}
