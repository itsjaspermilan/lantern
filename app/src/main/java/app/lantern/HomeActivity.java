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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
    static final int AMBER = 0xFFFFB454;
    private static final String[] TABS = {"Recent", "Systems", "All games", "Search"};
    private static final int TAB_SYSTEMS = 1, TAB_ALL = 2, TAB_SEARCH = 3;
    /** The tab to go back to when the search is left with B. */
    private int tabBeforeSearch;

    private final List<Game> all = new ArrayList<>();
    private final List<Game> shown = new ArrayList<>();

    private float s = 1f;
    /** Text is drawn this much larger than the mockup's sizes, and the side margins are tighter (EDGE_L, EDGE_R). */
    static final float FONT = 1.1f;
    static final int EDGE_L = 90, EDGE_R = 70;
    private boolean loaded;
    private int batteryLevel = -1;
    private boolean batteryCharging, batteryNumber;
    private FrameLayout root, safe, stage;
    private LinearLayout infoBlock, hintsRow, menuHintRow;
    private DetailsPage details;
    private ArtFetcher fetcher;
    private boolean artOn;
    /** When set, the shelf shows only this system's games (opened from the Systems shelf). */
    private String filter;
    private int loadGeneration;
    private Glow glow;
    private Shelf shelf;
    private TextView playPill;
    /** The highlight behind the active tab: it glides from tab to tab and grows or shrinks with the label. */
    private TextView eyebrow;
    private Game lastFocused;
    private int lastHeroIndex = -1;
    private View veil;
    private View dots;
    private int dotsN, dotsAt;
    private boolean clock24;
    private View chevL, chevR;
    private FrameLayout.LayoutParams infoLp;
    /** Recent's big first game has the focus, so A plays it straight away. */
    private boolean heroFocused;
    /** Recent's selected tile is the "All games" one, so A opens it and there are no details. */
    private boolean heroAll;
    private TabBar topBar;
    private TextView title, when, chipView, sub, empty, banner;
    private int tab = -1;
    private String lastId;
    /** The search being shown on the shelf (all games that match), or null. */
    private String query;
    private SearchPanel search;
    private TextView crumb, letterBadge;
    /** Android apps are hidden from every shelf unless this is on; the menu lists them either way. */
    private boolean showApps;
    private MenuPanel menu;

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
        clock24 = getSharedPreferences("lantern", MODE_PRIVATE).getBoolean("clock24", android.text.format.DateFormat.is24HourFormat(this));
        batteryNumber = getSharedPreferences("lantern", MODE_PRIVATE).getBoolean("batteryNumber", true);
        topBar = new TabBar(this, tabBarHost, TABS, clock24);
        stage.addView(topBar.view(), new FrameLayout.LayoutParams(-1, u(120), Gravity.TOP));
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
        dlp.topMargin = u(650); // just under the big tile (which ends at 636)
        stage.addView(dots, dlp);
        details = new DetailsPage(this, detailsHost);
        FrameLayout.LayoutParams ucp = new FrameLayout.LayoutParams(u(170), u(44), Gravity.TOP | Gravity.START);
        ucp.leftMargin = u(960 - 85);
        ucp.topMargin = u(38);
        stage.addView(details.chevron(), ucp);
        empty = label("", 44, 0x99FFFFFF, true);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(u(EDGE_L), 0, u(EDGE_L), 0);
        stage.addView(empty, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        stage.addView(details.view(), new FrameLayout.LayoutParams(-1, -1));
        search = new SearchPanel(this, searchHost);
        stage.addView(search.view(), new FrameLayout.LayoutParams(-1, -1));
        buildHints();
        menu = new MenuPanel(this, menuHost);
        stage.addView(menu.view(), new FrameLayout.LayoutParams(-1, -1));

        topBar.setBattery(batteryLevel, batteryCharging, batteryNumber);
        if (loaded) {
            refresh(true);
        }
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
        infoLp.topMargin = u(hero ? 164 : 866); // Recent: the title starts level with the big tile's top edge
        infoLp.width = u(hero ? 980 : 1700);
        eyebrow.setVisibility(View.GONE); // its text is still kept: the details page shows it
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
        p.topMargin = u(819); // level with the middle of the row of small tiles
        stage.addView(v, p);
        return v;
    }

    /** Shows each arrow only while there is something in that direction. */
    private void updateChevrons() {
        if (chevL == null || shelf == null) return;
        boolean hero = shelf.mode() == Shelf.HERO && !pageOpen() && !shown.isEmpty();
        int rowStart = Math.max(0, shelf.getIndex() - 1); // the row keeps the focused game one tile in from its left edge
        chevL.setVisibility(hero && rowStart > 0 ? View.VISIBLE : View.GONE);
        chevR.setVisibility(hero && rowStart + 4 < shelf.size() ? View.VISIBLE : View.GONE);
        dots.setVisibility(hero ? View.VISIBLE : View.GONE);
        dotsN = shelf.size();
        dotsAt = shelf.getIndex();
        dots.invalidate();
    }

    private void buildHints() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(u(EDGE_L), 0, u(EDGE_R), 0);
        banner = label("", 28, AMBER, true);
        banner.setVisibility(View.GONE); // no longer used: the Start hint says what is needed
        bottom.addView(banner);

        // The hints run along the bottom: the Menu hint alone on the left, the rest right-aligned.
        FrameLayout hintsBar = new FrameLayout(this);
        hintsRow = new LinearLayout(this);
        hintsRow.setGravity(Gravity.CENTER_VERTICAL);
        menuHintRow = new LinearLayout(this);
        menuHintRow.setGravity(Gravity.CENTER_VERTICAL);
        hintsBar.addView(menuHintRow, new FrameLayout.LayoutParams(-2, u(96), Gravity.START));
        hintsBar.addView(hintsRow, new FrameLayout.LayoutParams(-2, u(96), Gravity.END));
        setHints(false);
        bottom.addView(hintsBar, new LinearLayout.LayoutParams(-1, -2));
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
        lp.leftMargin = row.getChildCount() == 0 ? 0 : u(44); // the row is right-aligned, so the spacing goes on the left
        row.addView(h, lp);
    }

    // ---- lifecycle ----

    private final android.content.BroadcastReceiver batteryReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            readBattery(i);
        }
    };

    private void readBattery(Intent b) {
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        batteryLevel = level >= 0 ? level * 100 / Math.max(1, scale) : -1;
        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        if (topBar != null) topBar.setBattery(batteryLevel, batteryCharging, batteryNumber);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (topBar != null) topBar.pause(true);
    }

    @Override
    protected void onDestroy() {
        // The tab-name marquee repeats for ever; none of these may keep the destroyed activity's views alive.
        if (topBar != null) topBar.stop();
        if (details != null) details.stop();
        ArtCache.setListener(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (topBar != null) topBar.pause(false);
        ArtCache.clearShots();
        Library.forgetFileFacts(); // the player (another process) may have saved a resume point or added play time
        unveil(true);
        readBattery(registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED))); // and again on every change
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
        if (searchOpen()) search.close();
        if (menuOpen()) menu.close();
        if (pageOpen()) details.close();
        if (shelf == null) return;
        // Home pressed while Lantern is showing: Recent, if it is not there already (a system, Search or another tab);
        // on Recent itself, back to the first game.
        if (hasRecent() && (tab != 0 || filter != null || query != null)) {
            topBar.snapNext(); // the tab bar must not animate while the screen is coming back: that shows as a flicker
            setTab(0);
        }
        else shelf.setIndex(0, true);
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
        if (menuOpen()) return menu.key(keyCode, first);
        if (searchOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) { // the Menu hint is on the search page too
                if (first) menu.open();
                return true;
            }
            return search.key(keyCode, event, first);
        }
        if (pageOpen()) return details.key(keyCode, first);
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) openSearch(0);
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
                    if (first && shelf.size() > 0) details.open(); // Recent: A plays at once, Down slides up the game's page
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
                if (first) menu.open();
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
        if (menuOpen()) menu.close(); else if (searchOpen()) search.close(); else if (pageOpen()) details.close(); else leaveSystem();
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
            details.open();
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
        shelf.setEnterDirection(t > old ? 1 : -1); // R1: the new tab comes in from the right; L1: from the left
        refresh(false);
        slideIn(t > old ? 120 : -120, 0); // the next tab arrives from the side it sits on
        if (tab == TAB_SEARCH) openSearch(t > old ? 1 : -1); // arriving on Search opens the keyboard, which comes in from the side of the button pressed
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

        // The tab bar is the breadcrumb: inside a system, "Systems" becomes the system's name with a back arrow; a search
        // becomes "back arrow, the words, the number of results".
        boolean crumb = (tab == TAB_SEARCH && searching) || (tab == TAB_SYSTEMS && filter != null);
        String crumbText = !crumb ? null : searching ? "\u201C" + query + "\u201D  \u00B7  " + shown.size() : Library.systemTitle(filter);
        int sysHue = filter == null || tab != TAB_SYSTEMS ? -1 : Library.baseHue(filter); // inside a system the highlight takes its colour
        topBar.update(tab, crumb ? tab : -1, crumbText, sysHue);

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
        setHints(pageOpen());

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

    /** One tile per system that has games, in alphabetical order (Android games and apps too). */
    private List<Game> systemTiles() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Game g : all) if (listed(g)) counts.put(g.sysId, counts.containsKey(g.sysId) ? counts.get(g.sysId) + 1 : 1);
        List<String> ids = new ArrayList<>(counts.keySet());
        Collections.sort(ids, (a, b) -> Library.systemTitle(a).compareToIgnoreCase(Library.systemTitle(b))); // A to Z by name
        List<Game> out = new ArrayList<>();
        int n = 0, count = ids.size(), stride = Math.max(1, Math.round(count * .38f));
        while (stride > 1 && java.math.BigInteger.valueOf(stride).gcd(java.math.BigInteger.valueOf(count)).intValue() != 1) stride++;
        for (String id : ids) {
            Game t = new Game();
            t.id = "sys:" + id;
            t.systemTile = true;
            t.sysId = id;
            t.title = Library.systemTitle(id);
            t.system = Library.systemName(id);
            t.count = counts.get(id);
            t.hue = (20 + (n++ * stride % count) * 360 / count) % 360; // every tile a different colour, evenly round the wheel; neighbours are far apart
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
            if (!pageOpen() && !searchOpen() && !menuOpen()) setHints(false);
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
                    : Cores.infoFor(g.sysId) != null ? "Add its emulator from the menu (Select)"
                    : "No emulator for this system yet");
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
        sub.setText(g.isRom() && !Cores.has(this, g.sysId)
                ? (Cores.infoFor(g.sysId) != null ? "Press A to add the emulator for " + g.system : "No emulator for " + g.system + " yet")
                : saved > 0 ? "Resume available · saved " + ago(saved) : "Ready to play");
        glow.to(Color.HSVToColor(new float[]{g.hue, .60f, .50f}), Color.HSVToColor(new float[]{(g.hue + 35) % 360, .60f, .30f}));
        glow.art(ArtCache.backdrop(g));
        compactInfo();
        if (shelf.mode() == Shelf.HERO) {
            if (g != lastFocused && !pageOpen() && infoBlock.getVisibility() == View.VISIBLE && lastHeroIndex >= 0) {
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
                if (!pageOpen() && !searchOpen() && !menuOpen()) setHints(false);
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
        boolean hero = shelf.mode() == Shelf.HERO && !pageOpen();
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
        if (shelf == null || shelf.mode() != Shelf.HERO || pageOpen() || searchOpen() || menuOpen()) return;
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
                Cores.Info add = Cores.infoFor(g.sysId);
                if (add != null && !menuOpen()) menu.openCores(add.lib); // the emulator it needs, ready to download
                else Toast.makeText(this, "No emulator for " + g.system + " yet.", Toast.LENGTH_SHORT).show();
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
        setHints(pageOpen());
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
        menuHintRow.removeAllViews();
        if (menuOpen()) {
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), "Select");
            hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Close");
        } else if (searchOpen()) {
            hint(menuHintRow, glyph("Select", Color.WHITE, false), "Menu");
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), "Type");
            hint(hintsRow, glyph("Y", 0xFFFFD36E, true), "Delete");
            hint(hintsRow, glyph("X", 0xFF8FC7FF, true), "Show results");
            hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Cancel");
        } else if (page) {
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), "Confirm");
            hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Back");
        } else {
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), shelf != null && shelf.mode() == Shelf.HERO && !heroAll ? "Play" : "Open");
            if (shelf != null && shelf.mode() == Shelf.HERO && !heroAll) hint(hintsRow, glyph("▼", Color.WHITE, true), "Details");
            if (shelf != null && shelf.mode() == Shelf.HERO && !heroAll) hint(hintsRow, glyph("Y", 0xFFFFD36E, true), "Remove");
            hint(hintsRow, glyph("X", 0xFF8FC7FF, true), "Search");
            if (filter != null || tab == TAB_SEARCH) hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Back");
            // Select opens the menu: settings, file access and the Android apps list.
            hint(menuHintRow, glyph("Select", Color.WHITE, false), Library.hasFileAccess() ? "Menu" : "Menu (file access)");
        }
    }

    /** Android apps are only listed when the menu's switch is on, or while their own list is open. */
    private boolean listed(Game g) {
        if (!Library.APPS.equals(g.sysId)) return true;
        return showApps || Library.APPS.equals(filter);
    }

    /** What the tab bar asks of the launcher. */
    private final TabBar.Host tabBarHost = new TabBar.Host() {
        @Override
        public int px(float mockupPx) {
            return u(mockupPx);
        }

        @Override
        public float scale() {
            return s;
        }

        @Override
        public TextView text(String text, float mockupPx, int color, boolean bold) {
            return label(text, mockupPx, color, bold);
        }

        @Override
        public View glyph(String name, int color, boolean round) {
            return HomeActivity.this.glyph(name, color, round);
        }
    };

    // ---- menu (Select) ----

    private boolean menuOpen() {
        return menu != null && menu.isOpen();
    }

    /** What the menu panel asks of the launcher. */
    private final MenuPanel.Host menuHost = new MenuPanel.Host() {
        @Override
        public int px(float mockupPx) {
            return u(mockupPx);
        }

        @Override
        public TextView text(String text, float mockupPx, int color, boolean bold) {
            return label(text, mockupPx, color, bold);
        }

        @Override
        public int appCount() {
            int apps = 0;
            for (Game g : all) if (Library.APPS.equals(g.sysId)) apps++;
            return apps;
        }

        @Override
        public boolean showApps() {
            return showApps;
        }

        @Override
        public boolean coverArt() {
            return artOn;
        }

        @Override
        public boolean clock24() {
            return clock24;
        }

        @Override
        public boolean hasFileAccess() {
            return Library.hasFileAccess();
        }

        @Override
        public void toggleShowApps() {
            showApps = !showApps;
            getSharedPreferences("lantern", MODE_PRIVATE).edit().putBoolean("showApps", showApps).apply();
            refresh(true);
        }

        @Override
        public void toggleCoverArt() {
            toggleArt();
        }

        @Override
        public boolean batteryNumber() {
            return batteryNumber;
        }

        @Override
        public void toggleBatteryNumber() {
            batteryNumber = !batteryNumber;
            getSharedPreferences("lantern", MODE_PRIVATE).edit().putBoolean("batteryNumber", batteryNumber).apply();
            topBar.setBattery(batteryLevel, batteryCharging, batteryNumber);
        }

        @Override
        public void toggleClock() {
            clock24 = !clock24;
            getSharedPreferences("lantern", MODE_PRIVATE).edit().putBoolean("clock24", clock24).apply();
            topBar.setClock24(clock24);
        }

        @Override
        public void coresChanged() {
            if (!shown.isEmpty()) onFocused(shelf.getIndex());
        }

        @Override
        public void openAppsList() {
            if (searchOpen()) search.close(); // the menu was opened over the search page
            filter = Library.APPS;
            query = null;
            tab = TAB_SYSTEMS;
            lastId = null;
            refresh(false);
        }

        @Override
        public void askForFileAccess() {
            HomeActivity.this.askForFileAccess();
        }

        @Override
        public void menuVisible(boolean visible) {
            setHints(visible ? false : pageOpen());
        }

        @Override
        public void shiftScreen(float dx, long ms) {
            android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
            shelf.animate().translationX(u(dx)).setDuration(ms).setInterpolator(ease).start();
            infoBlock.animate().translationX(u(dx)).setDuration(ms).setInterpolator(ease).start();
        }
    };

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

    /**
     * Opens the search. dir is where its keyboard comes from: +1 the right (R1 onto the Search tab), -1 the left (L1),
     * 0 below (the X button).
     */
    private void openSearch(int dir) {
        if (tab != TAB_SEARCH) { // X from anywhere: go to the Search tab first, then type
            tabBeforeSearch = tab;
            tab = TAB_SEARCH;
            filter = null;
            query = null;
            lastId = null;
            refresh(false);
        }
        search.open(dir);
    }

    private boolean searchOpen() {
        return search != null && search.isOpen();
    }

    /** What the search panel asks of the launcher. */
    private final SearchPanel.Host searchHost = new SearchPanel.Host() {
        @Override
        public int px(float mockupPx) {
            return u(mockupPx);
        }

        @Override
        public TextView text(String text, float mockupPx, int color, boolean bold) {
            return label(text, mockupPx, color, bold);
        }

        @Override
        public List<Game> matches(String q) {
            return HomeActivity.this.matches(q);
        }

        @Override
        public String currentQuery() {
            return query;
        }

        @Override
        public void showResults(String q, String focusId) {
            query = q;
            if (query != null) filter = null;
            lastId = focusId;
            refresh(focusId != null);
        }

        @Override
        public void searchVisible(boolean visible) {
            empty.setVisibility(visible ? View.GONE : View.VISIBLE); // the keyboard has the screen: the empty-search hint would show through its dim layer
            setHints(visible ? false : pageOpen());
        }

        @Override
        public void moveTab(int dir) {
            setTab(tab + dir);
        }
    };

    private boolean pageOpen() {
        return details != null && details.isOpen();
    }

    /** What the details page asks of the launcher. */
    private final DetailsPage.Host detailsHost = new DetailsPage.Host() {
        @Override
        public int px(float mockupPx) {
            return u(mockupPx);
        }

        @Override
        public float scale() {
            return s;
        }

        @Override
        public TextView text(String text, float mockupPx, int color, boolean bold) {
            return label(text, mockupPx, color, bold);
        }

        @Override
        public Shelf shelf() {
            return shelf;
        }

        @Override
        public android.view.ViewGroup info() {
            return infoBlock;
        }

        @Override
        public TabBar tabBar() {
            return topBar;
        }

        @Override
        public Game game() {
            int i = shelf.getIndex();
            return i >= 0 && i < shown.size() ? shown.get(i) : null;
        }

        @Override
        public String eyebrow() {
            return String.valueOf(HomeActivity.this.eyebrow.getText());
        }

        @Override
        public void infoVisible(boolean visible) {
            setInfoVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }

        @Override
        public void listChromeVisible(boolean visible) {
            if (visible) {
                updateChevrons();
            } else if (chevL != null) {
                chevL.setVisibility(View.GONE);
                chevR.setVisibility(View.GONE);
                dots.setVisibility(View.GONE);
            }
        }

        @Override
        public void hints(boolean page) {
            setHints(page);
        }

        @Override
        public void play(Game g, boolean resume) {
            open(g, resume);
        }

        @Override
        public void appSettings(Game g) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + g.pkg)));
            } catch (RuntimeException e) {
                Toast.makeText(HomeActivity.this, "Settings could not be opened.", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public String ago(long time) {
            return HomeActivity.ago(time);
        }
    };

    // ---- helpers ----

    static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        return h < 48 ? "yesterday" : (h / 24) + " days ago";
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
