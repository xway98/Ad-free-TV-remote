package app.pocketremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import app.pocketremote.core.Keys;

/**
 * A touchpad for the TV. One finger: swipe to move (one arrow per stretch of travel, and a fast swipe goes further),
 * tap for OK, hold for a long OK. Two fingers: tap for Back, swipe to scroll fast.
 * A swipe that starts at the left or right edge is left to the pager, so the page can still be turned from here.
 */
final class TouchpadView extends View {
    interface Callback {
        void key(int code);

        void hold(int code, boolean start);
    }

    private static final int H = 1, V = 2;

    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float step = Ui.dp(24), scrollStep = Ui.dp(13), edge = Ui.dp(22);
    private final int slop;

    private float lastX, lastY, downX, downY, accX, accY, touchX, touchY, midY, midX, scrollAccX, scrollAccY, speed;
    private long downTime, lastMove, flashTime;
    private int axis, flashKey;
    private boolean moved, twoFingers, twoMoved, touching, held;

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (!touching || moved || twoFingers || held) return;
            held = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            callback.hold(Keys.CENTER, true);
        }
    };

    TouchpadView(Context c, Callback callback) {
        super(c);
        this.callback = callback;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(2));
        stroke.setColor(Ui.SURFACE_HI);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Ui.dp(11.5f));
        text.setColor(Ui.MUTED);
        setContentDescription("Touchpad");
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        fill.setColor(Ui.SURFACE);
        c.drawRoundRect(box, Ui.dp(26), Ui.dp(26), fill);
        c.drawRoundRect(box, Ui.dp(26), Ui.dp(26), stroke);

        long age = SystemClock.uptimeMillis() - flashTime;
        if (flashKey != 0 && age < 260) {
            int alpha = (int) (0x66 * (1f - age / 260f));
            fill.setColor((alpha << 24) | 0xFF35D2);
            float t = Ui.dp(5);
            switch (flashKey) {
                case Keys.LEFT: box.set(0, h * 0.25f, t, h * 0.75f); break;
                case Keys.RIGHT: box.set(w - t, h * 0.25f, w, h * 0.75f); break;
                case Keys.UP: box.set(w * 0.25f, 0, w * 0.75f, t); break;
                default: box.set(w * 0.25f, h - t, w * 0.75f, h); break;
            }
            c.drawRoundRect(box, t, t, fill);
            postInvalidateOnAnimation();
        }

        if (touching) {
            fill.setColor(held ? 0x66FF35D2 : 0x22FF35D2);
            c.drawCircle(touchX, touchY, Ui.dp(40), fill);
            fill.setColor(0x66FF35D2);
            c.drawCircle(touchX, touchY, Ui.dp(12), fill);
        }
        c.drawText("Swipe to move  •  Tap = OK  •  Hold = long OK", w / 2f, h - Ui.dp(34), text);
        c.drawText("Two fingers: tap = Back, swipe = scroll fast", w / 2f, h - Ui.dp(18), text);
    }

    private void emit(int key) {
        callback.key(key);
        flashKey = key;
        flashTime = SystemClock.uptimeMillis();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        touchX = e.getX();
        touchY = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getX() < edge || e.getX() > getWidth() - edge) return false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                lastX = downX = e.getX();
                lastY = downY = e.getY();
                accX = accY = 0;
                axis = 0;
                speed = 0;
                moved = twoFingers = twoMoved = held = false;
                touching = true;
                downTime = lastMove = SystemClock.uptimeMillis();
                handler.removeCallbacks(longPress);
                handler.postDelayed(longPress, 450);
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!twoFingers) {
                    twoFingers = true;
                    handler.removeCallbacks(longPress);
                    if (held) {
                        held = false;
                        callback.hold(Keys.CENTER, false);
                    }
                    midX = (e.getX(0) + e.getX(1)) / 2f;
                    midY = (e.getY(0) + e.getY(1)) / 2f;
                    scrollAccX = scrollAccY = 0;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (twoFingers) {
                    if (e.getPointerCount() >= 2) scroll((e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f);
                    return true;
                }
                move(e.getX(), e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                touching = false;
                if (held) {
                    held = false;
                    callback.hold(Keys.CENTER, false);
                } else if (twoFingers) {
                    if (!twoMoved && SystemClock.uptimeMillis() - downTime < 400) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        callback.key(Keys.BACK);
                    }
                } else if (!moved && SystemClock.uptimeMillis() - downTime < 300) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    callback.key(Keys.CENTER);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                touching = false;
                if (held) {
                    held = false;
                    callback.hold(Keys.CENTER, false);
                }
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /** One finger: travel is turned into arrow presses, scaled up when the finger is fast. */
    private void move(float x, float y) {
        long now = SystemClock.uptimeMillis();
        float dx = x - lastX, dy = y - lastY;
        long dt = Math.max(1, now - lastMove);
        lastX = x;
        lastY = y;
        if (now - lastMove > 220) axis = 0;      // a pause lets go of the direction lock
        lastMove = now;

        if (!moved && (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop)) {
            moved = true;
            handler.removeCallbacks(longPress);
        }
        if (held) return;

        float inst = (float) Math.hypot(dx, dy) / Ui.dp(1) / dt;     // dp per ms
        speed = speed * 0.7f + inst * 0.3f;
        float gain = 1f + Math.min(1.6f, speed * 1.1f);
        accX += dx * gain;
        accY += dy * gain;

        while (true) {
            float ax = Math.abs(accX), ay = Math.abs(accY);
            if (axis == 0) {
                if (Math.max(ax, ay) < step) break;
                axis = ax >= ay ? H : V;
            }
            // leave the lock when the finger clearly turns the other way
            if (axis == H && ay > step * 1.8f && ay > ax * 1.4f) { axis = V; accX = 0; continue; }
            if (axis == V && ax > step * 1.8f && ax > ay * 1.4f) { axis = H; accY = 0; continue; }
            if (axis == H) {
                if (ax < step) break;
                emit(accX > 0 ? Keys.RIGHT : Keys.LEFT);
                accX -= Math.signum(accX) * step;
                accY *= 0.4f;
            } else {
                if (ay < step) break;
                emit(accY > 0 ? Keys.DOWN : Keys.UP);
                accY -= Math.signum(accY) * step;
                accX *= 0.4f;
            }
        }
    }

    /** Two fingers: the same arrows, but at a much finer step, for long lists. */
    private void scroll(float x, float y) {
        scrollAccX += x - midX;
        scrollAccY += y - midY;
        midX = x;
        midY = y;
        if (!twoMoved && Math.hypot(scrollAccX, scrollAccY) > slop) twoMoved = true;
        if (!twoMoved) return;
        if (Math.abs(scrollAccY) >= Math.abs(scrollAccX)) {
            while (Math.abs(scrollAccY) >= scrollStep) {
                emit(scrollAccY > 0 ? Keys.DOWN : Keys.UP);
                scrollAccY -= Math.signum(scrollAccY) * scrollStep;
            }
            scrollAccX = 0;
        } else {
            while (Math.abs(scrollAccX) >= scrollStep) {
                emit(scrollAccX > 0 ? Keys.RIGHT : Keys.LEFT);
                scrollAccX -= Math.signum(scrollAccX) * scrollStep;
            }
            scrollAccY = 0;
        }
    }
}
