package app.pocketremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * One icon in the dock. The icon you are on grows into an accent pill with its name; the others stay quiet icons, and
 * a toggle that is switched on (the keyboard) gets a soft circle. Like the other buttons it acts on release only.
 */
final class DockButton extends View {
    static final int IDLE = 0, ACTIVE = 1, TOGGLED = 2;

    private final IconButton glyph;
    private final String label;
    private final Runnable press;
    private final int slop;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private int state = IDLE;
    private boolean down;
    private float downX, downY;

    DockButton(Context c, IconButton.Kind kind, String label, Runnable press) {
        super(c);
        this.glyph = new IconButton(c, kind, null, false, false, () -> {});     // only used to draw the icon
        this.label = label;
        this.press = press;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        text.setTextSize(Ui.dp(13));
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setContentDescription(label);
    }

    void setState(int s) {
        if (state == s) return;
        state = s;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = state == ACTIVE ? Ui.dp(14) + Ui.dp(20) + Ui.dp(6) + (int) Math.ceil(text.measureText(label)) + Ui.dp(14) : Ui.dp(46);
        setMeasuredDimension(w, Ui.dp(36));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        if (state == ACTIVE) {
            bg.setColor(Ui.ACCENT);
            c.drawRoundRect(box, h / 2f, h / 2f, bg);
            glyph.glyph(c, Ui.dp(14) + Ui.dp(10), h / 2f, Ui.dp(8.5f), Ui.BG);
            text.setColor(Ui.BG);
            c.drawText(label, Ui.dp(14) + Ui.dp(20) + Ui.dp(6), h / 2f + Ui.dp(4.5f), text);
        } else {
            if (state == TOGGLED || down) {
                bg.setColor(Ui.SURFACE_HI);
                c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, bg);
            }
            glyph.glyph(c, w / 2f, h / 2f, Ui.dp(8.5f), state == TOGGLED ? Ui.TEXT : Ui.MUTED);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                downX = e.getX();
                downY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (down && (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)) {
                    down = false;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (down) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    press.run();
                }
                down = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                down = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
