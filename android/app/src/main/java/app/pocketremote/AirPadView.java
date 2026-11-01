package app.pocketremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import app.pocketremote.core.AirMath;
import app.pocketremote.core.PcSession;

/**
 * An air mouse: while a thumb is held on the pad, turning the wrist moves the pointer (like a mouse lifted off the
 * desk, letting go stops it). Rotation speed from the gyroscope is used, not an absolute angle, so a small wrist
 * turn crosses the whole screen and there is nothing to recentre. A small dead zone and speed-aware smoothing keep
 * the pointer still when the hand is, and a gentle curve makes slow moves precise and fast ones quick.
 */
final class AirPadView extends View implements SensorEventListener {
    interface Callback {
        void move(int dx, int dy);

        void click(int button);

        void button(int button, boolean down);
    }

    private static final float DEAD_ZONE = 0.025f;      // rad/s of hand tremor that is ignored
    private static final long SETTLE_NS = 70_000_000L;   // pressing the glass nudges the phone: ignore the first 70 ms
    private static final long TAP_MS = 220, DOUBLE_TAP_MS = 320;
    private static final int LEFT = PcSession.LEFT, RIGHT = PcSession.RIGHT, MIDDLE = PcSession.MIDDLE;

    private final Callback cb;
    private final SensorManager sm;
    private final Sensor gyro, gravity, accel;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private final float[] up = {0, 0, 1};
    private final float[] rate = new float[3];
    private final float[] proj = new float[2];
    private float sens = 4500f;                          // pointer pixels per radian of wrist turn
    private float sx, sy, remX, remY;
    private long lastTs;
    private HandlerThread thread;
    private boolean listening;
    private volatile boolean engaged;
    private volatile long engagedAt;                     // SystemClock.elapsedRealtimeNanos() of the touch
    private volatile float movedCounts;                  // pointer travel during this touch, to tell a tap from a nudge
    private long downTime, lastTapUp;
    private int maxFingers;
    private boolean dragging;
    private boolean outside, outsideFired, outsideCancelled;     // a touch that started outside the circle
    private float outsideX, outsideY;
    private final int slop;
    private boolean hint = true;                 // the how-to text, shown until you have used the pad a few times

    AirPadView(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(2));
        text.setTextAlign(Paint.Align.CENTER);
        setKeepScreenOn(true);
        setContentDescription("Air mouse: hold and turn your wrist");
    }

    void setHint(boolean on) {
        hint = on;
        invalidate();
    }

    boolean available() {
        return gyro != null;
    }

    void setSensitivity(float pixelsPerRadian) {
        sens = pixelsPerRadian;
    }

    boolean isEngaged() {
        return engaged;
    }

    // ---- the sensors run only while the pad is on screen ----

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isShown()) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && isAttachedToWindow()) start();
        else stop();
    }

    private synchronized void start() {
        if (listening || gyro == null) return;
        listening = true;
        thread = new HandlerThread("air-mouse");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        lastTs = 0;
        sx = sy = remX = remY = 0;
        // 200 Hz is the most Android lets an app have without a special permission
        sm.registerListener(this, gyro, 5000, h);
        if (gravity != null) sm.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME, h);
        else if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, h);
    }

    private synchronized void stop() {
        engaged = false;
        if (!listening) return;
        listening = false;
        sm.unregisterListener(this);
        if (thread != null) thread.quitSafely();
        thread = null;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                System.arraycopy(e.values, 0, up, 0, 3);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                for (int i = 0; i < 3; i++) up[i] = up[i] * 0.92f + e.values[i] * 0.08f;
                break;
            case Sensor.TYPE_GYROSCOPE:
                gyro(e);
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private static float dead(float v) {
        float a = Math.abs(v);
        return a < DEAD_ZONE ? 0f : Math.signum(v) * (a - DEAD_ZONE);
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3 - 2 * t);
    }

    private void gyro(SensorEvent e) {
        float dt = lastTs == 0 ? 0f : (e.timestamp - lastTs) / 1e9f;
        lastTs = e.timestamp;
        if (!engaged) {
            sx = sy = remX = remY = 0;
            return;
        }
        if (dt <= 0f || dt > 0.05f) return;
        if (e.timestamp - engagedAt < SETTLE_NS) return;

        System.arraycopy(e.values, 0, rate, 0, 3);
        AirMath.project(up, rate, proj);
        float turn = dead(proj[0]), point = dead(proj[1]);

        // slow, small movements are smoothed hard; fast ones follow the hand closely
        float mag = (float) Math.hypot(turn, point);
        float alpha = 0.3f + 0.7f * smooth(mag / 0.9f);
        sx += (turn - sx) * alpha;
        sy += (point - sy) * alpha;

        // slow = precise (about half speed), fast = quick (about one and a half times)
        float curve = 0.55f + 0.95f * smooth((float) Math.hypot(sx, sy) / 1.2f);
        float fx = -sx * dt * sens * curve + remX;       // turning left moves the pointer left,
        float fy = -sy * dt * sens * curve + remY;       // pointing up moves it up
        int ix = (int) fx, iy = (int) fy;
        remX = fx - ix;
        remY = fy - iy;
        if (ix != 0 || iy != 0) {
            movedCounts += Math.abs(ix) + Math.abs(iy);
            cb.move(ix, iy);
        }
    }

    // ---- the pad ----

    private float zoneRadius() {
        return Math.min(getWidth(), getHeight()) * 0.3f;
    }

    private boolean inCircle(float x, float y) {
        return Math.hypot(x - getWidth() / 2f, y - getHeight() / 2f) <= zoneRadius() + Ui.dp(10);
    }

    private final Runnable outsideHold = new Runnable() {
        @Override public void run() {
            if (!outside || outsideFired || outsideCancelled) return;
            outsideFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            cb.click(RIGHT);
        }
    };

    private void endOutside() {
        removeCallbacks(outsideHold);
        outside = false;
        invalidate();
    }

    /**
     * The circle is the clutch: thumb held on it = the pointer follows the wrist. Everything outside it is a button, so
     * the thumb never has to travel: tap anywhere outside to left-click, hold there to right-click. In the circle, a
     * quick tap also clicks, and a tap followed at once by a hold drags (the left button stays down while you aim).
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (gyro == null) return true;
                if (!inCircle(e.getX(), e.getY())) {
                    outside = true;
                    outsideFired = outsideCancelled = false;
                    outsideX = e.getX();
                    outsideY = e.getY();
                    postDelayed(outsideHold, 450);
                    invalidate();
                    return true;
                }
                outside = false;
                downTime = now;
                maxFingers = 1;
                movedCounts = 0;
                engagedAt = SystemClock.elapsedRealtimeNanos();
                engaged = true;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                if (now - lastTapUp < DOUBLE_TAP_MS) {         // tap, then hold: drag with the left button down
                    dragging = true;
                    cb.button(LEFT, true);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (outside) return true;
                maxFingers = Math.max(maxFingers, e.getPointerCount());
                if (dragging) {
                    dragging = false;
                    cb.button(LEFT, false);
                }
                engaged = false;                                  // two fingers is a click gesture, not movement
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (outside && !outsideFired && Math.hypot(e.getX() - outsideX, e.getY() - outsideY) > slop * 2) {
                    outsideCancelled = true;                      // a swipe, not a tap
                    removeCallbacks(outsideHold);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (outside) {
                    if (!outsideFired && !outsideCancelled) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        cb.click(LEFT);
                    }
                    endOutside();
                    return true;
                }
                engaged = false;
                if (dragging) {
                    dragging = false;
                    cb.button(LEFT, false);
                } else if (gyro != null && now - downTime < TAP_MS && movedCounts < 40) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (maxFingers == 1) {
                        cb.click(LEFT);
                        lastTapUp = now;
                    } else if (maxFingers == 2) {
                        cb.click(RIGHT);
                    } else {
                        cb.click(MIDDLE);
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (outside) {
                    endOutside();
                    return true;
                }
                engaged = false;
                if (dragging) {
                    dragging = false;
                    cb.button(LEFT, false);
                }
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = zoneRadius();
        box.set(0, 0, w, h);
        fill.setColor(Ui.SURFACE_LO);
        c.drawRoundRect(box, Ui.dp(24), Ui.dp(24), fill);
        if (outside) {                                             // the click zone lights up while it is pressed
            fill.setColor(outsideFired ? 0x30FF35D2 : 0x14FFFFFF);
            c.drawRoundRect(box, Ui.dp(24), Ui.dp(24), fill);
        }

        // the clutch circle: quiet until you hold it
        fill.setColor(engaged ? 0x33FF35D2 : Ui.SURFACE);
        c.drawCircle(w / 2f, h / 2f, r, fill);
        stroke.setColor(engaged ? Ui.ACCENT : Ui.STROKE);
        c.drawCircle(w / 2f, h / 2f, r, stroke);
        fill.setColor(engaged ? Ui.ACCENT : Ui.FAINT);
        c.drawCircle(w / 2f, h / 2f, Ui.dp(8), fill);

        text.setTextSize(Ui.dp(14));
        if (gyro == null) {
            text.setColor(Ui.TEXT);
            c.drawText("This phone has no gyroscope", w / 2f, h / 2f - r - Ui.dp(16), text);
        } else if (hint || engaged) {
            text.setColor(engaged ? Ui.TEXT : Ui.MUTED);
            c.drawText(engaged ? "Moving…" : "Hold the circle, then turn your wrist", w / 2f, h / 2f - r - Ui.dp(16), text);
        }
        if (hint && gyro != null) {
            text.setColor(Ui.MUTED);
            text.setTextSize(Ui.dp(12));
            c.drawText("Tap outside the circle = click  •  Hold outside = right click", w / 2f, h - Ui.dp(36), text);
            c.drawText("In the circle: tap, then hold = drag", w / 2f, h - Ui.dp(18), text);
        }
    }
}