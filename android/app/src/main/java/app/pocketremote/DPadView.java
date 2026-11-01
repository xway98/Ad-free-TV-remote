package app.pocketremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import app.pocketremote.core.Keys;

/** A round D-pad: four direction zones around a centre OK. Holding a direction repeats it. */
final class DPadView extends View {
    interface Callback {
        void key(int code);
    }

    private static final int NONE = 0;

    private final Callback callback;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path arrow = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int slop;
    private float downX, downY;
    private boolean fired;
    private int pressed = NONE;

    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (pressed == NONE || pressed == Keys.CENTER) return;
            fired = true;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            callback.key(pressed);
            handler.postDelayed(this, 120);
        }
    };

    DPadView(Context c, Callback callback) {
        super(c);
        this.callback = callback;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(2.6f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Ui.TEXT);
        text.setTextSize(Ui.dp(20));
        text.setFakeBoldText(true);
        setContentDescription("Direction pad");
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f - Ui.dp(2);
        float inner = r * 0.38f;

        fill.setColor(Ui.SURFACE);
        c.drawCircle(cx, cy, r, fill);

        if (pressed != NONE && pressed != Keys.CENTER) {
            float start = pressed == Keys.UP ? -135 : pressed == Keys.RIGHT ? -45 : pressed == Keys.DOWN ? 45 : 135;
            oval.set(cx - r, cy - r, cx + r, cy + r);
            fill.setColor(Ui.SURFACE_HI);
            c.drawArc(oval, start, 90, true, fill);
        }

        fill.setColor(pressed == Keys.CENTER ? Ui.ACCENT : Ui.BG);
        c.drawCircle(cx, cy, inner, fill);
        stroke.setColor(Ui.SURFACE_HI);
        c.drawCircle(cx, cy, inner, stroke);
        c.drawText("OK", cx, cy + Ui.dp(7), text);

        float d = (r + inner) / 2f, s = r * 0.1f;
        stroke.setColor(Ui.TEXT);
        chevron(c, cx, cy - d, s, 0);
        chevron(c, cx + d, cy, s, 1);
        chevron(c, cx, cy + d, s, 2);
        chevron(c, cx - d, cy, s, 3);
    }

    private void chevron(Canvas c, float x, float y, float s, int dir) {
        arrow.reset();
        switch (dir) {
            case 0: arrow.moveTo(x - s, y + s * 0.5f); arrow.lineTo(x, y - s * 0.5f); arrow.lineTo(x + s, y + s * 0.5f); break;
            case 1: arrow.moveTo(x - s * 0.5f, y - s); arrow.lineTo(x + s * 0.5f, y); arrow.lineTo(x - s * 0.5f, y + s); break;
            case 2: arrow.moveTo(x - s, y - s * 0.5f); arrow.lineTo(x, y + s * 0.5f); arrow.lineTo(x + s, y - s * 0.5f); break;
            default: arrow.moveTo(x + s * 0.5f, y - s); arrow.lineTo(x - s * 0.5f, y); arrow.lineTo(x + s * 0.5f, y + s); break;
        }
        c.drawPath(arrow, stroke);
    }

    private int zone(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(getWidth(), getHeight()) / 2f;
        float dx = x - cx, dy = y - cy, dist = (float) Math.hypot(dx, dy);
        if (dist > r) return NONE;
        if (dist < r * 0.38f) return Keys.CENTER;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? Keys.RIGHT : Keys.LEFT;
        return dy > 0 ? Keys.DOWN : Keys.UP;
    }

    /** Fires on release (or while held), and not at all when the finger drifts off its zone, e.g. a page swipe. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int z = zone(e.getX(), e.getY());
                if (z == NONE) return false;
                pressed = z;
                fired = false;
                downX = e.getX();
                downY = e.getY();
                invalidate();
                handler.removeCallbacks(repeater);
                if (z != Keys.CENTER) handler.postDelayed(repeater, 380);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (pressed != NONE && (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)
                        && zone(e.getX(), e.getY()) != pressed) {
                    release();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (pressed != NONE && !fired) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    callback.key(pressed);
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
        pressed = NONE;
        handler.removeCallbacks(repeater);
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int size = Math.min(Math.min(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec)), Ui.dp(320));
        setMeasuredDimension(size, size);
    }
}
