package app.pocketremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import app.pocketremote.core.PcSession;

/**
 * The laptop's trackpad. One finger moves the pointer (faster swipes go further), a tap clicks, and a tap followed
 * at once by a touch that moves drags. Two fingers scroll (tap = right click), three fingers tap = middle click.
 * Movement is sent as whole pixels with the fraction carried over, so slow, precise moves are not rounded away.
 */
final class MousePadView extends View {
    interface Callback {
        void move(int dx, int dy);

        void click(int button);

        void button(int button, boolean down);

        void scroll(int vertical, int horizontal);
    }

    private static final long TAP_MS = 260, DOUBLE_TAP_MS = 300;

    private final Callback cb;
    private final float density = getResources().getDisplayMetrics().density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final android.graphics.Path cursor = new android.graphics.Path();
    private final float deadZone = Ui.dp(3.5f);

    private boolean hint = true;                 // the how-to text, shown until you have used the pad a few times
    private float speed = 2.4f;          // pointer pixels per dp of finger travel, before acceleration
    private boolean natural = true;      // two-finger scroll: content follows the fingers

    private float downX, downY, lastX, lastY, remX, remY, speedDp, touchX, touchY, midX, midY, scrollX, scrollY;
    private long downTime, lastTime, lastTapUp;
    private float lastTapX, lastTapY;
    private int fingers, maxFingers;
    private boolean moved, dragging, dragCandidate, touching;

    MousePadView(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(1));
        line.setColor(Ui.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Ui.dp(12));
        text.setColor(Ui.MUTED);
        setContentDescription("Laptop trackpad");
    }

    void setHint(boolean on) {
        hint = on;
        invalidate();
    }

    void setSpeed(float pixelsPerDp) {
        speed = pixelsPerDp;
    }

    void setNaturalScroll(boolean on) {
        natural = on;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        fill.setColor(Ui.SURFACE_LO);
        c.drawRoundRect(box, Ui.dp(24), Ui.dp(24), fill);
        if (touching) {
            fill.setColor(dragging ? 0x55FF35D2 : 0x18FFFFFF);
            c.drawCircle(touchX, touchY, Ui.dp(36), fill);
            fill.setColor(dragging ? 0xAAFF35D2 : 0x40FFFFFF);
            c.drawCircle(touchX, touchY, Ui.dp(9), fill);
        } else {
            // a faint pointer in the middle says "this is a trackpad" without any words
            float k = Ui.dp(2.2f), cx = w / 2f, cy = h / 2f;
            cursor.reset();
            cursor.moveTo(cx + (6 - 12) * k, cy + (3 - 12) * k);
            cursor.lineTo(cx + (6 - 12) * k, cy + (19 - 12) * k);
            cursor.lineTo(cx + (10 - 12) * k, cy + (15.5f - 12) * k);
            cursor.lineTo(cx + (12.6f - 12) * k, cy + (21 - 12) * k);
            cursor.lineTo(cx + (15.2f - 12) * k, cy + (19.8f - 12) * k);
            cursor.lineTo(cx + (12.6f - 12) * k, cy + (14.4f - 12) * k);
            cursor.lineTo(cx + (18 - 12) * k, cy + (14 - 12) * k);
            cursor.close();
            fill.setColor(Ui.STROKE);
            c.drawPath(cursor, fill);
            if (hint) {
                c.drawText("Tap = click  •  Tap, then drag = drag", w / 2f, h - Ui.dp(36), text);
                c.drawText("Two fingers: scroll, tap = right click", w / 2f, h - Ui.dp(18), text);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        touchX = e.getX();
        touchY = e.getY();
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downTime = lastTime = now;
                remX = remY = 0;
                speedDp = 0;
                fingers = maxFingers = 1;
                touching = true;
                moved = false;
                dragging = false;
                dragCandidate = now - lastTapUp < DOUBLE_TAP_MS && Math.hypot(e.getX() - lastTapX, e.getY() - lastTapY) < Ui.dp(60);
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                fingers = e.getPointerCount();
                maxFingers = Math.max(maxFingers, fingers);
                if (dragging) {
                    cb.button(PcSession.LEFT, false);
                    dragging = false;
                }
                if (fingers >= 2) {
                    midX = mean(e, true);
                    midY = mean(e, false);
                    scrollX = scrollY = 0;
                    speedDp = 0;
                    lastTime = now;
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                fingers = e.getPointerCount() - 1;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (maxFingers == 1) pointer(e.getX(), e.getY(), now);
                else if (fingers >= 2) scroll(mean(e, true), mean(e, false), now);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                touching = false;
                if (dragging) {
                    cb.button(PcSession.LEFT, false);
                    dragging = false;
                } else if (!moved && now - downTime < TAP_MS) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (maxFingers == 1) {
                        cb.click(PcSession.LEFT);
                        lastTapUp = now;
                        lastTapX = e.getX();
                        lastTapY = e.getY();
                    } else if (maxFingers == 2) {
                        cb.click(PcSession.RIGHT);
                    } else {
                        cb.click(PcSession.MIDDLE);
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                if (dragging) {
                    cb.button(PcSession.LEFT, false);
                    dragging = false;
                }
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private static float mean(MotionEvent e, boolean x) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += x ? e.getX(i) : e.getY(i);
        return s / e.getPointerCount();
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3 - 2 * t);
    }

    /** Finger speed (dp per ms), smoothed, so that one noisy sample doesn't throw the pointer. */
    private float track(float dxDp, float dyDp, long now) {
        long dt = Math.max(1, now - lastTime);
        lastTime = now;
        speedDp = speedDp * 0.6f + (float) Math.hypot(dxDp, dyDp) / dt * 0.4f;
        return speedDp;
    }

    private void pointer(float x, float y, long now) {
        // a touch that doesn't move is a tap: the pointer stays put until the finger really travels
        if (!moved) {
            if (Math.hypot(x - downX, y - downY) < deadZone) return;
            moved = true;
            if (dragCandidate) {
                dragging = true;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                cb.button(PcSession.LEFT, true);
            }
        }
        float dx = (x - lastX) / density, dy = (y - lastY) / density;
        lastX = x;
        lastY = y;
        float v = track(dx, dy, now);
        float gain = speed * (1f + 2.0f * smooth((v - 0.12f) / 1.2f));
        float fx = dx * gain + remX, fy = dy * gain + remY;
        int ix = (int) fx, iy = (int) fy;
        remX = fx - ix;
        remY = fy - iy;
        if (ix != 0 || iy != 0) cb.move(ix, iy);
    }

    private void scroll(float x, float y, long now) {
        float dx = (x - midX) / density, dy = (y - midY) / density;
        midX = x;
        midY = y;
        if (!moved && Math.hypot(x - downX, y - downY) > deadZone * 2) moved = true;
        float v = track(dx, dy, now);
        float gain = 7f * (1f + 1.6f * smooth((v - 0.1f) / 1.4f));       // wheel units per dp; 120 is a notch
        float dir = natural ? 1f : -1f;
        float fy = dy * gain * dir + scrollY, fx = dx * gain * dir + scrollX;
        // mostly sideways or mostly up/down, not both at once
        if (Math.abs(fx) > Math.abs(fy) * 2.2f) fy = scrollY = 0;
        else if (Math.abs(fy) > Math.abs(fx) * 2.2f) fx = scrollX = 0;
        int iy = (int) fy, ix = (int) fx;
        scrollY = fy - iy;
        scrollX = fx - ix;
        if (ix != 0 || iy != 0) cb.scroll(iy, ix);
    }
}
