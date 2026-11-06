package app.lantern;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The row of tiles. One number, {@code focus}, drives everything: the focused tile is large, its
 * neighbours shrink to normal as focus moves away, and the row slides so the focused tile's left
 * edge always sits at the same place. Sizes follow the 1920 x 1080 mockup.
 *
 * Only tiles near the screen exist as views; they are reused as the row slides, so a library of
 * thousands costs the same as a library of ten. Positions are worked out with arithmetic, not by
 * walking the list.
 */
final class Shelf extends ViewGroup {
    interface Listener {
        void onFocus(int index);

        void onTap(int index);
    }

    // Game tiles and system tiles, in mockup pixels.
    private static final float GW = 250, GH = 340, GBW = 400, GBH = 540;
    private static final float SW = 300, SH = 300, SBW = 460, SBH = 460;
    private static final float GAP = 28, LEFT = 90, BOTTOM = 690;

    private final float s;
    private final SparseArray<Cover> attached = new SparseArray<>();
    private final ArrayList<Cover> pool = new ArrayList<>();
    private List<Game> items = Collections.emptyList();
    private boolean systems;
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

    int size() {
        return items.size();
    }

    Game itemAt(int i) {
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    /** Shows a new list from its first item. systemTiles picks the larger, squarer tile size. */
    void setItems(List<Game> list, boolean systemTiles) {
        if (anim != null) anim.cancel();
        for (int k = 0; k < attached.size(); k++) pool.add(attached.valueAt(k));
        attached.clear();
        removeAllViews();
        items = list;
        systems = systemTiles;
        index = 0;
        focus = 0;
        requestLayout();
        if (listener != null && !items.isEmpty()) listener.onFocus(0);
    }

    void setIndex(int i, boolean animate) {
        int n = items.size();
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

    /** Redraws the tiles that exist, after something about a game (a star, a cover) changed. */
    void redraw() {
        for (int k = 0; k < attached.size(); k++) attached.valueAt(k).invalidate();
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
        int n = items.size();
        if (n == 0) {
            for (int k = 0; k < attached.size(); k++) pool.add(attached.valueAt(k));
            attached.clear();
            removeAllViewsInLayout();
            return;
        }
        float bw = systems ? SW : GW, bh = systems ? SH : GH, bbw = systems ? SBW : GBW, bbh = systems ? SBH : GBH;
        float pitch = (bw + GAP) * s, extra = (bbw - bw) * s;

        // Left edge of tile i: every tile before it is one pitch wide, and the two tiles that are
        // growing or shrinking (lo and lo + 1) add their extra width to everything after them.
        int lo = Math.max(0, Math.min(n - 1, (int) Math.floor(focus)));
        int hi = Math.min(lo + 1, n - 1);
        float wLo = weight(lo), wHi = hi == lo ? 0f : weight(hi);
        float xLo = left(lo, pitch, extra, lo, hi, wLo, wHi), xHi = left(hi, pitch, extra, lo, hi, wLo, wHi);
        float shift = LEFT * s - (xLo + (xHi - xLo) * (focus - lo));
        float bottom = BOTTOM * s;

        // Which tiles can be seen: those whose left edge falls inside the screen, with some margin.
        float margin = 2 * pitch + extra;
        int first = Math.max(0, (int) Math.floor((-shift - margin) / pitch));
        int last = Math.min(n - 1, (int) Math.ceil((getWidth() - shift + margin) / pitch));

        for (int k = attached.size() - 1; k >= 0; k--) {
            int idx = attached.keyAt(k);
            if (idx < first || idx > last) {
                Cover c = attached.valueAt(k);
                removeViewInLayout(c);
                attached.removeAt(k);
                pool.add(c);
            }
        }
        for (int i = first; i <= last; i++) {
            Cover c = attached.get(i);
            if (c == null) {
                c = pool.isEmpty() ? newCover() : pool.remove(pool.size() - 1);
                attached.put(i, c);
                addViewInLayout(c, -1, new ViewGroup.LayoutParams(-2, -2), true);
            }
            Game g = items.get(i);
            if (c.game() != g) c.setGame(g);
            float wt = weight(i);
            int w = Math.round((bw + (bbw - bw) * wt) * s), h = Math.round((bh + (bbh - bh) * wt) * s);
            int x = Math.round(left(i, pitch, extra, lo, hi, wLo, wHi) + shift);
            c.setWeight(wt);
            c.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            c.layout(x, Math.round(bottom - h), x + w, Math.round(bottom));
        }
    }

    private static float left(int i, float pitch, float extra, int lo, int hi, float wLo, float wHi) {
        float x = i * pitch;
        if (i > lo) x += extra * wLo;
        if (i > hi) x += extra * wHi;
        return x;
    }

    private Cover newCover() {
        Cover c = new Cover(getContext(), s);
        c.setOnClickListener(v -> {
            for (int k = 0; k < attached.size(); k++) {
                if (attached.valueAt(k) == v && listener != null) {
                    listener.onTap(attached.keyAt(k));
                    return;
                }
            }
        });
        return c;
    }
}
