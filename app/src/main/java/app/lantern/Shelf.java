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
import android.view.animation.OvershootInterpolator;

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

        /** Pushed against an end of the list: the whole screen can give a little (dx, dy in pixels). */
        void onEdge(float dx, float dy);
    }

    static final int GRID = 0, SYSTEMS = 1, HERO = 2;

    // Mockup pixels. The grid lives between GRID_TOP and GRID_BOTTOM; the info line is under it.
    private static final float LEFT = 90, RIGHT = 70, GRID_TOP = 150, GRID_BOTTOM = 860, GAP = 24;
    // Every grid tile, games and systems, is the same landscape rectangle (like SteamOS); covers are fitted inside it.
    // Each tile also has a LABEL strip under it for the title.
    private static final float DEF_W = 330, DEF_H = 200, SYS_W = 330, SYS_H = 200, LABEL = 42;
    // The hero tile and the small row beside and below it.
    // The big tile and the row under it are the same landscape shape as the grid tiles (about 1.65 : 1).
    private static final float HERO_W = 720, HERO_H = 436, HERO_Y = 150; // the same size and place as the cover on the details page
    private static final float SMALL_X = 90, SMALL_W = 336, SMALL_H = 204, SMALL_GAP = 24, SMALL_BOTTOM = 932;
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
    /** Focus spring (grid): the focused tile eases up with a little overshoot, the one just left settles back. */
    private ValueAnimator spring, enter, breathAnim;
    private int prevIndex = -1;
    private float focusT = 1f;
    /** Tiles come in one after another when a new list appears: 0 to 1. */
    private float enterT = 1f;
    /** The big Recent cover slowly breathes: 0 to 1 and back. */
    private float breath;
    /** The details page is open (0 to 1): the row under the big tile slides down; the big tile itself is hidden while the page's copy stands in. */
    private float pageT;
    /** Recent: the big tile is two covers that cross-fade when the focused game changes. */
    private Cover heroCur, heroPrev;
    private Game heroCurG, heroPrevG;
    private float heroFade = 1f;
    private ValueAnimator heroFadeAnim;
    private boolean heroHide;
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

    /** Where the big Recent tile sits, in mockup pixels: x, y, width, height. The details page cover uses the same box. */
    static float[] heroBox(Game g) {
        return new float[]{LEFT, HERO_Y, HERO_W, HERO_H};
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
        if (spring != null) spring.cancel();
        if (enter != null) enter.cancel();
        // A list that is the same one again (a star toggled, back from a game) does not replay the entrance.
        boolean fresh = layout != mode || list.size() != items.size() || (!list.isEmpty() && !items.isEmpty() && list.get(0) != items.get(0));
        for (int k = 0; k < attached.size(); k++) pool.add(attached.valueAt(k));
        attached.clear();
        removeAllViews();
        items = list;
        mode = layout;
        prevIndex = -1;
        focusT = 1f;
        enterT = 1f;
        if (fresh && layout != HERO && !list.isEmpty()) {
            enterT = 0f;
            enter = ValueAnimator.ofFloat(0f, 1f);
            enter.setDuration(760);
            enter.setInterpolator(new DecelerateInterpolator(1.4f));
            enter.addUpdateListener(v -> {
                enterT = (Float) v.getAnimatedValue();
                requestLayout();
            });
            enter.start();
        }
        if (heroFadeAnim != null) heroFadeAnim.cancel();
        heroFade = 1f;
        heroCurG = heroPrevG = layout == HERO && !list.isEmpty() ? list.get(0) : null;
        startBreath();
        index = 0;
        scroll = 0;
        heroPos = 0;
        requestLayout();
        if (listener != null && !items.isEmpty()) listener.onFocus(0);
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
        return mode == SYSTEMS ? SYS_H : gameH; // grid tiles carry no title: the focused game's name is under the grid
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
        int old = index;
        index = i;
        if (changed && mode != HERO) {
            prevIndex = old;
            focusT = 0f;
            if (spring != null) spring.cancel();
            spring = ValueAnimator.ofFloat(0f, 1f);
            spring.setDuration(380);
            spring.setInterpolator(new OvershootInterpolator(2.6f));
            spring.addUpdateListener(v -> {
                focusT = (Float) v.getAnimatedValue();
                requestLayout();
            });
            spring.start();
        }
        if (anim != null) anim.cancel();
        if (mode == HERO) {
            if (changed) { // the big tile cross-fades to the new game
                heroPrevG = heroCurG;
                heroCurG = items.get(i);
                heroFade = 0f;
                if (heroFadeAnim != null) heroFadeAnim.cancel();
                heroFadeAnim = ValueAnimator.ofFloat(0f, 1f);
                heroFadeAnim.setDuration(280);
                heroFadeAnim.setInterpolator(new DecelerateInterpolator(1.5f));
                heroFadeAnim.addUpdateListener(v -> {
                    heroFade = (Float) v.getAnimatedValue();
                    requestLayout();
                });
                heroFadeAnim.start();
            }
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
        if (index + d < 0 || index + d >= items.size()) {
            if (!items.isEmpty()) nudge(d * 18 * s, 0f);
            return;
        }
        setIndex(index + d, true);
    }

    /** A small push against the edge when there is nothing further in that direction. */
    private void nudge(float dx, float dy) {
        if (listener != null) {
            listener.onEdge(dx, dy);
            return;
        }
        animate().cancel();
        animate().translationX(dx).translationY(dy).setDuration(70).withEndAction(() ->
                animate().translationX(0f).translationY(0f).setDuration(220).setInterpolator(new OvershootInterpolator(3f)).start()).start();
    }

    void setPage(float t, boolean hideHero) {
        pageT = t;
        heroHide = hideHero;
        requestLayout();
    }

    private void startBreath() {
        if (breathAnim != null) breathAnim.cancel();
        breathAnim = null;
        breath = 0f;
        if (mode != HERO) return;
        breathAnim = ValueAnimator.ofFloat(0f, 1f);
        breathAnim.setDuration(6000);
        breathAnim.setRepeatCount(ValueAnimator.INFINITE);
        breathAnim.setRepeatMode(ValueAnimator.REVERSE);
        breathAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        breathAnim.addUpdateListener(v -> {
            breath = (Float) v.getAnimatedValue();
            if (heroCur != null && mode == HERO) {
                applyBreath(heroCur);
                applyBreath(heroPrev);
            }
        });
        if (getWindowVisibility() == View.VISIBLE) breathAnim.start();
    }

    private void applyBreath(Cover c) {
        c.setPivotX(c.getWidth() / 2f);
        c.setPivotY(c.getHeight());
        c.setScaleX(1f + .03f * breath);
        c.setScaleY(1f + .03f * breath);
    }

    @Override
    protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (breathAnim != null) {
            if (v == View.VISIBLE) { if (!breathAnim.isStarted()) breathAnim.start(); else breathAnim.resume(); } else breathAnim.pause();
        }
    }

    /** Up (-1) or down (+1): a grid row at a time. The hero layout has one row, so nothing happens. */
    void moveRow(int d) {
        if (mode == HERO) return;
        int n = items.size(), target = index + d * cols();
        if (d > 0 && target >= n && index / cols() < (n - 1) / cols()) target = n - 1; // last, shorter row
        if (target < 0 || target >= n) {
            nudge(0f, d * 14 * s);
            return;
        }
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
            first = Math.max(0, (int) Math.floor(Math.max(0f, heroPos - 1f)));
            last = Math.min(n - 1, first + 8);
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
            float x, y, w, h, wt = on ? 1f : 0f, alpha = -1f, z = on ? 2f : 0f, label = 0f, ek = 1f, ty = 0f;
            if (mode == HERO) {
                // The whole recent list sits in the row. The focused game is lifted a little, so you can see where you
                // are; the row scrolls to keep it in view, one game back from the left edge.
                float rs = Math.max(0f, heroPos - 1f), d = i - rs;
                float lift = Math.max(0f, 1f - Math.abs(i - heroPos));
                x = SMALL_X + d * (SMALL_W + SMALL_GAP);
                y = SMALL_BOTTOM - SMALL_H - lift * 24f + pageT * 420f;
                w = SMALL_W;
                h = SMALL_H;
                label = LABEL;
                wt = lift;
                z = lift > 0f ? 2f : 0f;
                ek = .3f + .7f * Math.max(0f, Math.min(1f, (1900f - x) / 240f)); // the row fades at the right edge,
                ek *= Math.max(0f, Math.min(1f, (x + SMALL_W - LEFT) / SMALL_W)); // and as it slides out on the left
            } else {
                int cols = cols(), row = i / cols, col = i % cols;
                float used = cols * (cellW() + GAP) - GAP, x0 = LEFT + (1920 - LEFT - RIGHT - used) / 2f;
                float grow = 1f + .06f * Math.max(0f, i == index ? focusT : i == prevIndex ? 1f - focusT : 0f);
                float order = Math.max(0, row - (int) Math.floor(scroll / (cellH() + GAP))) * .10f + col * .05f;
                float kk = Math.max(0f, Math.min(1f, (enterT - order) / .45f));
                ek = kk;
                ty = (1f - kk) * 28f * s;
                float cx = x0 + col * (cellW() + GAP) + cellW() / 2f, cy = GRID_TOP + row * (cellH() + GAP) - scroll + cellH() / 2f;
                w = cellW() * grow; h = cellH() * grow;
                x = cx - w / 2f; y = cy - h / 2f;
            }
            c.setLabel(label * s);
            c.setWeight(wt);
            c.setAlpha((alpha >= 0f ? alpha : .6f + .4f * wt) * ek); // tiles are reused, so never inherit an old fade
            c.setTranslationY(ty);
            c.setShot(null, 0f);
            float sc = mode == HERO ? 1f : .88f + .12f * ek; // grid tiles grow in as they arrive
            c.setScaleX(sc);
            c.setScaleY(sc);
            c.setZ(z);
            int pw = Math.round(w * s), ph = Math.round(h * s), px = Math.round(x * s), py = Math.round(y * s);
            c.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY));
            c.layout(px, py, px + pw, py + ph);
        }
        if (mode == HERO) layoutHero(r - l);
    }

    /** The big tile: the focused game, cross-fading from the one before. */
    private void layoutHero(int width) {
        if (heroCur == null) {
            heroCur = newCover();
            heroPrev = newCover();
            heroCur.setOnClickListener(v -> { if (listener != null) listener.onTap(index); });
        }
        float[] hb = heroBox(null);
        int px = Math.round(hb[0] * s), py = Math.round(hb[1] * s), pw = Math.round(hb[2] * s), ph = Math.round(hb[3] * s);
        Cover[] covers = {heroPrev, heroCur};
        Game[] games = {heroPrevG, heroCurG};
        float[] alphas = {heroFade < 1f ? 1f - heroFade : 0f, heroFade};
        for (int k = 0; k < 2; k++) {
            Cover c = covers[k];
            if (c.getParent() == null) addViewInLayout(c, -1, new ViewGroup.LayoutParams(-2, -2), true);
            if (games[k] != null && c.game() != games[k]) c.setGame(games[k]);
            c.setLabel(0f);
            c.setWeight(1f);
            c.setAlpha(heroHide ? 0f : alphas[k]);
            c.setShot(ArtCache.shot(games[k]), 1f); // a screenshot of where it was left
            c.setZ(3f);
            c.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY));
            c.layout(px, py, px + pw, py + ph);
        }
        applyBreath(heroCur);
        applyBreath(heroPrev);
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
