package app.pocketremote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** A scroll wheel strip: drag the thumb and the page scrolls with it; let go and it springs back to the middle. */
final class ScrollStrip extends View {
    interface Callback {
        void scroll(int vertical);
    }

    private final Callback cb;
    private final float density = getResources().getDisplayMetrics().density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float thumb = 0.5f;          // 0 top .. 1 bottom
    private float lastY, rem;
    private boolean down;
    private ValueAnimator back;

    ScrollStrip(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        setContentDescription("Scroll");
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        fill.setColor(Ui.SURFACE_LO);
        c.drawRoundRect(box, w / 2f, w / 2f, fill);
        float th = Ui.dp(64), pad = Ui.dp(5);
        float top = pad + (h - 2 * pad - th) * thumb;
        box.set(pad, top, w - pad, top + th);
        fill.setColor(down ? 0xFF6A6A80 : Ui.FAINT);
        c.drawRoundRect(box, (w - 2 * pad) / 2f, (w - 2 * pad) / 2f, fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (back != null) back.cancel();
                down = true;
                lastY = e.getY();
                rem = 0;
                follow(e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = (e.getY() - lastY) / density;
                lastY = e.getY();
                // dragging down scrolls down (the wheel turns negative)
                float f = -dy * 11f + rem;
                int i = (int) f;
                rem = f - i;
                if (i != 0) cb.scroll(i);
                follow(e.getY());
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false;
                back = ValueAnimator.ofFloat(thumb, 0.5f);
                back.setDuration(180);
                back.addUpdateListener(a -> {
                    thumb = (float) a.getAnimatedValue();
                    invalidate();
                });
                back.start();
                return true;
            default:
                return true;
        }
    }

    private void follow(float y) {
        float h = getHeight(), pad = Ui.dp(5), th = Ui.dp(64);
        thumb = Math.max(0f, Math.min(1f, (y - pad - th / 2f) / (h - 2 * pad - th)));
    }
}
