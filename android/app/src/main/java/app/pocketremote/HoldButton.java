package app.pocketremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** A mouse button: down while your finger is on it, up when it lifts, so it can hold a drag. */
final class HoldButton extends View {
    interface Callback {
        void onChange(boolean down);
    }

    private final String label;
    private final IconButton glyph;      // draws the icon when the button has one instead of a word
    private final Callback cb;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private boolean down;

    HoldButton(Context c, String label, String description, Callback cb) {
        this(c, label, null, description, cb);
    }

    HoldButton(Context c, IconButton.Kind icon, String description, Callback cb) {
        this(c, null, icon, description, cb);
    }

    private HoldButton(Context c, String label, IconButton.Kind icon, String description, Callback cb) {
        super(c);
        this.label = label;
        this.glyph = icon == null ? null : new IconButton(c, icon, null, false, false, () -> {});
        this.cb = cb;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Ui.dp(15));
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setContentDescription(description);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        bg.setColor(down ? Ui.ACCENT : label != null ? Ui.KEY : Ui.SURFACE);
        c.drawRoundRect(box, h / 2f, h / 2f, bg);
        int color = down ? Ui.BG : Ui.TEXT;
        if (glyph != null) {
            glyph.glyph(c, w / 2f, h / 2f, Ui.dp(8.5f), down ? Ui.BG : Ui.MUTED);
        } else {
            text.setColor(color);
            c.drawText(label, w / 2f, h / 2f + Ui.dp(5.5f), text);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                set(true);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                set(false);
                return true;
            default:
                return true;
        }
    }

    private void set(boolean d) {
        if (down == d) return;
        down = d;
        invalidate();
        cb.onChange(d);
    }
}
