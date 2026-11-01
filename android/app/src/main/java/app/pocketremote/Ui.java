package app.pocketremote;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.TextView;

final class Ui {
    private Ui() {}

    // One neutral scale and one accent: the accent only ever marks what is active or primary.
    static final int BG = 0xFF0E0E13, SURFACE = 0xFF17171F, SURFACE_HI = 0xFF20202B, SURFACE_LO = 0xFF13131A;
    static final int KEY = 0xFF20202B, KEY_DOWN = 0xFF2C2C3A, STROKE = 0xFF2A2A36, FAINT = 0xFF3A3A4A;
    static final int ACCENT = 0xFFFF35D2, ACCENT2 = 0xFF35D2FF;
    static final int TEXT = 0xFFECECF3, MUTED = 0xFF8D8D9B;
    static final int GREEN = 0xFF3DDC84, AMBER = 0xFFFFB300, RED = 0xFFFF5252;

    private static float density = 2f;

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** A sheet that rises from the bottom edge: rounded on top only. */
    static GradientDrawable sheet(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = dp(radiusDp);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** The one filled button of a screen is primary (accent); every other button is quiet. */
    static TextView button(Context c, String label, boolean primary) {
        TextView t = text(c, label, 15, primary ? BG : TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setBackground(round(primary ? ACCENT : KEY, 14));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setClickable(true);
        return t;
    }

    static <T extends EditText> T style(T e, String hint) {
        e.setHint(hint);
        e.setHintTextColor(0x668D8D9B);
        e.setTextColor(TEXT);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(round(SURFACE_HI, 14));
        return e;
    }
}
