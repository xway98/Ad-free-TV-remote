package app.pocketremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Page dots: the active one stretches into a pill and follows the pager while it is dragged. */
final class Dots extends View {
    private final int count;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float position;

    Dots(Context c, int count) {
        super(c);
        this.count = count;
    }

    void setPosition(float p) {
        position = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = Ui.dp(3), gap = Ui.dp(10), pill = Ui.dp(10);
        float total = count * 2 * r + (count - 1) * gap + pill - 2 * r;
        float x = (getWidth() - total) / 2f, cy = getHeight() / 2f;
        for (int i = 0; i < count; i++) {
            float near = Math.max(0f, 1f - Math.abs(position - i));
            float w = 2 * r + (pill - 2 * r) * near;
            paint.setColor(blend(Ui.SURFACE_HI, Ui.ACCENT, near));
            c.drawRoundRect(x, cy - r, x + w, cy + r, r, r, paint);
            x += w + gap;
        }
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
