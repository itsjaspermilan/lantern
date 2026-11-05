package app.lantern;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.List;

/**
 * The row of covers. One number, {@code focus}, drives everything: the focused cover is large,
 * its neighbours shrink to normal as focus moves away, and the row slides so the focused cover's
 * left edge always sits at the same place. Sizes follow the 1920 x 1080 mockup.
 */
final class Shelf extends ViewGroup {
    interface Listener {
        void onFocus(int index);
    }

    private static final float W = 250, H = 340, BIG_W = 400, BIG_H = 540, GAP = 28, LEFT = 140, BOTTOM = 690;

    private final float s;
    private float focus;
    private int index;
    private ValueAnimator anim;
    private Listener listener;

    Shelf(Context c, float s) {
        super(c);
        this.s = s;
        setClipChildren(false);
    }

    void setListener(Listener l) {
        listener = l;
    }

    int getIndex() {
        return index;
    }

    void setCovers(List<Cover> covers) {
        if (anim != null) anim.cancel();
        removeAllViews();
        for (Cover v : covers) addView(v);
        index = 0;
        focus = 0;
        requestLayout();
        if (listener != null && !covers.isEmpty()) listener.onFocus(0);
    }

    void setIndex(int i, boolean animate) {
        int n = getChildCount();
        if (n == 0) return;
        i = Math.max(0, Math.min(n - 1, i));
        boolean changed = i != index;
        index = i;
        if (anim != null) anim.cancel();
        if (animate) {
            anim = ValueAnimator.ofFloat(focus, i);
            anim.setDuration(380);
            anim.setInterpolator(new DecelerateInterpolator(2f));
            anim.addUpdateListener(v -> {
                focus = (Float) v.getAnimatedValue();
                requestLayout();
            });
            anim.start();
        } else {
            focus = i;
            requestLayout();
        }
        if (changed && listener != null) listener.onFocus(i);
    }

    void move(int d) {
        setIndex(index + d, true);
    }

    private float weight(int i) {
        return Math.max(0f, 1f - Math.abs(i - focus));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(View.MeasureSpec.getSize(wSpec), View.MeasureSpec.getSize(hSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = getChildCount();
        if (n == 0) return;
        float[] x = new float[n + 1];
        for (int i = 0; i < n; i++) x[i + 1] = x[i] + (W + (BIG_W - W) * weight(i) + GAP) * s;
        int lo = Math.max(0, Math.min(n - 1, (int) Math.floor(focus)));
        int hi = Math.min(lo + 1, n - 1);
        float shift = LEFT * s - (x[lo] + (x[hi] - x[lo]) * (focus - lo));
        float bottom = BOTTOM * s;
        for (int i = 0; i < n; i++) {
            Cover c = (Cover) getChildAt(i);
            float wt = weight(i);
            int w = Math.round((W + (BIG_W - W) * wt) * s);
            int h = Math.round((H + (BIG_H - H) * wt) * s);
            int left = Math.round(x[i] + shift);
            c.setWeight(wt);
            c.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            c.layout(left, Math.round(bottom - h), left + w, Math.round(bottom));
        }
    }
}
