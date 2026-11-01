package app.pocketremote;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** Full-width pages you swipe between. A swipe that passes 15% of the width moves on, otherwise it snaps back. */
final class Pager extends HorizontalScrollView {
    interface Listener {
        /** Position in pages: 0 is the first page, 1 the second, in between while dragging. */
        void onScroll(float position);
    }

    private final LinearLayout strip;
    private Listener listener;
    private int page, startPage;

    Pager(Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(true);
        strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        addView(strip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void addPage(View v) {
        strip.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void setListener(Listener l) {
        listener = l;
    }

    int getPage() {
        return page;
    }

    void setPage(int p, boolean animate) {
        page = Math.max(0, Math.min(strip.getChildCount() - 1, p));
        if (animate) smoothScrollTo(page * getWidth(), 0);
        else scrollTo(page * getWidth(), 0);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        for (int i = 0; i < strip.getChildCount(); i++) strip.getChildAt(i).getLayoutParams().width = w;
        super.onMeasure(wSpec, hSpec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        scrollTo(page * w, 0);
    }

    @Override
    protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        if (listener != null && getWidth() > 0) listener.onScroll(l / (float) getWidth());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) startPage = page;
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean r = super.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) settle();
        return r;
    }

    private void settle() {
        int w = getWidth();
        if (w == 0) return;
        float dx = getScrollX() - startPage * w;
        int target = startPage;
        if (dx > w * 0.15f) target = startPage + 1;
        else if (dx < -w * 0.15f) target = startPage - 1;
        setPage(target, true);
    }
}
