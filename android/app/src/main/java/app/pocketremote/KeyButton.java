package app.pocketremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * A text key for the laptop screens. Like the TV buttons it acts on release and never fires from a swipe; it can
 * repeat while held (arrows, backspace) and can show a sticky state (a modifier that is armed or locked).
 */
final class KeyButton extends View {
    interface Press {
        void onPress();
    }

    static final int OFF = 0, ARMED = 1, LOCKED = 2;

    private final String label;
    private final boolean repeat;
    private final Press press;
    private final int slop;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float downX, downY;
    private boolean down, fired;
    private int state = OFF;
    private Runnable longPress;

    private final Runnable longRunnable = new Runnable() {
        @Override public void run() {
            if (!down || fired || longPress == null) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            Runnable r = longPress;
            release();
            r.run();
        }
    };

    /** An optional second action for a long press; the normal press is then skipped. */
    KeyButton onLongPress(Runnable r) {
        longPress = r;
        return this;
    }

    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (!down) return;
            fired = true;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            press.onPress();
            handler.postDelayed(this, 70);
        }
    };

    KeyButton(Context c, String label, boolean repeat, Press press) {
        super(c);
        this.label = label;
        this.repeat = repeat;
        this.press = press;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Ui.dp(label.length() > 5 ? 12 : 14));
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setContentDescription(label);
    }

    void setState(int s) {
        state = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        box.set(0, 0, getWidth(), getHeight());
        bg.setColor(state == LOCKED ? Ui.ACCENT : down ? Ui.KEY_DOWN : Ui.KEY);
        c.drawRoundRect(box, Ui.dp(14), Ui.dp(14), bg);
        if (state == ARMED) {
            bg.setStyle(Paint.Style.STROKE);
            bg.setStrokeWidth(Ui.dp(2));
            bg.setColor(Ui.ACCENT);
            box.inset(Ui.dp(1), Ui.dp(1));
            c.drawRoundRect(box, Ui.dp(15), Ui.dp(15), bg);
            bg.setStyle(Paint.Style.FILL);
        }
        text.setColor(state == LOCKED ? Ui.BG : state == ARMED ? Ui.ACCENT : Ui.TEXT);
        c.drawText(label, getWidth() / 2f, getHeight() / 2f + Ui.dp(5), text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                fired = false;
                downX = e.getX();
                downY = e.getY();
                invalidate();
                if (repeat) handler.postDelayed(repeater, 380);
                if (longPress != null) handler.postDelayed(longRunnable, 550);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (down && (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)) release();
                return true;
            case MotionEvent.ACTION_UP:
                if (down && !fired) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    press.onPress();
                }
                release();
                return true;
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
            default:
                return true;
        }
    }

    private void release() {
        down = false;
        handler.removeCallbacks(repeater);
        handler.removeCallbacks(longRunnable);
        invalidate();
    }
}
