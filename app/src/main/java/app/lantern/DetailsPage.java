package app.lantern;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One game's page: its cover, its title and the actions (play, resume, app settings). It opens from Recent's big tile
 * (pressing Down) or from a grid tile (pressing A), and the cover flies there from where it was, and back again.
 *
 * <p>The choreography: the cover and the title travel to the centre of the screen together; the tab bar fades away (on
 * Recent's page a wide chevron takes its place); on Recent the row of games slides down and away. Closing reverses all
 * of it. While the cover is travelling, the real tile and title stay hidden, so nothing shows twice.
 */
final class DetailsPage {
    /** What the page needs from the launcher. */
    interface Host {
        int px(float mockupPx);

        float scale();

        TextView text(String text, float mockupPx, int color, boolean bold);

        Shelf shelf();

        /** The info block beside Recent's big tile (the line above the title, the title, chips, status, the Play button). */
        ViewGroup info();

        TabBar tabBar();

        /** The focused game. */
        Game game();

        /** The line above the title on Recent ("Recently played"...). */
        String eyebrow();

        void infoVisible(boolean visible);

        /** The dots and arrows around Recent's row are hidden while the page is open. */
        void listChromeVisible(boolean visible);

        /** The hints at the bottom: the page's (true) or the screen's own (false). */
        void hints(boolean page);

        void play(Game g, boolean resume);

        void appSettings(Game g);

        String ago(long time);
    }

    /** Length of the moves that open and close the page: cover, tab bar, chevron and row all take this long. */
    private static final long PAGE_MS = 320;

    /** One row in the action list. */
    private static final class Act {
        final String icon, label, hint;

        Act(String icon, String label, String hint) {
            this.icon = icon;
            this.label = label;
            this.hint = hint;
        }
    }

    private final Context ctx;
    private final Host host;
    private final FrameLayout view;
    private final View chevron;
    private boolean open;
    private int actIdx;
    private View cover, col, rest, eyeb;
    private TextView titleView;
    /** Where the title sits inside the column, and how small it starts (Recent's title is smaller than the page's). */
    private int titleOff;
    private float titleFrom = 1f;
    /** Top of the text column on screen, in pixels: it is centred on the screen like the cover. */
    private int colTop;
    private android.animation.ValueAnimator coverAnim;

    DetailsPage(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        view = new FrameLayout(ctx);
        view.setVisibility(View.GONE);
        // On Recent's page the tab bar slips away and a wide chevron says that Up goes back.
        chevron = new View(ctx) {
            private final android.graphics.Paint cp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path path = new android.graphics.Path();

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight();
                cp.setStyle(android.graphics.Paint.Style.STROKE);
                cp.setStrokeWidth(7 * host.scale());
                cp.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                cp.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                cp.setColor(0xCCFFFFFF);
                path.reset();
                path.moveTo(.08f * w, .78f * h);
                path.lineTo(.5f * w, .24f * h);
                path.lineTo(.92f * w, .78f * h);
                c.drawPath(path, cp);
            }
        };
        chevron.setVisibility(View.GONE);
        chevron.setOnClickListener(x -> { if (open) close(); });
    }

    /** The layer for the page (it covers the screen). */
    FrameLayout view() {
        return view;
    }

    /** The up chevron, to put at the top of the screen. */
    View chevron() {
        return chevron;
    }

    boolean isOpen() {
        return open;
    }

    /** Stops the animation: call when the screen is going away. */
    void stop() {
        if (coverAnim != null) coverAnim.cancel();
    }

    private List<Act> actionsFor(Game g) {
        List<Act> a = new ArrayList<>();
        long saved = Library.resumeTime(g);
        if (saved > 0) a.add(new Act("⟲", "Resume", "Saved " + host.ago(saved)));
        a.add(new Act("▶", saved > 0 ? "Play from the start" : "Play", g.isRom() ? (Cores.has(ctx, g.sysId) ? "Built-in player" : Cores.infoFor(g.sysId) != null ? "Add the emulator first" : "No emulator yet") : "Android app"));
        if (!g.isRom()) a.add(new Act("⚙", "App settings", "Android settings"));
        return a;
    }

    void open() {
        Game g = host.game();
        if (g == null || g.systemTile) return;
        Shelf shelf = host.shelf();
        open = true;
        host.listChromeVisible(false);
        final boolean fromHero = shelf.mode() == Shelf.HERO;
        chrome(true, fromHero); // the chevron is for Recent's page only
        actIdx = 0;
        render();
        view.setVisibility(View.VISIBLE);
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        // The cover starts where it was (Recent's big tile, or the grid tile) and moves to its place on the page,
        // centred on the screen.
        placeCoverAtSource();
        moveCover(1f, 1f, 0f, 0f, null);
        view.setAlpha(1f);
        view.setTranslationY(0f);
        if (fromHero) {
            // The title starts exactly where (and as big as) Recent's title is and grows into its place on the page;
            // the line above it fades in.
            titleToRecent();
            titleView.animate().cancel();
            titleView.animate().translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease).start();
            eyeb.setAlpha(0f);
            eyeb.animate().alpha(1f).setStartDelay(120).setDuration(PAGE_MS - 120).setInterpolator(ease).start();
            // What Recent shows under its title (chips, status, Play) fades out instead of vanishing; its title is the
            // page's title now, so it goes in the same frame.
            final ViewGroup info = host.info();
            for (int ci = 0; ci < info.getChildCount(); ci++) {
                View cv = info.getChildAt(ci);
                cv.animate().cancel();
                if (ci < 2) cv.setAlpha(0f);
                else {
                    cv.animate().alpha(0f).setStartDelay(0).setDuration(110).withEndAction(() -> { if (open) host.infoVisible(false); }).start();
                }
            }
            // Down moves the screen down: the row under the big tile slides away, and the details drop in from above.
            rest.setAlpha(0f);
            rest.setTranslationY(-host.px(70));
            rest.animate().alpha(1f).translationY(0f).setStartDelay(170).setDuration(300).setInterpolator(ease).start();
            shelf.setPage(0f, true);
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(PAGE_MS);
            va.setInterpolator(ease);
            va.addUpdateListener(a -> shelf.setPage((Float) a.getAnimatedValue(), true));
            va.start();
        } else {
            shelf.setHiddenTile(shelf.getIndex()); // the cover flying to the page is this tile: do not leave a copy behind
            col.setAlpha(0f);
            col.animate().alpha(1f).setStartDelay(100).setDuration(PAGE_MS).setInterpolator(ease).start();
            shelf.animate().cancel();
            shelf.animate().alpha(0f).setDuration(PAGE_MS).withEndAction(() -> {
                if (open) shelf.setVisibility(View.INVISIBLE);
            }).start();
        }
        if (!fromHero) host.infoVisible(false);
        host.hints(true);
    }

    /** Puts the title (as a copy of Recent's) at Recent's place: the same size, the same spot on the screen. */
    private void titleToRecent() {
        titleView.setPivotX(0f);
        titleView.setPivotY(0f);
        titleView.setScaleX(titleFrom);
        titleView.setScaleY(titleFrom);
        titleView.setTranslationY(host.px(164) - colTop - titleOff);
    }

    void close() {
        Shelf shelf = host.shelf();
        open = false;
        chrome(false, false);
        host.listChromeVisible(true);
        if (cover == null) {
            shelf.setHiddenTile(-1);
            view.setVisibility(View.GONE);
            view.removeAllViews();
            shelf.setVisibility(View.VISIBLE);
            host.infoVisible(true);
            host.hints(false);
            shelf.redraw();
            return;
        }
        final boolean hero = shelf.mode() == Shelf.HERO;
        final ViewGroup info = host.info();
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        // The cover goes back to where it came from, and the page is taken down once it has landed.
        float[] src = sourceRect(), pb = Shelf.pageBox();
        cover.setPivotX(0f);
        cover.setPivotY(0f);
        moveCover(src[2] / pb[2], src[3] / pb[3], host.px(src[0] - pb[0]), host.px(src[1] - pb[1]), () -> {
            if (open) return; // opened again meanwhile
            view.setVisibility(View.GONE);
            view.removeAllViews();
            shelf.setHiddenTile(-1); // the tile shows again exactly where the cover landed
            if (hero) {
                // The page's copy and the real tile and title are at the same place now: swap in the same frame.
                shelf.setPage(0f, false);
                for (int ci = 0; ci < 2 && ci < info.getChildCount(); ci++) info.getChildAt(ci).setAlpha(1f); // the line above the title, and the title
            }
        });
        shelf.animate().cancel();
        shelf.setVisibility(View.VISIBLE);
        if (hero) {
            // Up moves the screen back up: the details lift away, the row slides back in from below, and the info returns.
            // The big tile and the real title stay hidden while the page's cover and title travel back (so nothing
            // shows twice); both come back in the frame the page goes.
            shelf.setAlpha(1f);
            shelf.setPage(1f, true);
            // The rest of the info (chips, status, the Play button) comes back with the row sliding in, not after it.
            info.animate().cancel();
            info.setAlpha(1f);
            host.infoVisible(true);
            for (int ci = 0; ci < info.getChildCount(); ci++) {
                View cv = info.getChildAt(ci);
                cv.animate().cancel();
                cv.setAlpha(0f);
                if (ci >= 2) cv.animate().alpha(1f).setStartDelay(120).setDuration(PAGE_MS - 120).start();
            }
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(1f, 0f);
            va.setDuration(PAGE_MS);
            va.setInterpolator(ease);
            va.addUpdateListener(a -> {
                if (!open) shelf.setPage((Float) a.getAnimatedValue(), (Float) a.getAnimatedValue() > 0f);
            });
            va.start();
            rest.animate().alpha(0f).translationY(-host.px(70)).setStartDelay(0).setDuration(240).setInterpolator(ease).start();
            titleView.animate().cancel();
            titleView.animate().translationY(host.px(164) - colTop - titleOff).scaleX(titleFrom).scaleY(titleFrom).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease).start();
            eyeb.animate().cancel();
            eyeb.animate().alpha(0f).setStartDelay(0).setDuration(PAGE_MS / 2).setInterpolator(ease).start();
        } else {
            shelf.setAlpha(0f);
            shelf.animate().alpha(1f).setStartDelay(PAGE_MS / 2).setDuration(PAGE_MS / 2).start(); // under the landing cover
            col.animate().alpha(0f).setStartDelay(0).setDuration(PAGE_MS / 2).start();
            info.animate().cancel();
            info.setAlpha(0f);
            host.infoVisible(true);
            info.animate().alpha(1f).setStartDelay(120).setDuration(260).start();
        }
        host.hints(false);
        shelf.redraw();
    }

    /** With the page open the tab bar fades up and away and (on Recent's page) a wide chevron takes its place. */
    private void chrome(boolean opening, boolean withChevron) {
        // The tab bar and the chevron are one crossfade: same start, same length, same easing.
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        chevron.animate().cancel();
        if (opening) {
            host.tabBar().away(true, PAGE_MS);
            if (!withChevron) {
                chevron.setVisibility(View.GONE);
                return;
            }
            chevron.setVisibility(View.VISIBLE);
            chevron.setAlpha(0f);
            chevron.setTranslationY(host.px(26));
            chevron.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease).start();
        } else {
            host.tabBar().away(false, PAGE_MS);
            chevron.animate().alpha(0f).translationY(host.px(26)).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease)
                    .withEndAction(() -> { if (!open) chevron.setVisibility(View.GONE); }).start();
        }
    }

    /** Where the cover came from: Recent's big tile, or the tile in the grid. */
    private float[] sourceRect() {
        Shelf shelf = host.shelf();
        if (shelf.mode() == Shelf.HERO) return Shelf.heroBox(null);
        float[] r = shelf.tileRect(shelf.getIndex());
        return r != null ? r : Shelf.heroBox(null);
    }

    /**
     * Moves the cover to a scale and place. It is driven by hand, not by a property animator, so the cover is
     * redrawn on every frame and its white ring can keep the same thickness on screen the whole way.
     */
    private void moveCover(final float tsx, final float tsy, final float ttx, final float tty, final Runnable end) {
        if (coverAnim != null) coverAnim.cancel();
        final View c = cover;
        final float fsx = c.getScaleX(), fsy = c.getScaleY(), ftx = c.getTranslationX(), fty = c.getTranslationY();
        coverAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        coverAnim.setDuration(PAGE_MS);
        coverAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
        coverAnim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            c.setScaleX(fsx + (tsx - fsx) * f);
            c.setScaleY(fsy + (tsy - fsy) * f);
            c.setTranslationX(ftx + (ttx - ftx) * f);
            c.setTranslationY(fty + (tty - fty) * f);
            c.invalidate();
        });
        if (end != null) {
            coverAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator an) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator an) {
                    if (!cancelled) end.run();
                }
            });
        }
        coverAnim.start();
    }

    private void placeCoverAtSource() {
        float[] src = sourceRect(), pb = Shelf.pageBox();
        cover.setPivotX(0f);
        cover.setPivotY(0f);
        cover.setScaleX(src[2] / pb[2]);
        cover.setScaleY(src[3] / pb[3]);
        cover.setTranslationX(host.px(src[0] - pb[0]));
        cover.setTranslationY(host.px(src[1] - pb[1]));
    }

    private void render() {
        Game g = host.game();
        Shelf shelf = host.shelf();
        view.removeAllViews();

        Cover big = new Cover(ctx, host.scale());
        big.setGame(g);
        big.setWeight(1f);
        float[] hb = Shelf.pageBox(); // the same size as Recent's big cover, centred on the screen
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(host.px(hb[2]), host.px(hb[3]), Gravity.TOP | Gravity.START);
        cp.leftMargin = host.px(hb[0]);
        cp.topMargin = host.px(hb[1]);
        view.addView(big, cp);
        cover = big;

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        // The line above the title and the title are the same size and in the same place as on Recent, so they
        // do not move when the page opens; only what is below them slides in.
        boolean hero = shelf.mode() == Shelf.HERO;
        if (hero) {
            TextView eb = host.text(host.eyebrow(), 24, HomeActivity.AMBER, true);
            eb.setLetterSpacing(.16f);
            eb.setSingleLine(true);
            LinearLayout.LayoutParams ebp = new LinearLayout.LayoutParams(-2, -2);
            ebp.bottomMargin = host.px(8);
            column.addView(eb, ebp);
            eyeb = eb;
        }
        float tk = hero ? Math.max(.62f, Math.min(1f, 24f / Math.max(1, g.title.length()))) : 1f;
        TextView t = host.text(g.title, 84 * tk, Color.WHITE, true);
        t.setMaxLines(3);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(t);
        titleView = t;
        titleFrom = hero ? 62f / (84 * tk) : 1f; // Recent's title is 62 (in mockup pixels)

        LinearLayout below = new LinearLayout(ctx);
        below.setOrientation(LinearLayout.VERTICAL);
        column.addView(below, new LinearLayout.LayoutParams(-1, -2));
        rest = below;

        LinearLayout meta = new LinearLayout(ctx);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView chip = host.text(g.system.toUpperCase(), 26, Color.WHITE, true);
        GradientDrawable cd = new GradientDrawable();
        cd.setCornerRadius(host.px(99));
        cd.setColor(0x29FFFFFF);
        chip.setBackground(cd);
        chip.setPadding(host.px(18), host.px(4), host.px(18), host.px(4));
        meta.addView(chip);
        TextView when = host.text(g.lastPlayed == 0 ? "Never played" : "Played " + host.ago(g.lastPlayed), 30, 0xC7FFFFFF, false);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
        wp.leftMargin = host.px(22);
        meta.addView(when, wp);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = host.px(14);
        below.addView(meta, mp);

        TextView blurb = host.text(summary(g), 30, 0xBFFFFFFF, false);
        blurb.setLineSpacing(0, 1.25f);
        blurb.setMaxLines(4);
        blurb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(host.px(900), -2);
        bp.topMargin = host.px(22);
        bp.bottomMargin = host.px(30);
        below.addView(blurb, bp);

        List<Act> acts = actionsFor(g);
        actIdx = Math.max(0, Math.min(actIdx, acts.size() - 1));
        for (int i = 0; i < acts.size(); i++) {
            Act a = acts.get(i);
            boolean on = i == actIdx;
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(host.px(30), 0, host.px(30), 0);
            GradientDrawable rd = new GradientDrawable();
            rd.setCornerRadius(host.px(16));
            rd.setColor(on ? Color.WHITE : 0x14FFFFFF);
            row.setBackground(rd);
            if (on) row.setTranslationX(host.px(14));
            int ink = on ? 0xFF17131F : Color.WHITE;
            TextView ic = host.text(a.icon, 30, ink, true);
            ic.setGravity(Gravity.CENTER);
            row.addView(ic, new LinearLayout.LayoutParams(host.px(46), -2));
            row.addView(host.text(a.label, 34, ink, true));
            row.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(host.text(a.hint, 24, on ? 0xBF17131F : 0xB3FFFFFF, false));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(host.px(760), host.px(82));
            rp.bottomMargin = host.px(14);
            below.addView(row, rp);
        }

        FrameLayout.LayoutParams colp = new FrameLayout.LayoutParams(host.px(980), -2, Gravity.TOP | Gravity.START);
        colp.leftMargin = host.px(870);
        column.measure(View.MeasureSpec.makeMeasureSpec(host.px(980), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        titleOff = hero ? eyeb.getMeasuredHeight() + host.px(8) : 0;
        colTop = Math.max(host.px(120), (host.px(1080) - column.getMeasuredHeight()) / 2); // centred on the screen, like the cover
        colp.topMargin = colTop;
        view.addView(column, colp);
        col = column;
    }

    /** A key pressed while the page is open. Returns false for keys the launcher handles itself. */
    boolean key(int keyCode, boolean first) {
        Game g = host.game();
        List<Act> acts = actionsFor(g);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (actIdx > 0) { actIdx--; render(); }
                else if (first && host.shelf().mode() == Shelf.HERO) close(); // top of Recent's page: back up (the grid's page goes back with B)
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (actIdx < acts.size() - 1) { actIdx++; render(); }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK: // the system Back key (and gesture) does the same as B
                if (first) close();
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) activate(g, acts.get(actIdx));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_L1: // the page is for this game only; the tabs and the list are not reachable from here
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_START:
                return true;
            default:
                return false;
        }
    }

    private void activate(Game g, Act a) {
        if (a.label.equals("Resume")) host.play(g, true);
        else if (a.label.equals("Play") || a.label.equals("Play from the start")) host.play(g, false);
        else if (a.label.equals("App settings")) host.appSettings(g);
    }

    private String summary(Game g) {
        if (g.isRom()) {
            return "A " + g.system + " game from your ROM folder. "
                    + (Cores.has(ctx, g.sysId) ? "It opens in Lantern's own player."
                    : Cores.infoFor(g.sysId) != null ? "Its emulator is not added yet: Play opens the list to download it."
                    : "Lantern has no emulator for this system yet.");
        }
        return "An installed Android app. It opens as it normally does, and Home brings you back here.";
    }
}
