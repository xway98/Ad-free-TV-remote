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

/**
 * A button with an icon drawn in code and, optionally, a small label. It acts on release (and only if the finger
 * stayed put, so a swipe that starts on it never presses it), and can repeat while held. In "bare" mode it has no
 * background at all: just the icon, for the top bar. {@link #glyph} draws the icon alone for other views.
 */
final class IconButton extends View {
    enum Kind {
        POWER, BACK, HOME, MUTE, PLAY_PAUSE, REWIND, FORWARD, PLUS, MINUS, MENU, INPUT, KEYBOARD, QUICK, SETTINGS, APPS,
        MOUSE, BOLT, MORE, CHEVRON_DOWN, CLIPBOARD, ARROW_LEFT, ARROW_RIGHT, SEARCH
    }

    interface Press {
        void onPress();
    }

    private static final int HOLD_MS = 350;

    private final Kind kind;
    private final String label;
    private final Press press;
    private final boolean repeat;
    private final boolean accent;
    private final int slop;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path path = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float downX, downY;
    private boolean down, fired, bare;
    private int tint;

    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (!down) return;
            fired = true;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            press.onPress();
            handler.postDelayed(this, 110);
        }
    };

    IconButton(Context c, Kind kind, String label, boolean repeat, boolean accent, Press press) {
        super(c);
        this.kind = kind;
        this.label = label;
        this.repeat = repeat;
        this.accent = accent;
        this.press = press;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(Ui.dp(2.2f));
        solid.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Ui.dp(11));
        setContentDescription(label != null ? label : kind.name().toLowerCase().replace('_', ' '));
    }

    /** No background: just the icon in one colour (top bar). */
    IconButton bare(boolean on) {
        bare = on;
        return this;
    }

    IconButton tint(int color) {
        tint = color;
        return this;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        if (!bare) {
            bg.setColor(down ? Ui.KEY_DOWN : Ui.SURFACE);
            c.drawRoundRect(box, Ui.dp(16), Ui.dp(16), bg);
        } else if (down) {
            bg.setColor(Ui.SURFACE_HI);
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, bg);
        }
        int color = tint != 0 ? tint : accent ? Ui.ACCENT : Ui.TEXT;
        float cx = w / 2f;
        float cy = label != null ? h * 0.40f : h / 2f;
        float a = Math.min(w, h) * (label != null ? 0.17f : bare ? 0.24f : 0.2f);
        if (label != null) {
            text.setColor(accent ? Ui.ACCENT : Ui.MUTED);
            c.drawText(label, cx, h - Ui.dp(10), text);
        }
        glyph(c, cx, cy, a, color);
    }

    /** Draws this button's icon alone, so other views (the dock) can show the same shapes. */
    void glyph(Canvas c, float cx, float cy, float a, int color) {
        line.setColor(color);
        solid.setColor(color);
        switch (kind) {
            case POWER:
                box.set(cx - a, cy - a, cx + a, cy + a);
                c.drawArc(box, -60, 300, false, line);
                c.drawLine(cx, cy - a * 1.15f, cx, cy - a * 0.1f, line);
                break;
            case BACK:
                path.reset();
                path.moveTo(cx + a * 0.6f, cy - a);
                path.lineTo(cx - a * 0.6f, cy);
                path.lineTo(cx + a * 0.6f, cy + a);
                c.drawPath(path, line);
                break;
            case HOME:
                path.reset();
                path.moveTo(cx - a * 1.1f, cy - a * 0.05f);
                path.lineTo(cx, cy - a);
                path.lineTo(cx + a * 1.1f, cy - a * 0.05f);
                path.moveTo(cx - a * 0.7f, cy - a * 0.2f);
                path.lineTo(cx - a * 0.7f, cy + a);
                path.lineTo(cx + a * 0.7f, cy + a);
                path.lineTo(cx + a * 0.7f, cy - a * 0.2f);
                c.drawPath(path, line);
                break;
            case MUTE:
                path.reset();
                path.moveTo(cx - a * 1.1f, cy - a * 0.4f);
                path.lineTo(cx - a * 0.5f, cy - a * 0.4f);
                path.lineTo(cx + a * 0.1f, cy - a * 0.95f);
                path.lineTo(cx + a * 0.1f, cy + a * 0.95f);
                path.lineTo(cx - a * 0.5f, cy + a * 0.4f);
                path.lineTo(cx - a * 1.1f, cy + a * 0.4f);
                path.close();
                c.drawPath(path, line);
                c.drawLine(cx + a * 0.5f, cy - a * 0.5f, cx + a * 1.2f, cy + a * 0.5f, line);
                c.drawLine(cx + a * 1.2f, cy - a * 0.5f, cx + a * 0.5f, cy + a * 0.5f, line);
                break;
            case PLAY_PAUSE:
                triangle(c, cx - a * 0.9f, cy, a * 0.95f, true);
                c.drawRect(cx + a * 0.3f, cy - a * 0.8f, cx + a * 0.62f, cy + a * 0.8f, solid);
                c.drawRect(cx + a * 0.95f, cy - a * 0.8f, cx + a * 1.27f, cy + a * 0.8f, solid);
                break;
            case REWIND:
                triangle(c, cx - a * 0.1f, cy, a * 0.8f, false);
                triangle(c, cx + a * 0.9f, cy, a * 0.8f, false);
                break;
            case FORWARD:
                triangle(c, cx - a * 0.9f, cy, a * 0.8f, true);
                triangle(c, cx + a * 0.1f, cy, a * 0.8f, true);
                break;
            case PLUS:
                c.drawLine(cx - a, cy, cx + a, cy, line);
                c.drawLine(cx, cy - a, cx, cy + a, line);
                break;
            case MINUS:
                c.drawLine(cx - a, cy, cx + a, cy, line);
                break;
            case MENU:
                c.drawLine(cx - a, cy - a * 0.7f, cx + a, cy - a * 0.7f, line);
                c.drawLine(cx - a, cy, cx + a, cy, line);
                c.drawLine(cx - a, cy + a * 0.7f, cx + a, cy + a * 0.7f, line);
                break;
            case INPUT:
                path.reset();
                path.moveTo(cx - a * 0.2f, cy - a);
                path.lineTo(cx + a * 1.1f, cy - a);
                path.lineTo(cx + a * 1.1f, cy + a);
                path.lineTo(cx - a * 0.2f, cy + a);
                c.drawPath(path, line);
                c.drawLine(cx - a * 1.2f, cy, cx + a * 0.5f, cy, line);
                path.reset();
                path.moveTo(cx - a * 0.3f, cy - a * 0.55f);
                path.lineTo(cx + a * 0.5f, cy);
                path.lineTo(cx - a * 0.3f, cy + a * 0.55f);
                c.drawPath(path, line);
                break;
            case KEYBOARD:
                box.set(cx - a * 1.3f, cy - a * 0.8f, cx + a * 1.3f, cy + a * 0.8f);
                c.drawRoundRect(box, Ui.dp(4), Ui.dp(4), line);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * a * 0.7f, cy - a * 0.2f, Ui.dp(1.2f), solid);
                c.drawLine(cx - a * 0.7f, cy + a * 0.35f, cx + a * 0.7f, cy + a * 0.35f, line);
                break;
            case QUICK:
                // three sliders: the quick menu
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * a * 0.85f;
                    c.drawLine(cx - a * 1.1f, y, cx + a * 1.1f, y, line);
                    float k = cx + (i == 0 ? -0.5f : i < 0 ? 0.55f : 0.1f) * a;
                    bg.setColor(bare ? Ui.BG : down ? Ui.KEY_DOWN : Ui.SURFACE);
                    c.drawCircle(k, y, a * 0.32f, bg);
                    c.drawCircle(k, y, a * 0.32f, line);
                }
                break;
            case SETTINGS:
                c.drawCircle(cx, cy, a * 0.42f, line);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI / 4 * i;
                    float cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
                    c.drawLine(cx + cs * a * 0.8f, cy + sn * a * 0.8f, cx + cs * a * 1.2f, cy + sn * a * 1.2f, line);
                }
                c.drawCircle(cx, cy, a * 0.85f, line);
                break;
            case APPS:
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        float x0 = cx + (i == 0 ? -1.05f : 0.15f) * a, y0 = cy + (j == 0 ? -1.05f : 0.15f) * a;
                        box.set(x0, y0, x0 + a * 0.9f, y0 + a * 0.9f);
                        c.drawRoundRect(box, Ui.dp(2), Ui.dp(2), line);
                    }
                }
                break;
            case MOUSE:
                box.set(cx - a * 0.8f, cy - a * 1.15f, cx + a * 0.8f, cy + a * 1.15f);
                c.drawRoundRect(box, a * 0.8f, a * 0.8f, line);
                c.drawLine(cx, cy - a * 1.15f, cx, cy - a * 0.2f, line);
                break;
            case BOLT:
                path.reset();
                path.moveTo(cx + a * 0.25f, cy - a * 1.2f);
                path.lineTo(cx - a * 0.7f, cy + a * 0.1f);
                path.lineTo(cx - a * 0.05f, cy + a * 0.1f);
                path.lineTo(cx - a * 0.25f, cy + a * 1.2f);
                path.lineTo(cx + a * 0.7f, cy - a * 0.1f);
                path.lineTo(cx + a * 0.05f, cy - a * 0.1f);
                path.close();
                c.drawPath(path, line);
                break;
            case MORE:
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * a * 0.75f, cy, a * 0.2f, solid);
                break;
            case CHEVRON_DOWN:
                path.reset();
                path.moveTo(cx - a * 0.85f, cy - a * 0.35f);
                path.lineTo(cx, cy + a * 0.5f);
                path.lineTo(cx + a * 0.85f, cy - a * 0.35f);
                c.drawPath(path, line);
                break;
            case CLIPBOARD:
                box.set(cx - a * 0.85f, cy - a * 0.9f, cx + a * 0.85f, cy + a * 1.1f);
                c.drawRoundRect(box, Ui.dp(3), Ui.dp(3), line);
                box.set(cx - a * 0.4f, cy - a * 1.25f, cx + a * 0.4f, cy - a * 0.65f);
                c.drawRoundRect(box, Ui.dp(2), Ui.dp(2), line);
                c.drawLine(cx - a * 0.45f, cy + a * 0.1f, cx + a * 0.45f, cy + a * 0.1f, line);
                c.drawLine(cx - a * 0.45f, cy + a * 0.55f, cx + a * 0.2f, cy + a * 0.55f, line);
                break;
            case ARROW_LEFT:
                c.drawLine(cx + a * 0.9f, cy, cx - a * 0.9f, cy, line);
                path.reset();
                path.moveTo(cx - a * 0.1f, cy - a * 0.8f);
                path.lineTo(cx - a * 0.9f, cy);
                path.lineTo(cx - a * 0.1f, cy + a * 0.8f);
                c.drawPath(path, line);
                break;
            case SEARCH:
                c.drawCircle(cx - a * 0.2f, cy - a * 0.2f, a * 0.75f, line);
                c.drawLine(cx + a * 0.35f, cy + a * 0.35f, cx + a * 1.0f, cy + a * 1.0f, line);
                break;
            case ARROW_RIGHT:
                c.drawLine(cx - a * 0.9f, cy, cx + a * 0.9f, cy, line);
                path.reset();
                path.moveTo(cx + a * 0.1f, cy - a * 0.8f);
                path.lineTo(cx + a * 0.9f, cy);
                path.lineTo(cx + a * 0.1f, cy + a * 0.8f);
                c.drawPath(path, line);
                break;
        }
    }

    private void triangle(Canvas c, float cx, float cy, float s, boolean pointRight) {
        path.reset();
        if (pointRight) {
            path.moveTo(cx - s * 0.5f, cy - s);
            path.lineTo(cx + s * 0.6f, cy);
            path.lineTo(cx - s * 0.5f, cy + s);
        } else {
            path.moveTo(cx + s * 0.5f, cy - s);
            path.lineTo(cx - s * 0.6f, cy);
            path.lineTo(cx + s * 0.5f, cy + s);
        }
        path.close();
        c.drawPath(path, solid);
    }

    /** Fires on release, and only if the finger stayed put: a swipe that starts on a button never presses it. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                fired = false;
                downX = e.getX();
                downY = e.getY();
                invalidate();
                if (repeat) handler.postDelayed(repeater, HOLD_MS);
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
        invalidate();
    }
}
