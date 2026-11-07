package app.lantern;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The tiles on the home screen, in one of three layouts. A grid of game tiles and a grid of system tiles
 * scroll vertically; the focused tile is a little larger and its row is kept in view. The hero layout
 * (Recent) shows the first game very large with a short row of the next ones below.
 *
 * Only tiles near the screen exist as views; they are reused as the grid scrolls, so a library of
 * thousands costs the same as a library of ten. Positions are worked out with arithmetic, not by
 * walking the list. Sizes follow the 1920 x 1080 mockup.
 */
final class Shelf extends ViewGroup {
    interface Listener {
        void onFocus(int index);

        void onTap(int index);
    }

    static final int GRID = 0, SYSTEMS = 1, HERO = 2;

    // Mockup pixels. The grid lives between GRID_TOP and GRID_BOTTOM; the info line is under it.
    private static final float LEFT = 90, RIGHT = 70, GRID_TOP = 150, GRID_BOTTOM = 860, GAP = 24;
    // Picture sizes; each tile also has a LABEL strip under it for the title.
    private static final float DEF_W = 228, DEF_H = 300, SYS_W = 270, SYS_H = 270, LABEL = 42;
    // The hero tile and the small row beside and below it.
    private static final float HERO_W = 430, HERO_H = 620, HERO_Y = 170; // the same size and place as the cover on the details page
    private static final float SMALL_X = 620, SMALL_W = 180, SMALL_H = 262, SMALL_GAP = 20, SMALL_BOTTOM = 956;
    /** How many recent games the hero layout carries. The focused one is large; the next six show beside it. */
    static final int HERO_COUNT = 12;

    private final float s;
    private final SparseArray<Cover> attached = new SparseArray<>();
    private final ArrayList<Cover> pool = new ArrayList<>();
    private List<Game> items = Collections.emptyList();
    private int mode = GRID;
    /** Grid scroll, in mockup pixels from the top of the first row. */
    private float scroll;
    /** Game tile size for the list on show: it follows the shape of the covers (see fitCells). */
    private float gameW = DEF_W, gameH = DEF_H;
    private int index;
    /** Where the hero layout is, as a game number that glides between whole numbers. */
    private float heroPos;
    private ValueAnimator anim;
    private Listener listener;
    private float downY, lastY;
    private boolean dragging;
    private final int slop;

    Shelf(Context c, float s) {
        super(c);
        this.s = s;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
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

    int mode() {
        return mode;
    }

    Game itemAt(int i) {
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    /** Shows a new list from its first item, in the given layout. */
    void setItems(List<Game> list, int layout) {
        if (anim != null) anim.cancel();
        for (int k = 0; k < attached.size(); k++) pool.add(attached.valueAt(k));
        attached.clear();
        removeAllViews();
        items = list;
        mode = layout;
        if (layout == GRID) fitCells(list);
        index = 0;
        scroll = 0;
        heroPos = 0;
        requestLayout();
        if (listener != null && !items.isEmpty()) listener.onFocus(0);
    }

    /**
     * Sizes the tiles to the covers in this list: the middle cover shape wins, so a Game Boy Advance library
     * gets squarer tiles and a PlayStation one gets tall ones. Tall tiles keep the default height (two rows
     * must fit); wide ones get a little smaller in height and a little larger in width.
     */
    private void fitCells(List<Game> list) {
        int n = list.size(), step = Math.max(1, n / 48);
        ArrayList<Float> a = new ArrayList<>();
        for (int i = 0; i < n; i += step) {
            float x = ArtCache.aspect(list.get(i));
            if (x > 0f) a.add(x);
        }
        gameW = DEF_W;
        gameH = DEF_H;
        if (a.size() < 3) return;
        Collections.sort(a);
        float asp = Math.max(.6f, Math.min(1.4f, a.get(a.size() / 2)));
        if (asp < DEF_W / DEF_H) {
            gameH = DEF_H;
            gameW = Math.round(DEF_H * asp);
        } else {
            gameW = Math.round((float) Math.sqrt(DEF_W * DEF_H * asp));
            gameH = Math.round(gameW / asp);
        }
    }

    private int cols() {
        float w = cellW();
        return Math.max(1, (int) Math.floor((1920 - LEFT - RIGHT + GAP) / (w + GAP)));
    }

    private float cellW() {
        return mode == SYSTEMS ? SYS_W : gameW;
    }

    /** A grid cell's full height: the picture and the title strip under it. */
    private float cellH() {
        return (mode == SYSTEMS ? SYS_H : gameH) + LABEL;
    }

    private float maxScroll() {
        int rows = (items.size() + cols() - 1) / cols();
        return Math.max(0f, rows * (cellH() + GAP) - GAP - (GRID_BOTTOM - GRID_TOP));
    }

    /** Grids scroll a whole row at a time: the focused row is kept in view, and the top row is never cut. */
    private float scrollFor(int i) {
        float pitch = cellH() + GAP;
        int visible = Math.max(1, (int) Math.floor((GRID_BOTTOM - GRID_TOP + GAP) / pitch));
        int row = i / cols(), f = Math.round(scroll / pitch);
        if (row < f) f = row;
        if (row > f + visible - 1) f = row - visible + 1;
        return Math.max(0f, Math.min(maxScroll(), f * pitch));
    }

    void setIndex(int i, boolean animate) {
        int n = items.size();
        if (n == 0) return;
        i = Math.max(0, Math.min(n - 1, i));
        boolean changed = i != index;
        index = i;
        if (anim != null) anim.cancel();
        if (mode == HERO) {
            anim = ValueAnimator.ofFloat(heroPos, i);
            anim.setDuration(animate ? 320 : 0);
            anim.setInterpolator(new DecelerateInterpolator(2f));
            anim.addUpdateListener(v -> {
                heroPos = (Float) v.getAnimatedValue();
                requestLayout();
            });
            anim.start();
        } else {
            float target = scrollFor(i);
            if (animate && target != scroll) {
                anim = ValueAnimator.ofFloat(scroll, target);
                anim.setDuration(260);
                anim.setInterpolator(new DecelerateInterpolator(2f));
                anim.addUpdateListener(v -> {
                    scroll = (Float) v.getAnimatedValue();
                    requestLayout();
                });
                anim.start();
            } else {
                scroll = target;
            }
        }
        requestLayout();
        if (changed && listener != null) listener.onFocus(i);
    }

    void move(int d) {
        setIndex(index + d, true);
    }

    /** Up (-1) or down (+1): a grid row at a time. The hero layout has one row, so nothing happens. */
    void moveRow(int d) {
        if (mode == HERO) return;
        int n = items.size(), target = index + d * cols();
        if (d > 0 && target >= n && index / cols() < (n - 1) / cols()) target = n - 1; // last, shorter row
        if (target < 0 || target >= n) return;
        setIndex(target, true);
    }

    /** Redraws the tiles that exist, after something about a game (a star, a cover) changed. */
    void redraw() {
        for (int k = 0; k < attached.size(); k++) attached.valueAt(k).invalidate();
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
        int first, last;
        if (mode == HERO) {
            setClipBounds(null);
            first = Math.max(0, (int) Math.floor(heroPos) - 1);
            last = Math.min(n - 1, (int) Math.ceil(heroPos) + 7);
        } else {
            setClipBounds(new Rect(0, Math.round((GRID_TOP - 20) * s), r - l, Math.round((GRID_BOTTOM + 10) * s)));
            int c = cols();
            float pitch = cellH() + GAP;
            first = Math.max(0, (int) Math.floor(scroll / pitch)) * c;
            last = Math.min(n - 1, ((int) Math.floor((scroll + GRID_BOTTOM - GRID_TOP) / pitch) + 1) * c - 1);
        }

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
            boolean on = i == index;
            float x, y, w, h, wt = on ? 1f : 0f, alpha = -1f, z = on ? 2f : 0f, label = LABEL;
            if (mode == HERO) {
                // d is how far this game is from the hero spot. At 0 it is the big tile; from 1 on it sits in the
                // small row, and between 0 and 1 it glides from one to the other. The game just left fades out.
                float d = i - heroPos, k = Math.max(0f, Math.min(1f, d));
                float slotCx = SMALL_X + (Math.max(d, 1f) - 1) * (SMALL_W + SMALL_GAP) + SMALL_W / 2f;
                float sx = slotCx - SMALL_W / 2f, sy = SMALL_BOTTOM - SMALL_H;
                label = LABEL * k;
                x = LEFT + (sx - LEFT) * k;
                y = HERO_Y + (sy - HERO_Y) * k;
                w = HERO_W + (SMALL_W - HERO_W) * k;
                h = HERO_H + (SMALL_H - HERO_H) * k;
                wt = Math.max(0f, 1f - Math.abs(d));
                alpha = d >= 1f ? .6f : d > 0f ? 1f - .4f * d : Math.max(0f, 1f + d);
                z = d <= 0f ? (d > -1f ? 1f : 0f) : 0f;
                if (d < 0f) z = 1f;
                if (d > 0f && d < 1f) z = 2f;
            } else {
                int cols = cols(), row = i / cols, col = i % cols;
                float used = cols * (cellW() + GAP) - GAP, x0 = LEFT + (1920 - LEFT - RIGHT - used) / 2f;
                float grow = on ? 1.06f : 1f;
                float cx = x0 + col * (cellW() + GAP) + cellW() / 2f, cy = GRID_TOP + row * (cellH() + GAP) - scroll + cellH() / 2f;
                w = cellW() * grow; h = cellH() * grow;
                x = cx - w / 2f; y = cy - h / 2f;
            }
            c.setLabel(label * s);
            c.setWeight(wt);
            c.setAlpha(alpha >= 0f ? alpha : .6f + .4f * wt); // tiles are reused, so never inherit an old fade
            c.setZ(z);
            int pw = Math.round(w * s), ph = Math.round(h * s), px = Math.round(x * s), py = Math.round(y * s);
            c.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY));
            c.layout(px, py, px + pw, py + ph);
        }
    }

    // ---- touch: drag a grid up and down ----

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (mode == HERO) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = lastY = e.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getY() - downY) > slop) {
                    dragging = true;
                    lastY = e.getY();
                    if (anim != null) anim.cancel();
                }
                break;
            default:
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mode == HERO) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    scroll = Math.max(0f, Math.min(maxScroll(), scroll - (e.getY() - lastY) / s));
                    lastY = e.getY();
                    requestLayout();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
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
