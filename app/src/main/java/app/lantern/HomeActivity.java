package app.lantern;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The home screen, laid out to the 1920 x 1080 mockup: three shelves switched with L1 and R1,
 * D-pad to browse, A to open, X to search. Every size below is in mockup pixels, scaled to the screen.
 */
public class HomeActivity extends Activity {
    private static final int AMBER = 0xFFFFB454;
    private static final String[] TABS = {"Recent", "Systems", "All games", "Search"};
    private static final int TAB_SYSTEMS = 1, TAB_ALL = 2, TAB_SEARCH = 3;
    /** The tab to go back to when the search is left with B. */
    private int tabBeforeSearch;

    private final List<Game> all = new ArrayList<>();
    private final List<Game> shown = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();

    private float s = 1f;
    /** Text is drawn this much larger than the mockup's sizes, and the side margins are tighter (EDGE_L, EDGE_R). */
    private static final float FONT = 1.1f;
    private static final int EDGE_L = 90, EDGE_R = 70;
    private boolean loaded;
    private String batteryText = "";
    private FrameLayout root, safe, stage, pageView;
    private LinearLayout infoBlock, hintsRow;
    private boolean pageOpen, infoOpen;
    private ArtFetcher fetcher;
    private boolean artOn;
    /** When set, the shelf shows only this system's games (opened from the Systems shelf). */
    private String filter;
    private int loadGeneration;
    private int actIdx;
    private Glow glow;
    private Shelf shelf;
    private TextView playPill;
    /** The highlight behind the active tab: it glides from tab to tab and grows or shrinks with the label. */
    private View tabPill;
    private LinearLayout tabBar;
    private TextClock clock;
    private GradientDrawable pillBg;
    private TextView eyebrow;
    private Game lastFocused;
    private int lastHeroIndex = -1;
    /** Top of the details text column on screen, in pixels: it is centred on the screen like the cover. */
    private int pageColTop;
    private android.animation.ValueAnimator marquee;
    private TextView marqueeTab;
    private View veil;
    private View dots, upChev;
    /** Length of the moves that open and close the details page: tab bar, chevron and row all take this long. */
    private static final long PAGE_MS = 320;
    private int dotsN, dotsAt;
    private float[] tabNat;
    private boolean clock24;
    private FrameLayout tabHost;
    private android.animation.ValueAnimator pillAnim;
    private int pillX, pillW;
    /** The page's big cover and its text column, kept so Recent's game can fly into place and back. */
    private View pageCover, pageCol, pageRest;
    private View chevL, chevR;
    private FrameLayout.LayoutParams infoLp;
    /** Recent's big first game has the focus, so A plays it straight away. */
    private boolean heroFocused;
    /** Recent's selected tile is the "All games" one, so A opens it and there are no details. */
    private boolean heroAll;
    private TextView title, when, chipView, sub, empty, banner, battery;
    private int tab = -1;
    private String lastId;
    /** The search being shown on the shelf (all games that match), or null. */
    private String query;
    private boolean searchOpen;
    private String draft = "";
    private int kbRow, kbCol;
    private FrameLayout searchView;
    private TextView crumb, letterBadge;
    /** Android apps are hidden from every shelf unless this is on; the drawer lists them either way. */
    private boolean showApps;
    private boolean drawerOpen;
    private int drawerIdx;
    private FrameLayout drawerView;
    private static final String[][] KEYS = {
        {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
        {"z", "x", "c", "v", "b", "n", "m", "Space", "Del", "OK"},
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The screen is built once the window's real size is known: some devices keep space for
        // system bars even in full screen, and the 16:9 layout then has to fit what is left.
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        fullScreenWindow(this);
        fetcher = new ArtFetcher(this);
        artOn = getSharedPreferences("lantern", MODE_PRIVATE).getBoolean("art", false); // off until asked
        showApps = getSharedPreferences("lantern", MODE_PRIVATE).getBoolean("showApps", false);
        root = new FrameLayout(this) {
            @Override
            protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                post(HomeActivity.this::recompute);
            }
        };
        root.setBackgroundColor(0xFF0B0A10);
        glow = new Glow(this, s);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        // Everything that must stay readable sits inside `safe`, which is padded by whatever
        // system bars or camera cut-outs are showing. The glow stays full-bleed behind it.
        safe = new FrameLayout(this);
        safe.setClipChildren(false);
        safe.setClipToPadding(false);
        root.addView(safe, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = ins.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                // Full screen: nothing is reserved. The window draws behind the (hidden, transparent)
                // navigation bar, see fullScreenWindow; the mockup's own margins sit beside a camera hole.
                safe.setPadding(0, 0, 0, 0);
            } else {
                safe.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                        ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            }
            root.post(this::recompute);
            return ins;
        });
        // Nothing here takes the system's keyboard focus: keys are handled by onKeyDown, and a focused view would
        // get Android's default focus highlight, a pale rectangle drawn over it.
        root.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.setDefaultFocusHighlightEnabled(false);
        setContentView(root);
    }

    /** Fits the 16:9 layout into the space the system leaves, and rebuilds when that changes. */
    private void recompute() {
        int w = safe.getWidth() - safe.getPaddingLeft() - safe.getPaddingRight();
        int h = safe.getHeight() - safe.getPaddingTop() - safe.getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        float ns = Math.min(w / 1920f, h / 1080f);
        if (stage == null || Math.abs(ns - s) > 0.001f) {
            s = ns;
            buildUi();
        }
    }

    /** Lets the window draw over the whole screen, including the strip where the navigation bar sits. */
    static void fullScreenWindow(android.app.Activity a) {
        android.view.Window w = a.getWindow();
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setStatusBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
    }

    private void buildUi() {
        safe.removeAllViews();
        tabViews.clear();
        glow.to(0xFF2A2438, 0xFF14121C);
        stage = new FrameLayout(this);
        stage.setClipChildren(false); // on screens wider than 16:9 the row may run past the layout box
        safe.addView(stage, new FrameLayout.LayoutParams(u(1920), u(1080), Gravity.CENTER));

        shelf = new Shelf(this, s);
        shelf.setListener(new Shelf.Listener() {
            @Override
            public void onFocus(int index) {
                onFocused(index);
            }

            @Override
            public void onTap(int index) {
                if (index == shelf.getIndex()) activateShelf(); else shelf.setIndex(index, true);
            }

            @Override
            public void onEdge(float dx, float dy) {
                elastic(dx, dy);
            }
        });
        stage.addView(shelf, new FrameLayout.LayoutParams(-1, -1));
        buildTopBar();
        crumb = label("", 28, 0xCCFFFFFF, false);
        crumb.setVisibility(View.GONE);
        FrameLayout.LayoutParams cr = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cr.leftMargin = u(EDGE_L);
        cr.topMargin = u(104);
        stage.addView(crumb, cr);
        letterBadge = label("", 64, Color.WHITE, true);
        letterBadge.setGravity(Gravity.CENTER);
        GradientDrawable lb = new GradientDrawable();
        lb.setCornerRadius(u(28));
        lb.setColor(0xCC17131F);
        lb.setStroke(u(3), AMBER);
        letterBadge.setBackground(lb);
        letterBadge.setAlpha(0f);
        FrameLayout.LayoutParams lbp = new FrameLayout.LayoutParams(u(120), u(120), Gravity.TOP | Gravity.END);
        lbp.rightMargin = u(EDGE_R);
        lbp.topMargin = u(200);
        stage.addView(letterBadge, lbp);
        buildInfo();
        chevL = chevron("\u2039", 40, -1);
        chevR = chevron("\u203A", 1878, 1);
        // Dots under the big tile: where you are in the recent list.
        dots = new View(this) {
            private final android.graphics.Paint dp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                int n = Math.min(dotsN, 13);
                if (n < 2) return;
                float sp = 30 * s, r = 5 * s, x0 = getWidth() / 2f - (n - 1) * sp / 2f, cy = getHeight() / 2f;
                int at = Math.min(dotsAt, n - 1);
                for (int i = 0; i < n; i++) {
                    if (i == at) {
                        dp.setColor(AMBER);
                        c.drawRoundRect(x0 + i * sp - 13 * s, cy - r, x0 + i * sp + 13 * s, cy + r, r, r, dp);
                    } else {
                        dp.setColor(0x80FFFFFF);
                        c.drawCircle(x0 + i * sp, cy, r * .8f, dp);
                    }
                }
            }
        };
        dots.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(u(720), u(24), Gravity.TOP | Gravity.START);
        dlp.leftMargin = u(EDGE_L);
        dlp.topMargin = u(604);
        stage.addView(dots, dlp);
        // On the details page the tab bar slips away and a wide chevron says that Up goes back.
        upChev = new View(this) {
            private final android.graphics.Paint cp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path path = new android.graphics.Path();

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight();
                cp.setStyle(android.graphics.Paint.Style.STROKE);
                cp.setStrokeWidth(7 * s);
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
        upChev.setVisibility(View.GONE);
        upChev.setOnClickListener(x -> { if (pageOpen) closePage(); });
        FrameLayout.LayoutParams ucp = new FrameLayout.LayoutParams(u(170), u(44), Gravity.TOP | Gravity.START);
        ucp.leftMargin = u(960 - 85);
        ucp.topMargin = u(38);
        stage.addView(upChev, ucp);
        empty = label("", 44, 0x99FFFFFF, true);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(u(EDGE_L), 0, u(EDGE_L), 0);
        stage.addView(empty, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        pageOpen = false;
        pageView = new FrameLayout(this);
        pageView.setVisibility(View.GONE);
        stage.addView(pageView, new FrameLayout.LayoutParams(-1, -1));
        searchOpen = false;
        searchView = new FrameLayout(this);
        searchView.setVisibility(View.GONE);
        stage.addView(searchView, new FrameLayout.LayoutParams(-1, -1));
        buildHints();
        drawerOpen = false;
        drawerView = new FrameLayout(this);
        drawerView.setVisibility(View.GONE);
        stage.addView(drawerView, new FrameLayout.LayoutParams(-1, -1));

        battery.setText(batteryText);
        if (loaded) {
            refresh(true);
        }
    }

    private void buildTopBar() {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(u(EDGE_L), 0, u(EDGE_R), 0);

        LanternLogo logo = new LanternLogo(this);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(u(80), u(80));
        fp.rightMargin = u(22);
        top.addView(logo, fp);
        // Spaced capitals with a lantern-lit gradient; the first letter is the warmest.
        TextView word = label("LANTERN", 30, Color.WHITE, true);
        word.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        word.setLetterSpacing(0.3f);
        word.setShadowLayer(u(12), 0, 0, 0x55FFB454);
        word.post(() -> word.getPaint().setShader(new android.graphics.LinearGradient(0, 0, word.getWidth(), 0,
                new int[]{0xFFFFC46B, 0xFFFFFFFF, 0xFFFFE9C4}, new float[]{0f, .35f, 1f}, android.graphics.Shader.TileMode.CLAMP)));
        top.addView(word);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabBar = tabs;
        tabHost = new FrameLayout(this);
        tabPill = new View(this);
        pillBg = new GradientDrawable();
        pillBg.setCornerRadius(u(99));
        pillBg.setColor(0x2EFFFFFF);
        tabPill.setBackground(pillBg);
        pillX = pillW = 0;
        tabHost.addView(tabPill, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.START));
        tabHost.addView(tabs, new FrameLayout.LayoutParams(-1, -2));
        // A fixed width: the bar never grows into the battery and the clock. Tabs share it by weight (see applyTabWeights).
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(u(1000), -2);
        tp.leftMargin = u(60);
        top.addView(tabHost, tp);
        tabs.addView(glyph("L1", Color.WHITE, false));
        for (String name : TABS) {
            TextView t = label(name, 32, Color.WHITE, false);
            t.setPadding(u(18), u(8), u(18), u(8));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = u(11);
            lp.rightMargin = u(11);
            tabs.addView(t, lp);
            tabViews.add(t);
        }
        tabs.addView(glyph("R1", Color.WHITE, false));
        tabNat = new float[TABS.length];
        android.graphics.Paint bold = new android.graphics.Paint(tabViews.get(0).getPaint());
        bold.setTypeface(Typeface.DEFAULT_BOLD);
        for (int i = 0; i < TABS.length; i++) tabNat[i] = bold.measureText(TABS[i]);
        applyTabWeights(-1);

        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        battery = label("", 30, Color.WHITE, false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.rightMargin = u(26);
        top.addView(battery, bp);
        clock = new TextClock(this);
        clock24 = getSharedPreferences("lantern", MODE_PRIVATE).getBoolean("clock24", android.text.format.DateFormat.is24HourFormat(this));
        applyClock();
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * FONT * s);
        top.addView(clock);
        stage.addView(top, new FrameLayout.LayoutParams(-1, u(120), Gravity.TOP));
    }

    private void stopMarquee() {
        if (marquee != null) marquee.cancel();
        marquee = null;
        if (marqueeTab != null) marqueeTab.setScrollX(0);
        marqueeTab = null;
    }

    /** A system name too long for its place in the tab bar slides slowly to its end and back, pausing at each end. */
    private void startMarquee(TextView t) {
        if (t == null || tab < 0 || tab >= tabViews.size() || tabViews.get(tab) != t || t.getWidth() == 0) return;
        stopMarquee();
        float avail = t.getWidth() - t.getTotalPaddingLeft() - t.getTotalPaddingRight();
        float over = t.getPaint().measureText(String.valueOf(t.getText())) - avail;
        if (over < 4f) return;
        marqueeTab = t;
        marquee = android.animation.ValueAnimator.ofFloat(0f, over + u(6));
        marquee.setDuration(Math.max(2600L, (long) (over / s / 40f * 1000f))); // about 40 mockup pixels a second
        marquee.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        marquee.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        marquee.setInterpolator(x -> {
            float f = Math.max(0f, Math.min(1f, (x - .18f) / .64f)); // rests at both ends
            return f * f * (3f - 2f * f);
        });
        marquee.addUpdateListener(a -> t.setScrollX(Math.round((Float) a.getAnimatedValue())));
        marquee.start();
    }

    private void applyClock() {
        String f = clock24 ? "HH:mm" : "h:mm a";
        clock.setFormat24Hour(f);
        clock.setFormat12Hour(f);
    }

    /**
     * The tabs share a fixed width by weight, so the bar is always the same size. Inside a system or a search the
     * tab that holds the breadcrumb (crumb, or -1) takes the room the others give up by tightening their padding.
     */
    private float[] curW, curM, curP;
    private android.animation.ValueAnimator weightAnim;

    private void applyTabWeights(int crumb) {
        int n = tabViews.size();
        float total = 0, others = 0;
        for (int i = 0; i < tabNat.length; i++) {
            total += tabNat[i] + u(58);
            if (i != crumb) others += tabNat[i] + u(24);
        }
        final float[] tw = new float[n], tm = new float[n], tp = new float[n];
        for (int i = 0; i < n; i++) {
            boolean tight = crumb >= 0 && i != crumb;
            tw[i] = crumb < 0 ? tabNat[i] + u(58) : i == crumb ? total - others : tabNat[i] + u(24);
            tm[i] = tight ? u(4) : u(11);
            tp[i] = tight ? u(8) : u(18);
        }
        if (curW == null) { // the first time: just set it
            curW = tw.clone();
            curM = tm.clone();
            curP = tp.clone();
            applyTabValues();
            return;
        }
        if (weightAnim != null) weightAnim.cancel();
        final float[] fw = curW.clone(), fm = curM.clone(), fp = curP.clone();
        boolean same = true;
        for (int i = 0; i < n; i++) if (Math.abs(fw[i] - tw[i]) > .5f || Math.abs(fm[i] - tm[i]) > .5f) same = false;
        if (same) return;
        // The tabs slide aside to make room for the breadcrumb (or back together), and the highlight follows.
        weightAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        weightAnim.setDuration(340);
        weightAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
        weightAnim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            for (int i = 0; i < n; i++) {
                curW[i] = fw[i] + (tw[i] - fw[i]) * f;
                curM[i] = fm[i] + (tm[i] - fm[i]) * f;
                curP[i] = fp[i] + (tp[i] - fp[i]) * f;
            }
            applyTabValues();
        });
        weightAnim.start();
    }

    private void applyTabValues() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView v = tabViews.get(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.weight = curW[i];
            lp.leftMargin = lp.rightMargin = Math.round(curM[i]);
            v.setPadding(Math.round(curP[i]), u(8), Math.round(curP[i]), u(8));
            v.setLayoutParams(lp);
        }
        if (weightAnim != null && weightAnim.isRunning()) movePill();
    }

    /** While the tabs slide, the highlight is placed from the same numbers the layout uses, so it never lags behind. */
    private void movePill() {
        if (tab < 0 || tab >= tabViews.size() || tabPill == null || pillW == 0) return;
        float sumW = 0, sumM = 0;
        for (int i = 0; i < curW.length; i++) {
            sumW += curW[i];
            sumM += 2 * curM[i];
        }
        float host = tabHost.getWidth() > 0 ? tabHost.getWidth() : u(1000);
        float avail = host - 2 * u(52) - sumM, x = u(52);
        for (int j = 0; j < tab; j++) x += 2 * curM[j] + avail * curW[j] / sumW;
        x += curM[tab];
        pillX = Math.round(x);
        pillW = Math.round(avail * curW[tab] / sumW);
        FrameLayout.LayoutParams l2 = (FrameLayout.LayoutParams) tabPill.getLayoutParams();
        l2.width = pillW;
        tabPill.setTranslationX(pillX);
        tabPill.setLayoutParams(l2);
    }

    private void buildInfo() {
        LinearLayout info = new LinearLayout(this);
        infoBlock = info;
        info.setOrientation(LinearLayout.VERTICAL);
        eyebrow = label("", 24, AMBER, true);
        eyebrow.setLetterSpacing(.16f);
        eyebrow.setSingleLine(true);
        eyebrow.setVisibility(View.GONE);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2);
        ep.bottomMargin = u(8);
        info.addView(eyebrow, ep);
        title =label("", 76, Color.WHITE, true);
        title.setSingleLine(true);
        info.addView(title);

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        chipView = label("", 26, Color.WHITE, true);
        GradientDrawable cd = new GradientDrawable();
        cd.setCornerRadius(u(99));
        cd.setColor(0x29FFFFFF);
        chipView.setBackground(cd);
        chipView.setPadding(u(18), u(4), u(18), u(4));
        meta.addView(chipView);
        when = label("", 30, 0xC7FFFFFF, false);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
        wp.leftMargin = u(22);
        meta.addView(when, wp);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = u(14);
        info.addView(meta, mp);

        sub = label("", 30, AMBER, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = u(16);
        info.addView(sub, sp);

        playPill = label("", 36, 0xFF17131F, true);
        playPill.setPadding(u(44), u(14), u(44), u(14));
        GradientDrawable pd = new GradientDrawable();
        pd.setCornerRadius(u(99));
        pd.setColor(AMBER);
        playPill.setBackground(pd);
        playPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.topMargin = u(34);
        info.addView(playPill, pp);

        infoLp = new FrameLayout.LayoutParams(u(1700), -2, Gravity.TOP | Gravity.START);
        stage.addView(info, infoLp);
        placeInfo(false);

    }

    /** Shows or hides the info text. */
    private void setInfoVisibility(int v) {
        infoBlock.setVisibility(v);
    }

    /** "45 min", "3 h 10 min". */
    private static String span(long ms) {
        long m = Math.max(1, ms / 60000);
        return m < 60 ? m + " min" : (m / 60) + " h" + (m % 60 == 0 ? "" : " " + (m % 60) + " min");
    }

    /** Long titles get a smaller size on Recent so they stay on a line or two. */
    private void fitTitle(String text) {
        boolean hero = shelf != null && shelf.mode() == Shelf.HERO;
        float k = hero ? Math.max(.62f, Math.min(1f, 24f / Math.max(1, text.length()))) : 1f;
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, (hero ? 84 : 52) * k * FONT * s);
    }

    /** The info block sits under the grid, or (Recent) beside the big first game. */
    private void placeInfo(boolean hero) {
        infoLp.leftMargin = u(hero ? 870 : EDGE_L);
        infoLp.topMargin = u(hero ? 150 : 866);
        infoLp.width = u(hero ? 980 : 1700);
        eyebrow.setVisibility(hero ? View.VISIBLE : View.GONE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, (hero ? 84 : 52) * FONT * s);
        title.setSingleLine(!hero);
        title.setMaxLines(hero ? 3 : 1);
        LinearLayout.LayoutParams mp = (LinearLayout.LayoutParams) ((View) chipView.getParent()).getLayoutParams();
        mp.topMargin = u(hero ? 18 : 6);
        infoBlock.requestLayout();
    }

    /** A small round arrow under Recent's big game, showing that there is more to the left or right. */
    private View chevron(String glyph, int x, int dir) {
        android.widget.ImageView v = new android.widget.ImageView(this);
        v.setImageBitmap(UiIcons.get(this, dir < 0 ? "arrow_left" : "arrow_right"));
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        v.setPadding(u(11), u(11), u(11), u(11)); // an image centres itself; the arrow sits in the middle of the disc
        GradientDrawable d = new GradientDrawable(); // a soft dark disc at the edge of the row, with no outline
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0x99000000);
        v.setBackground(d);
        v.setVisibility(View.GONE);
        v.setFocusable(false); // keys never land on it, so no focus highlight is drawn over it
        v.setDefaultFocusHighlightEnabled(false);
        v.setOnClickListener(x2 -> shelf.move(dir));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(u(58), u(58), Gravity.TOP | Gravity.START);
        p.leftMargin = u(x - 29);
        p.topMargin = u(801); // level with the middle of the row of small tiles
        stage.addView(v, p);
        return v;
    }

    /** Shows each arrow only while there is something in that direction. */
    private void updateChevrons() {
        if (chevL == null || shelf == null) return;
        boolean hero = shelf.mode() == Shelf.HERO && !pageOpen && !shown.isEmpty();
        int rowStart = Math.max(0, shelf.getIndex() - 1); // the row keeps the focused game one tile in from its left edge
        chevL.setVisibility(hero && rowStart > 0 ? View.VISIBLE : View.GONE);
        chevR.setVisibility(hero && rowStart + 5 < shelf.size() ? View.VISIBLE : View.GONE);
        dots.setVisibility(hero ? View.VISIBLE : View.GONE);
        dotsN = shelf.size();
        dotsAt = shelf.getIndex();
        dots.invalidate();
    }

    /** Moves the highlight to the active tab once the bar has been laid out with its new labels. */
    private void glidePill() {
        if (tabHost == null) return;
        tabHost.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                tabHost.getViewTreeObserver().removeOnPreDrawListener(this);
                if (weightAnim != null && weightAnim.isRunning()) return true; // the sliding tabs place the highlight themselves
                if (tab < 0 || tab >= tabViews.size()) return true;
                TextView t = tabViews.get(tab);
                if (t.getWidth() == 0) return true;
                final int toX = t.getLeft(), toW = t.getWidth(), toY = t.getTop(), toH = t.getHeight();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tabPill.getLayoutParams();
                lp.height = toH;
                tabPill.setTranslationY(toY);
                if (pillAnim != null) pillAnim.cancel();
                if (pillW == 0) { // first time: just put it there
                    pillX = toX;
                    pillW = toW;
                    lp.width = toW;
                    tabPill.setTranslationX(toX);
                    tabPill.setLayoutParams(lp);
                    return true;
                }
                final int fromX = pillX, fromW = pillW;
                pillAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
                pillAnim.setDuration(300);
                pillAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
                pillAnim.addUpdateListener(a -> {
                    float f = (Float) a.getAnimatedValue();
                    pillX = Math.round(fromX + (toX - fromX) * f);
                    pillW = Math.round(fromW + (toW - fromW) * f);
                    FrameLayout.LayoutParams l2 = (FrameLayout.LayoutParams) tabPill.getLayoutParams();
                    l2.width = pillW;
                    tabPill.setTranslationX(pillX);
                    tabPill.setLayoutParams(l2);
                });
                pillAnim.start();
                return true;
            }
        });
        tabBar.invalidate();
    }

    private void buildHints() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(u(EDGE_L), 0, u(EDGE_R), 0);
        banner = label("", 28, AMBER, true);
        banner.setVisibility(View.GONE); // no longer used: the Start hint says what is needed
        bottom.addView(banner);

        hintsRow = new LinearLayout(this);
        hintsRow.setGravity(Gravity.CENTER_VERTICAL);
        setHints(false);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, u(96));
        hl.gravity = Gravity.END;
        bottom.addView(hintsRow, hl);
        stage.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void hint(LinearLayout row, View glyph, String text) {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(glyph);
        TextView t = label(text, 28, 0xD1FFFFFF, false);
        t.setPadding(u(12), 0, 0, 0);
        h.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = u(44); // the row is right-aligned, so the spacing goes on the left
        row.addView(h, lp);
    }

    // ---- lifecycle ----

    @Override
    protected void onPause() {
        super.onPause();
        if (marquee != null) marquee.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (marquee != null) marquee.resume();
        ArtCache.clearShots();
        unveil(true);
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            batteryText = level >= 0 ? (level * 100 / scale) + "%" : "";
        }
        // When a cover finishes decoding, its colour may change the glow behind the focused game.
        ArtCache.setListener(g -> {
            int i = shelf == null ? -1 : shelf.getIndex();
            if (i >= 0 && i < shown.size() && shown.get(i) == g) onFocused(i);
        });

        // Reading the library touches the storage for every game, so it happens off the main thread;
        // the screen stays responsive and fills in when the list is ready.
        final int generation = ++loadGeneration;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            long t0 = System.nanoTime();
            final List<Game> fresh = Library.load(app);
            android.util.Log.i("Lantern", "library: " + fresh.size() + " games in " + (System.nanoTime() - t0) / 1000000 + " ms");
            runOnUiThread(() -> {
                if (generation != loadGeneration || isDestroyed()) return; // a newer load took over
                all.clear();
                all.addAll(fresh);
                if (tab < 0) tab = hasRecent() ? 0 : TAB_ALL;
                loaded = true;
                if (stage != null) buildUi(); // already sized: rebuild with the fresh library
                if (artOn) startArt();
            });
        }, "library").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (searchOpen) closeSearch();
        if (drawerOpen) closeDrawer();
        if (shelf != null) shelf.setIndex(0, true); // Home pressed while Lantern is showing: back to the first game
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean first = event.getRepeatCount() == 0;
        if (shelf == null) return super.onKeyDown(keyCode, event); // not built yet
        if (searchOpen) return searchKey(keyCode, event, first);
        if (drawerOpen) return drawerKey(keyCode, first);
        if (pageOpen) return pageKey(keyCode, first);
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) openSearch();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (first) jumpLetter(keyCode == KeyEvent.KEYCODE_BUTTON_R2 ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                shelf.move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                shelf.move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                shelf.moveRow(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (shelf.mode() == Shelf.HERO) {
                    if (first && shelf.size() > 0) openPage(); // Recent: A plays at once, Down slides up the game's page
                } else {
                    shelf.moveRow(1);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (first) setTab(tab - 1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (first) setTab(tab + 1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) activateShelf();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (first) removeRecent();
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (first) openDrawer();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (first) leaveSystem();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onBackPressed() {
        // A launcher has nowhere to go back to, except out of a game page or a system.
        if (searchOpen) closeSearch(); else if (drawerOpen) closeDrawer(); else if (pageOpen) closePage(); else leaveSystem();
    }

    /** A on the shelf: a system tile opens that system's games, a game tile opens its page. */
    private void activateShelf() {
        Game g = shelf.itemAt(shelf.getIndex());
        if (g == null) return;
        if (g.systemTile && "all".equals(g.sysId)) {
            setTab(TAB_ALL); // Recent's last tile
        } else if (g.systemTile) {
            filter = g.sysId;
            lastId = null;
            refresh(false);
            slideIn(0, 80); // into a system: its games rise in from below
        } else if (shelf.mode() == Shelf.HERO) {
            open(g, Library.resumeTime(g) > 0); // Recent: the big game is the selected one; one press and it is playing
        } else {
            openPage();
        }
    }

    /** B inside a search clears it; B inside a system goes back to the Systems shelf, with that system's tile selected. */
    private void leaveSystem() {
        if (tab == TAB_SEARCH) { // out of the Search tab, back to where the search began
            query = null;
            tab = tabBeforeSearch == TAB_SEARCH ? 0 : tabBeforeSearch;
            lastId = null;
            refresh(false);
            return;
        }
        if (filter == null) return;
        lastId = "sys:" + filter;
        filter = null;
        refresh(true);
        slideIn(0, -80); // back out: the systems come down from above
    }

    /**
     * At the end of a list the whole screen stretches a little the way you pushed and springs back, like a rubber
     * band. The tiles move the most; the text lags behind them, which gives it depth.
     */
    private void elastic(float dx, float dy) {
        View[] parts = {shelf, infoBlock, dots, chevL, chevR};
        float[] amount = {1.6f, .9f, 1.2f, 1.6f, 1.6f};
        for (int i = 0; i < parts.length; i++) {
            View v = parts[i];
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            v.animate().cancel();
            v.animate().translationX(-dx * amount[i]).translationY(-dy * amount[i]).setDuration(80) // pulled the way the list would have gone
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .withEndAction(() -> v.animate().translationX(0f).translationY(0f).setDuration(420)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(3.2f)).start())
                    .start();
        }
    }

    /** The shelf and the info text arrive from a direction (mockup pixels) and fade in, so a move reads as a move. */
    private void slideIn(float dx, float dy) {
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        for (View v : new View[]{shelf, infoBlock}) {
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationX(u(dx));
            v.setTranslationY(u(dy));
            v.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(280).setInterpolator(ease).start();
        }
    }

    // ---- shelves ----

    private boolean hasRecent() {
        for (Game g : all) if (listed(g) && g.lastPlayed > 0) return true;
        return false;
    }

    private void setTab(int t) {
        int old = tab;
        int next = (t + TABS.length) % TABS.length;
        if (next == TAB_SEARCH && tab != TAB_SEARCH) tabBeforeSearch = tab;
        tab = next;
        filter = null;
        query = null;
        lastId = null;
        refresh(false);
        slideIn(t > old ? 120 : -120, 0); // the next tab arrives from the side it sits on
        if (tab == TAB_SEARCH) openSearch(); // arriving on Search opens the keyboard
    }

    private void refresh(boolean keepFocus) {
        shown.clear();
        boolean searching = query != null;
        boolean systemTiles = tab == TAB_SYSTEMS && filter == null;
        boolean hero = !searching && tab == 0 && filter == null;
        if (tab == TAB_SEARCH) {
            if (searching) shown.addAll(matches(query));
        } else if (systemTiles) {
            shown.addAll(systemTiles());
        } else {
            for (Game g : all) {
                if (!listed(g)) continue;
                if (filter != null && !filter.equals(g.sysId)) continue;
                if (filter == null && tab == 0 && g.lastPlayed == 0) continue;
                shown.add(g);
            }
            if (filter == null && tab == 0) {
                Collections.sort(shown, (a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
                while (shown.size() > Shelf.HERO_COUNT) shown.remove(shown.size() - 1);
                if (!shown.isEmpty()) { // the last tile of Recent leads to everything
                    int total = 0;
                    for (Game g : all) if (listed(g)) total++;
                    Game more = new Game();
                    more.id = "sys:all";
                    more.systemTile = true;
                    more.sysId = "all";
                    more.title = "All games";
                    more.system = "";
                    more.count = total;
                    more.hue = 255;
                    shown.add(more);
                }
            }
        }

        int crumbAt = -1;
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            // A search is shown in the All games tab, since it looks through everything.
            boolean on = i == tab;
            String before = String.valueOf(t.getText());
            // The tab bar is the breadcrumb: inside a system, "Systems" becomes the system's name with a back arrow
            // and the number of games; a search becomes "back arrow, the words, the number of results".
            boolean back = on && ((i == TAB_SEARCH && searching) || (i == TAB_SYSTEMS && filter != null));
            if (back) crumbAt = i;
            if (back && searching) t.setText("\u201C" + query + "\u201D  \u00B7  " + shown.size());
            else if (back) t.setText(Library.systemTitle(filter));
            else t.setText(TABS[i]);
            if (back) {
                android.graphics.Bitmap arrow = UiIcons.get(this, "arrow_left");
                if (arrow != null) {
                    android.graphics.drawable.BitmapDrawable ad = new android.graphics.drawable.BitmapDrawable(getResources(), arrow);
                    ad.setBounds(0, 0, u(34), u(34));
                    t.setCompoundDrawables(ad, null, null, null);
                    t.setCompoundDrawablePadding(u(12));
                }
            } else {
                t.setCompoundDrawables(null, null, null, null);
            }
            t.setGravity(back ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER); // scrolling text starts at the left
            t.setEllipsize(back ? null : android.text.TextUtils.TruncateAt.END); // a long name scrolls instead of being cut (see startMarquee)
            if (!back && t == marqueeTab) stopMarquee();
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            float want = on ? 1f : 0.55f;
            t.setBackground(null); // the shared pill behind the tabs does the highlighting
            t.animate().cancel();
            if (!before.equals(String.valueOf(t.getText())) && !before.isEmpty()) {
                t.setAlpha(0f); // a changed label fades in
                t.animate().alpha(want).setDuration(220).start();
            } else {
                t.animate().alpha(want).setDuration(180).start();
            }
        }
        if (crumbAt >= 0) {
            final TextView ct = tabViews.get(crumbAt);
            stopMarquee();
            ct.postDelayed(() -> startMarquee(ct), 450); // after the tabs have settled into their new widths
        }
        int sysHue = filter == null ? -1 : Library.baseHue(filter); // inside a system the highlight takes its colour
        pillBg.setColor(sysHue >= 0 && tab == TAB_SYSTEMS ? (0x55000000 | (0xFFFFFF & Color.HSVToColor(new float[]{sysHue, .45f, 1f}))) : 0x2EFFFFFF);
        applyTabWeights(crumbAt);
        glidePill();

        // Work out which tile to come back to first: setItems announces item 0, which overwrites lastId.
        final String want = keepFocus ? lastId : null;
        int restore = 0;
        if (want != null) {
            for (int i = 0; i < shown.size(); i++) if (shown.get(i).id.equals(want)) { restore = i; break; }
        }
        placeInfo(hero);
        heroFocused = hero;
        heroAll = false;
        shelf.setItems(new ArrayList<>(shown), hero ? Shelf.HERO : systemTiles ? Shelf.SYSTEMS : Shelf.GRID);
        shelf.setIndex(restore, false);
        setHints(pageOpen);

        updateCrumb();
        updateChevrons();
        empty.setText(shown.isEmpty() ? emptyMessage() : "");
        if (shown.isEmpty()) {
            title.setText("");
            when.setText("");
            chipView.setVisibility(View.GONE);
            sub.setText("");
            playPill.setVisibility(View.GONE);
            glow.to(0xFF2A2438, 0xFF14121C);
            glow.art(null);
        }
    }

    private String emptyMessage() {
        if (tab == TAB_SEARCH && query == null) return "Search your library.\nPress X to open the keyboard.";
        if (query != null) return "No games match \u201c" + query + "\u201d.\nPress B to clear the search.";
        if (filter != null) return "No games here.";
        if (tab == 0) return "Nothing played yet.\nOpen a game and it will show up here.";
        return Library.hasFileAccess()
                ? "No games found.\nPut ROMs in Lantern/roms/<system> on the device,\nor install an Android game."
                : "No games found yet.";
    }

    /** One tile per system that has games, in a fixed order: consoles by name, Android last. */
    private List<Game> systemTiles() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Game g : all) if (listed(g)) counts.put(g.sysId, counts.containsKey(g.sysId) ? counts.get(g.sysId) + 1 : 1);
        List<String> ids = new ArrayList<>(counts.keySet());
        Collections.sort(ids, (a, b) -> {
            // Consoles first, by name; then Android games; Android apps last.
            int ra = Library.APPS.equals(a) ? 2 : Library.GAMES.equals(a) ? 1 : 0;
            int rb = Library.APPS.equals(b) ? 2 : Library.GAMES.equals(b) ? 1 : 0;
            if (ra != rb) return ra - rb;
            return Library.systemTitle(a).compareToIgnoreCase(Library.systemTitle(b));
        });
        List<Game> out = new ArrayList<>();
        for (String id : ids) {
            Game t = new Game();
            t.id = "sys:" + id;
            t.systemTile = true;
            t.sysId = id;
            t.title = Library.systemTitle(id);
            t.system = Library.systemName(id);
            t.count = counts.get(id);
            t.hue = (id.hashCode() & 0x7fffffff) % 360;
            out.add(t);
        }
        return out;
    }

    private void onFocused(int index) {
        if (index < 0 || index >= shown.size()) return;
        Game g = shown.get(index);
        lastId = g.id;
        updateChevrons();
        if (shelf.mode() == Shelf.HERO && g.systemTile != heroAll) {
            heroAll = g.systemTile;
            if (!pageOpen && !searchOpen && !drawerOpen) setHints(false);
        }
        if (g.systemTile) {
            title.setText(g.title);
            fitTitle(g.title);
            eyebrow.setText("BROWSE YOUR WHOLE LIBRARY");
            lastFocused = g;
            chipView.setVisibility(View.VISIBLE);
            chipView.setText((g.count + " " + Library.unit(g.sysId, g.count)).toUpperCase());
            when.setText("");
            sub.setText(g.sysId.equals("all") ? "Everything in your library"
                    : g.sysId.equals(Library.APPS) ? "Installed Android apps"
                    : g.sysId.equals(Library.GAMES) ? "Installed Android games"
                    : Cores.has(this, g.sysId) ? "Plays in Lantern's built-in player"
                    : "No emulator core for this system yet");
            glow.to(Color.HSVToColor(new float[]{g.hue, .60f, .50f}), Color.HSVToColor(new float[]{(g.hue + 35) % 360, .60f, .30f}));
            glow.art(null);
            compactInfo();
            boolean heroMode = shelf.mode() == Shelf.HERO;
            playPill.setVisibility(heroMode ? View.VISIBLE : View.GONE);
            playPill.setText("\u25A6  Browse all");
            return;
        }
        title.setText(g.title);
        fitTitle(g.title);
        chipView.setVisibility(View.VISIBLE);
        chipView.setText(g.system.toUpperCase());
        long total = g.isRom() ? Library.playTime(this, g.id) : 0;
        when.setText((g.lastPlayed == 0 ? "Never played" : "Played " + ago(g.lastPlayed)) + (total >= 60000 ? "  \u00B7  " + span(total) + " in total" : ""));
        long saved = Library.resumeTime(g);
        eyebrow.setText(index == 0 ? (saved > 0 ? "PICK UP WHERE YOU LEFT OFF" : "JUMP BACK IN") : "RECENTLY PLAYED");
        if (g.isRom()) {
            g.resume = saved > 0;
            shelf.redraw();
        }
        sub.setText(g.isRom() && !Cores.has(this, g.sysId) ? "No emulator core for " + g.system + " yet"
                : saved > 0 ? "Resume available · saved " + ago(saved) : "Ready to play");
        glow.to(Color.HSVToColor(new float[]{g.hue, .60f, .50f}), Color.HSVToColor(new float[]{(g.hue + 35) % 360, .60f, .30f}));
        glow.art(ArtCache.backdrop(g));
        compactInfo();
        if (shelf.mode() == Shelf.HERO) {
            if (g != lastFocused && !pageOpen && infoBlock.getVisibility() == View.VISIBLE && lastHeroIndex >= 0) {
                infoBlock.animate().cancel(); // the text slides a little the way you moved
                infoBlock.setTranslationX((index > lastHeroIndex ? 1 : -1) * u(50));
                infoBlock.animate().translationX(0f).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            }
            lastHeroIndex = index;
            if (g != lastFocused) { // the status line fades in on a new game
                sub.setAlpha(0f);
                sub.animate().alpha(1f).setStartDelay(120).setDuration(280).start();
            }
            boolean h = true; // the selected game is always the big one
            playPill.setVisibility(View.VISIBLE);
            playPill.setText(saved > 0 ? "\u25B6  Resume" : "\u25B6  Play");
            if (h != heroFocused) {
                heroFocused = h;
                if (!pageOpen && !searchOpen && !drawerOpen) setHints(false);
            }
        } else {
            playPill.setVisibility(View.GONE);
        }
        lastFocused = g;
    }

    /** Under a grid there is one line for the game, so the status joins the "played" text. */
    private void compactInfo() {
        if (shelf.mode() == Shelf.HERO) {
            sub.setVisibility(View.VISIBLE);
            return;
        }
        String a = String.valueOf(when.getText()), b = String.valueOf(sub.getText());
        when.setText(a.isEmpty() ? b : b.isEmpty() ? a : a + "  \u00B7  " + b);
        sub.setText("");
        sub.setVisibility(View.GONE);
    }

    // ---- actions ----

    /** Going into a game: the screen eases towards you and fades to black, then the game opens. Coming back, it fades up. */
    private void launch(Runnable go) {
        if (veil == null) {
            veil = new View(this);
            veil.setBackgroundColor(0xFF000000);
            veil.setAlpha(0f);
            veil.setVisibility(View.GONE);
            root.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        }
        veil.setVisibility(View.VISIBLE);
        boolean hero = shelf.mode() == Shelf.HERO && !pageOpen;
        stage.setPivotX(u(hero ? 450 : 960));
        stage.setPivotY(u(hero ? 368 : 540));
        stage.animate().scaleX(1.16f).scaleY(1.16f).setDuration(260).setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f)).start();
        veil.animate().alpha(1f).setDuration(260).withEndAction(go).start();
    }

    private void unveil(boolean fade) {
        if (veil == null || veil.getVisibility() != View.VISIBLE) return;
        stage.animate().cancel();
        stage.setScaleX(1f);
        stage.setScaleY(1f);
        veil.animate().cancel();
        if (!fade) {
            veil.setAlpha(0f);
            veil.setVisibility(View.GONE);
            return;
        }
        veil.animate().alpha(0f).setDuration(380).withEndAction(() -> veil.setVisibility(View.GONE)).start();
    }

    /** Y on Recent: takes the big game off the list (its saves stay; playing it again puts it back). */
    private void removeRecent() {
        if (shelf == null || shelf.mode() != Shelf.HERO || pageOpen || searchOpen || drawerOpen) return;
        int i = shelf.getIndex();
        if (i < 0 || i >= shown.size() || shown.get(i).systemTile) return;
        Game g = shown.get(i);
        Library.clearPlayed(this, g);
        refresh(false);
        shelf.setIndex(Math.min(i, Math.max(0, shown.size() - 1)), false);
        slideIn(90, 0); // the next game slides into the big spot
        Toast.makeText(this, "Removed \u201C" + g.title + "\u201D from Recent", Toast.LENGTH_SHORT).show();
    }

    /** Opens a game. For a ROM, resume picks up the saved resume point instead of starting fresh. */
    private void open(Game g, boolean resume) {
        if (g.isRom()) {
            java.io.File core = Cores.file(this, g.sysId);
            if (core == null) {
                Toast.makeText(this, "No emulator core for " + g.system + " yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            Library.markPlayed(this, g);
            launch(() -> startActivity(new Intent(this, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_CORE, core.getAbsolutePath())
                    .putExtra(PlayerActivity.EXTRA_ROM, g.file.getAbsolutePath())
                    .putExtra(PlayerActivity.EXTRA_SYS, Library.biosDir().getAbsolutePath())
                    .putExtra(PlayerActivity.EXTRA_SAVE, Library.saveDir(g.sysId).getAbsolutePath())
                    .putExtra(PlayerActivity.EXTRA_START, resume ? 0 : -1)
                    .putExtra(PlayerActivity.EXTRA_TITLE, g.title)
                    .putExtra(PlayerActivity.EXTRA_SYSID, g.sysId)));
            return;
        }
        try {
            Library.markPlayed(this, g);
            launch(() -> {
                try {
                    startActivity(g.launch);
                } catch (RuntimeException e) {
                    unveil(false);
                }
            });
        } catch (RuntimeException e) {
            // App was removed or is not launchable; the next resume rebuilds the shelf.
        }
    }

    /** Start turns cover art fetching on or off. Off by default: it is the only thing here that uses the network. */
    private void toggleArt() {
        artOn = !artOn;
        getSharedPreferences("lantern", MODE_PRIVATE).edit().putBoolean("art", artOn).apply();
        Toast.makeText(this, artOn ? "Fetching cover art from " + ArtFetcher.baseUrl() : "Cover art fetching is off",
                Toast.LENGTH_LONG).show();
        setHints(pageOpen);
        if (artOn) startArt(); else fetcher.cancel();
    }

    private void startArt() {
        fetcher.fetch(all, g -> {
            if (shelf != null) shelf.redraw();
        });
    }

    private void askForFileAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ---- hints ----

    private void setHints(boolean page) {
        hintsRow.removeAllViews();
        if (drawerOpen) {
            hint(hintsRow, glyph("▲ ▼", Color.WHITE, true), "Choose");
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), "Select");
            hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Close");
        } else if (searchOpen) {
            hint(hintsRow, glyph("◀ ▶ ▲ ▼", Color.WHITE, true), "Choose");
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), "Type");
            hint(hintsRow, glyph("Y", 0xFFFFD36E, true), "Delete");
            hint(hintsRow, glyph("X", 0xFF8FC7FF, true), "Show results");
            hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Cancel");
        } else if (page) {
            hint(hintsRow, glyph("▲ ▼", Color.WHITE, true), "Choose");
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), "Confirm");
            hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Back");
        } else {
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), shelf != null && shelf.mode() == Shelf.HERO && !heroAll ? "Play" : "Open");
            if (shelf != null && shelf.mode() == Shelf.HERO && !heroAll) hint(hintsRow, glyph("▼", Color.WHITE, true), "Details");
            if (shelf != null && shelf.mode() == Shelf.HERO && !heroAll) hint(hintsRow, glyph("Y", 0xFFFFD36E, true), "Remove");
            hint(hintsRow, glyph("X", 0xFF8FC7FF, true), "Search");
            if (filter != null || tab == TAB_SEARCH) hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Back");
            // Select opens the drawer: settings, file access and the Android apps list.
            hint(hintsRow, glyph("Select", Color.WHITE, false), Library.hasFileAccess() ? "Menu" : "Menu (file access)");
        }
    }

    /** Android apps are only listed when the drawer's switch is on, or while their own list is open. */
    private boolean listed(Game g) {
        if (!Library.APPS.equals(g.sysId)) return true;
        return showApps || Library.APPS.equals(filter);
    }

    // ---- drawer (Select) ----

    private static final int D_APPS = 0, D_SHOWAPPS = 1, D_ART = 2, D_FILES = 3, D_CLOCK = 4;

    private List<Integer> drawerItems() {
        List<Integer> l = new ArrayList<>();
        l.add(D_APPS);
        l.add(D_SHOWAPPS);
        l.add(D_ART);
        l.add(D_CLOCK);
        if (!Library.hasFileAccess()) l.add(D_FILES);
        return l;
    }

    private void openDrawer() {
        drawerOpen = true;
        drawerIdx = 0;
        renderDrawer();
        drawerView.setVisibility(View.VISIBLE);
        drawerView.setAlpha(0f);
        drawerView.animate().alpha(1f).setDuration(150).start();
        // The Select button is on the left, so the menu comes from the left and pushes the screen a little to the right.
        View panel = drawerView.getChildAt(drawerView.getChildCount() - 1);
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        panel.setTranslationX(-u(700));
        panel.animate().translationX(0f).setDuration(280).setInterpolator(ease).start();
        shelf.animate().translationX(u(80)).setDuration(280).setInterpolator(ease).start();
        infoBlock.animate().translationX(u(80)).setDuration(280).setInterpolator(ease).start();
        setHints(false);
    }

    private void closeDrawer() {
        drawerOpen = false;
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        shelf.animate().translationX(0f).setDuration(240).setInterpolator(ease).start();
        infoBlock.animate().translationX(0f).setDuration(240).setInterpolator(ease).start();
        View panel = drawerView.getChildAt(drawerView.getChildCount() - 1);
        if (panel != null && drawerView.getVisibility() == View.VISIBLE) {
            panel.animate().translationX(-u(700)).setDuration(220).setInterpolator(ease).withEndAction(() -> {
                if (drawerOpen) return;
                drawerView.setVisibility(View.GONE);
                drawerView.removeAllViews();
            }).start();
            drawerView.animate().alpha(0f).setDuration(220).start();
        } else {
            drawerView.setVisibility(View.GONE);
            drawerView.removeAllViews();
        }
        setHints(pageOpen);
    }

    private void drawerDo(int item) {
        switch (item) {
            case D_APPS:
                closeDrawer();
                filter = Library.APPS;
                query = null;
                tab = TAB_SYSTEMS;
                lastId = null;
                refresh(false);
                break;
            case D_SHOWAPPS:
                showApps = !showApps;
                getSharedPreferences("lantern", MODE_PRIVATE).edit().putBoolean("showApps", showApps).apply();
                refresh(true); // the shelf behind the drawer follows the switch
                renderDrawer();
                break;
            case D_ART:
                toggleArt();
                renderDrawer();
                break;
            case D_CLOCK:
                clock24 = !clock24;
                getSharedPreferences("lantern", MODE_PRIVATE).edit().putBoolean("clock24", clock24).apply();
                applyClock();
                renderDrawer();
                break;
            case D_FILES:
                closeDrawer();
                askForFileAccess();
                break;
            default:
        }
    }

    private boolean drawerKey(int keyCode, boolean first) {
        List<Integer> items = drawerItems();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (drawerIdx > 0) { drawerIdx--; renderDrawer(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (drawerIdx < items.size() - 1) { drawerIdx++; renderDrawer(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (first && items.get(drawerIdx) != D_APPS && items.get(drawerIdx) != D_FILES) drawerDo(items.get(drawerIdx));
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) drawerDo(items.get(drawerIdx));
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (first) closeDrawer();
                return true;
            default:
                return true;
        }
    }

    private void renderDrawer() {
        drawerView.removeAllViews();
        View scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(x -> closeDrawer());
        drawerView.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true); // taps on the panel are not taps on the scrim
        panel.setPadding(u(EDGE_L), u(70), u(48), u(40));
        GradientDrawable pd = new GradientDrawable();
        pd.setColor(0xF2141119);
        pd.setCornerRadii(new float[]{0, 0, u(28), u(28), u(28), u(28), 0, 0});
        panel.setBackground(pd);
        panel.addView(label("Menu", 52, Color.WHITE, true));

        int apps = 0;
        for (Game g : all) if (Library.APPS.equals(g.sysId)) apps++;
        List<Integer> items = drawerItems();
        for (int i = 0; i < items.size(); i++) {
            int item = items.get(i);
            boolean on = i == drawerIdx;
            String name, value;
            switch (item) {
                case D_APPS: name = "Android apps"; value = apps + "  \u203A"; break;
                case D_SHOWAPPS: name = "Show apps on every shelf"; value = showApps ? "On" : "Off"; break;
                case D_ART: name = "Cover art"; value = artOn ? "On" : "Off"; break;
                case D_CLOCK: name = "Clock"; value = clock24 ? "24 hour" : "12 hour"; break;
                default: name = "Allow file access"; value = "\u203A";
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(u(28), 0, u(28), 0);
            GradientDrawable rd = new GradientDrawable();
            rd.setCornerRadius(u(16));
            rd.setColor(on ? Color.WHITE : 0x14FFFFFF);
            row.setBackground(rd);
            int ink = on ? 0xFF17131F : Color.WHITE;
            TextView nm = label(name, 30, ink, true);
            nm.setSingleLine(true);
            row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
            boolean toggle = item == D_SHOWAPPS || item == D_ART;
            TextView val = label(value, 28, toggle && value.equals("On") ? (on ? 0xFF1F7A3A : 0xFF8BE28F) : on ? 0xBF17131F : 0xB3FFFFFF, toggle);
            row.addView(val);
            final int idx = i;
            row.setOnClickListener(x -> { drawerIdx = idx; drawerDo(item); });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, u(88));
            rp.topMargin = u(i == 0 ? 36 : 14);
            panel.addView(row, rp);
        }
        TextView note = label("Apps are hidden from the shelves unless the switch is on. Open the list here any time.", 24, 0x99FFFFFF, false);
        note.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = u(30);
        panel.addView(note, np);
        drawerView.addView(panel, new FrameLayout.LayoutParams(u(700), -1, Gravity.START));
    }

    // ---- letter jumping ----

    /** The letter a title files under; digits and symbols share "#". */
    private static char initial(Game g) {
        String n = g.title;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isLetter(c)) return Character.toUpperCase(c);
            if (Character.isDigit(c)) return '#';
        }
        return '#';
    }

    /** R2 jumps to the first game of the next letter, L2 to the start of this letter, then of the previous one. */
    private void jumpLetter(int dir) {
        if (tab == 0 && filter == null && query == null) return; // Recent is in play order, not alphabetical
        int n = shown.size(), cur = shelf.getIndex();
        if (n < 2 || cur < 0 || cur >= n) return;
        char c = initial(shown.get(cur));
        int target;
        if (dir > 0) {
            target = 0; // wraps to the top after the last letter
            for (int i = cur + 1; i < n; i++) if (initial(shown.get(i)) != c) { target = i; break; }
        } else {
            int start = cur;
            while (start > 0 && initial(shown.get(start - 1)) == c) start--;
            if (start < cur) {
                target = start;
            } else {
                int last = start == 0 ? n - 1 : start - 1; // wraps to the last letter before the first
                char pc = initial(shown.get(last));
                target = last;
                while (target > 0 && initial(shown.get(target - 1)) == pc) target--;
            }
        }
        shelf.setIndex(target, true);
        letterBadge.setText(String.valueOf(initial(shown.get(target))));
        letterBadge.animate().cancel();
        letterBadge.setAlpha(1f);
        letterBadge.animate().alpha(0f).setStartDelay(700).setDuration(300).start();
    }

    // ---- search and breadcrumbs ----

    /** Games whose title or system contains every word of the query, in title order. */
    private List<Game> matches(String q) {
        String[] words = q.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
        List<Game> out = new ArrayList<>();
        for (Game g : all) {
            if (!listed(g)) continue;
            String hay = (g.title + " " + g.system).toLowerCase(java.util.Locale.ROOT);
            boolean ok = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { ok = false; break; }
            if (ok) out.add(g);
        }
        return out;
    }

    /** "Systems > Game Boy Advance . 214 games" inside a system, "Search > "mario" . 12 games" after a search. */
    private void updateCrumb() {
        crumb.setVisibility(View.GONE); // the tab bar now carries the breadcrumb
    }

    private void openSearch() {
        if (tab != TAB_SEARCH) { // X from anywhere: go to the Search tab first, then type
            tabBeforeSearch = tab;
            tab = TAB_SEARCH;
            filter = null;
            query = null;
            lastId = null;
            refresh(false);
        }
        searchOpen = true;
        empty.setVisibility(View.GONE); // the keyboard has the screen; the empty-search hint would show through its dim layer
        draft = query == null ? "" : query;
        kbRow = 1; // starts on Q
        kbCol = 0;
        renderSearch();
        searchView.setVisibility(View.VISIBLE);
        searchView.setAlpha(0f);
        searchView.animate().alpha(1f).setDuration(150).start();
        // The keyboard rises from below and the results come in from the right, where they sit.
        android.view.animation.DecelerateInterpolator easeS = new android.view.animation.DecelerateInterpolator(2f);
        for (int ci = 1; ci < searchView.getChildCount(); ci++) {
            View cv = searchView.getChildAt(ci);
            boolean results = ((FrameLayout.LayoutParams) cv.getLayoutParams()).leftMargin == u(1210);
            cv.setAlpha(0f);
            if (results) cv.setTranslationX(u(140)); else cv.setTranslationY(u(160));
            cv.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(Math.min(ci, 30) * 6L).setDuration(300).setInterpolator(easeS).start();
        }
        setHints(false);
    }

    private void closeSearch() {
        searchOpen = false;
        empty.setVisibility(View.VISIBLE);
        searchView.setVisibility(View.GONE);
        searchView.removeAllViews();
        setHints(pageOpen);
    }

    /** Shows the draft's matches on the shelf (an empty draft clears the search). */
    private void applySearch() {
        String q = draft.trim();
        query = q.isEmpty() ? null : q;
        if (query != null) filter = null;
        closeSearch();
        lastId = null;
        refresh(false);
    }

    private void typeChar(String c) {
        if (draft.length() < 40) draft += c;
        renderSearch();
    }

    private void backspace() {
        if (!draft.isEmpty()) draft = draft.substring(0, draft.length() - 1);
        renderSearch();
    }

    private void pressKey(String k) {
        if (k.equals("OK")) applySearch();
        else if (k.equals("Del")) backspace();
        else typeChar(k.equals("Space") ? " " : k);
    }

    /** Touch: a tapped result closes the search with those matches on the shelf and that game focused. */
    private void pickResult(Game g) {
        String q = draft.trim();
        query = q.isEmpty() ? null : q;
        if (query != null) filter = null;
        closeSearch();
        lastId = g.id;
        refresh(true);
    }

    private boolean searchKey(int keyCode, KeyEvent event, boolean first) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                kbCol = (kbCol + KEYS[kbRow].length - 1) % KEYS[kbRow].length;
                renderSearch();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                kbCol = (kbCol + 1) % KEYS[kbRow].length;
                renderSearch();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                kbRow = (kbRow + KEYS.length - 1) % KEYS.length;
                kbCol = Math.min(kbCol, KEYS[kbRow].length - 1);
                renderSearch();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                kbRow = (kbRow + 1) % KEYS.length;
                kbCol = Math.min(kbCol, KEYS[kbRow].length - 1);
                renderSearch();
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!first) return true;
                pressKey(KEYS[kbRow][kbCol]);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_DEL:
                backspace();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) applySearch();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (first) {
                    int dirT = keyCode == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1;
                    closeSearch();
                    setTab(tab + dirT);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                if (first) closeSearch();
                return true;
            default:
                // A real keyboard works too.
                int ch = event.getUnicodeChar();
                if (ch > 0 && (Character.isLetterOrDigit(ch) || ch == ' ')) {
                    typeChar(String.valueOf((char) Character.toLowerCase(ch)));
                    return true;
                }
                return true;
        }
    }

    private void renderSearch() {
        searchView.removeAllViews();
        View dim = new View(this);
        dim.setBackgroundColor(0xF20B0A10);
        dim.setClickable(true); // swallows touches so the shelf underneath is not tapped
        FrameLayout.LayoutParams dimp = new FrameLayout.LayoutParams(-1, -1);
        dimp.topMargin = u(104); // the tab bar stays above the keyboard: L1 and R1 still move between tabs
        searchView.addView(dim, dimp);

        TextView head = label("Search", 56, Color.WHITE, true);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hp.leftMargin = u(EDGE_L);
        hp.topMargin = u(110);
        searchView.addView(head, hp);

        TextView box = label(draft.isEmpty() ? "Type a game name" : draft + "\u2502", 44, draft.isEmpty() ? 0x66FFFFFF : Color.WHITE, false);
        box.setSingleLine(true);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(u(28), 0, u(28), 0);
        GradientDrawable bd = new GradientDrawable();
        bd.setCornerRadius(u(18));
        bd.setColor(0x1FFFFFFF);
        bd.setStroke(u(3), AMBER);
        box.setBackground(bd);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(u(1040), u(96), Gravity.TOP | Gravity.START);
        bp.leftMargin = u(EDGE_L);
        bp.topMargin = u(215);
        searchView.addView(box, bp);

        // The letters: a grid chosen with the D-pad.
        int cell = 92, gap = 10;
        for (int r = 0; r < KEYS.length; r++) {
            for (int c = 0; c < KEYS[r].length; c++) {
                String k = KEYS[r][c];
                boolean on = r == kbRow && c == kbCol;
                boolean word = k.length() > 1;
                TextView v = label(word ? k : k.toUpperCase(), word ? 26 : 38, on ? 0xFF17131F : Color.WHITE, true);
                v.setGravity(Gravity.CENTER);
                GradientDrawable kd = new GradientDrawable();
                kd.setCornerRadius(u(16));
                kd.setColor(on ? Color.WHITE : k.equals("OK") ? 0x40FFB454 : 0x1AFFFFFF);
                v.setBackground(kd);
                FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(u(cell), u(cell - 8), Gravity.TOP | Gravity.START);
                kp.leftMargin = u(EDGE_L + (r == 2 ? (cell + gap) / 2 : 0) + c * (cell + gap)); // the home row is indented, as on a keyboard
                kp.topMargin = u(350 + r * (cell - 8 + gap));
                final int fr = r, fc = c;
                v.setOnClickListener(x -> {
                    kbRow = fr;
                    kbCol = fc;
                    pressKey(k);
                });
                searchView.addView(v, kp);
            }
        }

        // Touch: close, and show the results.
        TextView cancel = label("Cancel", 28, Color.WHITE, true);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cdr = new GradientDrawable();
        cdr.setCornerRadius(u(99));
        cdr.setColor(0x24FFFFFF);
        cancel.setBackground(cdr);
        cancel.setOnClickListener(x -> closeSearch());
        FrameLayout.LayoutParams cap = new FrameLayout.LayoutParams(u(170), u(64), Gravity.TOP | Gravity.START);
        cap.leftMargin = u(EDGE_L + 870);
        cap.topMargin = u(120);
        searchView.addView(cancel, cap);

        // Live matches on the right.
        List<Game> hits = draft.trim().isEmpty() ? new ArrayList<Game>() : matches(draft);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        String sum = draft.trim().isEmpty() ? "Pick letters, then press X to see the games"
                : hits.size() + (hits.size() == 1 ? " game" : " games");
        TextView sumView = label(sum, 28, AMBER, true);
        if (!hits.isEmpty()) {
            sumView.setText(sum + "  \u2014  tap to show all");
            sumView.setOnClickListener(x -> applySearch());
        }
        col.addView(sumView);
        for (int i = 0; i < Math.min(hits.size(), 8); i++) {
            Game g = hits.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(g.title, 30, Color.WHITE, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(label(g.system, 22, 0x99FFFFFF, false));
            row.setPadding(0, u(6), 0, u(6));
            row.setOnClickListener(x -> pickResult(g));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = u(12);
            col.addView(row, rp);
        }
        if (hits.size() > 8) {
            TextView more = label("and " + (hits.size() - 8) + " more", 24, 0x99FFFFFF, false);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
            mp.topMargin = u(18);
            col.addView(more, mp);
        }
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(u(600), -2, Gravity.TOP | Gravity.START);
        cp.leftMargin = u(1210);
        cp.topMargin = u(232);
        searchView.addView(col, cp);
    }

    // ---- game page ----

    /** One row in the page's action list. */
    private static final class Act {
        final String icon, label, hint;

        Act(String icon, String label, String hint) {
            this.icon = icon;
            this.label = label;
            this.hint = hint;
        }
    }

    private List<Act> actionsFor(Game g) {
        List<Act> a = new ArrayList<>();
        long saved = Library.resumeTime(g);
        if (saved > 0) a.add(new Act("⟲", "Resume", "Saved " + ago(saved)));
        a.add(new Act("▶", saved > 0 ? "Play from the start" : "Play", g.isRom() ? (Cores.has(this, g.sysId) ? "Built-in player" : "No core yet") : "Android app"));
        if (!g.isRom()) a.add(new Act("⚙", "App settings", "Android settings"));
        return a;
    }

    private void openPage() {
        int i = shelf.getIndex();
        if (i < 0 || i >= shown.size()) return;
        if (shown.get(i).systemTile) return;
        pageOpen = true;
        infoOpen = false;
        if (chevL != null) { chevL.setVisibility(View.GONE); chevR.setVisibility(View.GONE); dots.setVisibility(View.GONE); }
        pageChrome(true);
        actIdx = 0;
        renderPage();
        pageView.setVisibility(View.VISIBLE);
        boolean fromHero = shelf.mode() == Shelf.HERO;
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        // The cover starts where it was (Recent's big tile, or the grid tile) and moves to its place on the page,
        // centred on the screen.
        placeCoverAtSource();
        pageCover.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(PAGE_MS).setInterpolator(ease).start();
        pageView.setAlpha(1f);
        pageView.setTranslationY(0f);
        if (fromHero) {
            // The line above the title and the title start where they are on Recent and move down with the cover.
            pageCol.setTranslationY(u(150) - pageColTop);
            pageCol.animate().translationY(0f).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease).start();
            // Down moves the screen down: the row under the big tile slides away, and the details drop in from above.
            pageRest.setAlpha(0f);
            pageRest.setTranslationY(-u(70));
            pageRest.animate().alpha(1f).translationY(0f).setStartDelay(110).setDuration(340).setInterpolator(ease).start();
            shelf.setPage(0f, true);
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(PAGE_MS);
            va.setInterpolator(ease);
            va.addUpdateListener(a -> shelf.setPage((Float) a.getAnimatedValue(), true));
            va.start();
        } else {
            pageCol.setAlpha(0f);
            pageCol.animate().alpha(1f).setStartDelay(100).setDuration(PAGE_MS).setInterpolator(ease).start();
            shelf.animate().cancel();
            shelf.animate().alpha(0f).setDuration(PAGE_MS).withEndAction(() -> {
                if (pageOpen) shelf.setVisibility(View.INVISIBLE);
            }).start();
        }
        setInfoVisibility(View.INVISIBLE);
        setHints(true);
    }

    /** With the details open the tab bar fades up and away and a wide chevron takes its place; closing reverses it. */
    private void pageChrome(boolean open) {
        // The tab bar and the chevron are one crossfade: same start, same length, same easing.
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        tabHost.animate().cancel();
        upChev.animate().cancel();
        if (open) {
            tabHost.animate().alpha(0f).translationY(-u(44)).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease).start();
            upChev.setVisibility(View.VISIBLE);
            upChev.setAlpha(0f);
            upChev.setTranslationY(u(26));
            upChev.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease).start();
        } else {
            tabHost.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease).start();
            upChev.animate().alpha(0f).translationY(u(26)).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease)
                    .withEndAction(() -> { if (!pageOpen) upChev.setVisibility(View.GONE); }).start();
        }
    }

    /** Where the page's cover came from: Recent's big tile, or the tile in the grid. */
    private float[] sourceRect() {
        if (shelf.mode() == Shelf.HERO) return Shelf.heroBox(null);
        float[] r = shelf.tileRect(shelf.getIndex());
        return r != null ? r : Shelf.heroBox(null);
    }

    private void placeCoverAtSource() {
        float[] src = sourceRect(), pb = Shelf.pageBox();
        pageCover.setPivotX(0f);
        pageCover.setPivotY(0f);
        pageCover.setScaleX(src[2] / pb[2]);
        pageCover.setScaleY(src[3] / pb[3]);
        pageCover.setTranslationX(u(src[0] - pb[0]));
        pageCover.setTranslationY(u(src[1] - pb[1]));
    }

    private void closePage() {
        pageOpen = false;
        pageChrome(false);
        updateChevrons();
        if (pageCover == null) {
            pageView.setVisibility(View.GONE);
            pageView.removeAllViews();
            shelf.setVisibility(View.VISIBLE);
            setInfoVisibility(View.VISIBLE);
            setHints(false);
            shelf.redraw();
            return;
        }
        final boolean hero = shelf.mode() == Shelf.HERO;
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        // The cover goes back to where it came from, and the page is taken down once it has landed.
        float[] src = sourceRect(), pb = Shelf.pageBox();
        pageCover.setPivotX(0f);
        pageCover.setPivotY(0f);
        pageCover.animate().scaleX(src[2] / pb[2]).scaleY(src[3] / pb[3]).translationX(u(src[0] - pb[0])).translationY(u(src[1] - pb[1]))
                .setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease).withEndAction(() -> {
                    if (pageOpen) return; // opened again meanwhile
                    pageView.setVisibility(View.GONE);
                    pageView.removeAllViews();
                    if (hero) {
                        // The page's copy and the real tile and title are at the same place now: swap in the same frame.
                        shelf.setPage(0f, false);
                        infoBlock.animate().cancel();
                        infoBlock.setAlpha(1f);
                        setInfoVisibility(View.VISIBLE);
                        for (int ci = 2; ci < infoBlock.getChildCount(); ci++) {
                            View cv = infoBlock.getChildAt(ci);
                            cv.setAlpha(0f);
                            cv.animate().alpha(1f).setStartDelay(0).setDuration(260).start();
                        }
                    }
                }).start();
        shelf.animate().cancel();
        shelf.setVisibility(View.VISIBLE);
        if (hero) {
            // Up moves the screen back up: the details lift away, the row slides back in from below, and the info returns.
            // The big tile and the real title stay hidden while the page's cover and title travel back (so nothing
            // shows twice); both come back in the frame the page goes.
            shelf.setAlpha(1f);
            shelf.setPage(1f, true);
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(1f, 0f);
            va.setDuration(PAGE_MS);
            va.setInterpolator(ease);
            va.addUpdateListener(a -> { if (!pageOpen) shelf.setPage((Float) a.getAnimatedValue(), (Float) a.getAnimatedValue() > 0f); });
            va.start();
            pageRest.animate().alpha(0f).translationY(-u(70)).setStartDelay(0).setDuration(240).setInterpolator(ease).start();
            pageCol.animate().translationY(u(150) - pageColTop).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(ease).start();
        } else {
            shelf.setAlpha(0f);
            shelf.animate().alpha(1f).setStartDelay(PAGE_MS / 2).setDuration(PAGE_MS / 2).start(); // under the landing cover
            pageCol.animate().alpha(0f).setStartDelay(0).setDuration(PAGE_MS / 2).start();
            infoBlock.animate().cancel();
            infoBlock.setAlpha(0f);
            setInfoVisibility(View.VISIBLE);
            infoBlock.animate().alpha(1f).setStartDelay(120).setDuration(260).start();
        }
        setHints(false);
        shelf.redraw();
    }

    private void renderPage() {
        Game g = shown.get(shelf.getIndex());
        pageView.removeAllViews();

        Cover big = new Cover(this, s);
        big.setGame(g);
        big.setWeight(1f);
        float[] hb = Shelf.pageBox(); // the same size as Recent's big cover, centred on the screen
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(u(hb[2]), u(hb[3]), Gravity.TOP | Gravity.START);
        cp.leftMargin = u(hb[0]);
        cp.topMargin = u(hb[1]);
        pageView.addView(big, cp);
        pageCover = big;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // The line above the title and the title are the same size and in the same place as on Recent, so they
        // do not move when the page opens; only what is below them slides in.
        if (shelf.mode() == Shelf.HERO) {
            TextView eb = label(String.valueOf(eyebrow.getText()), 24, AMBER, true);
            eb.setLetterSpacing(.16f);
            eb.setSingleLine(true);
            LinearLayout.LayoutParams ebp = new LinearLayout.LayoutParams(-2, -2);
            ebp.bottomMargin = u(8);
            col.addView(eb, ebp);
        }
        float tk = shelf.mode() == Shelf.HERO ? Math.max(.62f, Math.min(1f, 24f / Math.max(1, g.title.length()))) : 1f;
        TextView t = label(g.title, 84 * tk, Color.WHITE, true);
        t.setMaxLines(3);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);

        LinearLayout rest = new LinearLayout(this);
        rest.setOrientation(LinearLayout.VERTICAL);
        col.addView(rest, new LinearLayout.LayoutParams(-1, -2));
        pageRest = rest;

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView chip = label(g.system.toUpperCase(), 26, Color.WHITE, true);
        GradientDrawable cd = new GradientDrawable();
        cd.setCornerRadius(u(99));
        cd.setColor(0x29FFFFFF);
        chip.setBackground(cd);
        chip.setPadding(u(18), u(4), u(18), u(4));
        meta.addView(chip);
        TextView when = label(g.lastPlayed == 0 ? "Never played" : "Played " + ago(g.lastPlayed), 30, 0xC7FFFFFF, false);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
        wp.leftMargin = u(22);
        meta.addView(when, wp);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = u(14);
        rest.addView(meta, mp);

        TextView blurb = label(infoOpen ? details(g) : summary(g), infoOpen ? 22 : 30, 0xBFFFFFFF, false);
        blurb.setLineSpacing(0, infoOpen ? 1.15f : 1.25f);
        blurb.setMaxLines(infoOpen ? 6 : 4);
        blurb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(u(infoOpen ? 1100 : 900), -2);
        bp.topMargin = u(22);
        bp.bottomMargin = u(30);
        rest.addView(blurb, bp);

        List<Act> acts = actionsFor(g);
        actIdx = Math.max(0, Math.min(actIdx, acts.size() - 1));
        for (int i = 0; i < acts.size(); i++) {
            Act a = acts.get(i);
            boolean on = i == actIdx;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(u(30), 0, u(30), 0);
            GradientDrawable rd = new GradientDrawable();
            rd.setCornerRadius(u(16));
            rd.setColor(on ? Color.WHITE : 0x14FFFFFF);
            row.setBackground(rd);
            if (on) row.setTranslationX(u(14));
            int ink = on ? 0xFF17131F : Color.WHITE;
            TextView ic = label(a.icon, 30, ink, true);
            ic.setGravity(Gravity.CENTER);
            row.addView(ic, new LinearLayout.LayoutParams(u(46), -2));
            row.addView(label(a.label, 34, ink, true));
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(label(a.hint, 24, on ? 0xBF17131F : 0xB3FFFFFF, false));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(u(760), u(82));
            rp.bottomMargin = u(14);
            rest.addView(row, rp);
        }

        FrameLayout.LayoutParams colp = new FrameLayout.LayoutParams(u(980), -2, Gravity.TOP | Gravity.START);
        colp.leftMargin = u(870);
        col.measure(View.MeasureSpec.makeMeasureSpec(u(980), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        pageColTop = Math.max(u(120), (u(1080) - col.getMeasuredHeight()) / 2); // centred on the screen, like the cover
        colp.topMargin = pageColTop;
        pageView.addView(col, colp);
        pageCol = col;
    }

    private boolean pageKey(int keyCode, boolean first) {
        List<Act> acts = actionsFor(shown.get(shelf.getIndex()));
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (actIdx > 0) { actIdx--; renderPage(); }
                else if (first && shelf.mode() == Shelf.HERO) closePage(); // top of the menu: back up to Recent
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (actIdx < acts.size() - 1) { actIdx++; renderPage(); }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (first) closePage();
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) activate(shown.get(shelf.getIndex()), acts.get(actIdx));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_L1: // the details are for this game only; the tabs and the list are not reachable from here
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_START:
                return true;
            default:
                return false;
        }
    }

    private void activate(Game g, Act a) {
        if (a.label.equals("Resume")) {
            open(g, true);
        } else if (a.label.equals("Play") || a.label.equals("Play from the start")) {
            open(g, false);
        } else if (a.label.endsWith("details")  || a.label.equals("Details")) {
            infoOpen = !infoOpen;
            renderPage();
        } else if (a.label.equals("App settings")) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + g.pkg)));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Settings could not be opened.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String summary(Game g) {
        if (g.isRom()) {
            return "A " + g.system + " game from your ROM folder. "
                    + (Cores.has(this, g.sysId) ? "It opens in Lantern's own player."
                    : "No emulator core is bundled for this system yet.");
        }
        return "An installed Android app. It opens as it normally does, and Home brings you back here.";
    }

    private String details(Game g) {
        StringBuilder b = new StringBuilder();
        if (g.isRom()) {
            b.append("File    ").append(g.file.getName()).append('\n');
            b.append("Folder  ").append(g.file.getParent()).append('\n');
            b.append("Size    ").append(size(g.file.length())).append('\n');
            b.append("Added   ").append(java.text.DateFormat.getDateInstance().format(new java.util.Date(g.file.lastModified())));
        } else {
            b.append("Package  ").append(g.pkg).append('\n');
            try {
                b.append("Version  ").append(getPackageManager().getPackageInfo(g.pkg, 0).versionName);
            } catch (Exception e) {
                b.append("Version  unknown");
            }
        }
        return b.toString();
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    // ---- helpers ----

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        return (h / 24) + " days ago";
    }

    /** Mockup pixels to screen pixels. */
    private int u(float mockupPx) {
        return Math.round(mockupPx * s);
    }

    private TextView label(String text, float mockupPx, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, mockupPx * FONT * s);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    /** A button glyph like the mockup's: an outlined circle (letters) or rounded box (shoulders, D-pad). */
    private View glyph(String text, int color, boolean round) {
        String prompt = UiIcons.promptFor(text);
        android.graphics.Bitmap pic = prompt == null ? null : UiIcons.get(this, prompt);
        if (pic != null) { // Kenney's button prompt, tinted with the colour the hint has always had
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(pic);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            if (!prompt.startsWith("dpad")) iv.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN); // the D-pad pictures mark the pressed part in red
            iv.setLayoutParams(new LinearLayout.LayoutParams(u(52), u(52)));
            return iv;
        }
        boolean one = text.length() == 1;
        TextView v = label(text, one ? 24 : 20, color, true);
        v.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setStroke(u(3), color);
        d.setCornerRadius(round ? u(99) : u(10));
        v.setBackground(d);
        v.setMinWidth(u(42));
        v.setMinHeight(u(42));
        v.setPadding(u(10), 0, u(10), 0);
        return v;
    }
}
