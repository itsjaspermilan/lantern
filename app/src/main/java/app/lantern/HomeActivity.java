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
 * D-pad to browse, A to open, Y to favourite, X to search. Every size below is in mockup pixels, scaled to the screen.
 */
public class HomeActivity extends Activity {
    private static final int AMBER = 0xFFFFB454;
    private static final String[] TABS = {"Recent", "Favourites", "Systems", "All games"};
    private static final int TAB_SYSTEMS = 2, TAB_ALL = 3;

    private final List<Game> all = new ArrayList<>();
    private final List<Game> shown = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();

    private float s = 1f;
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
    private TextView title, when, chipView, sub, empty, banner, battery;
    private int tab = -1;
    private String lastId;
    /** The search being shown on the shelf (all games that match), or null. */
    private String query;
    private boolean searchOpen;
    private String draft = "";
    private int kbRow, kbCol;
    private FrameLayout searchView;
    private TextView crumb;
    private static final String[][] KEYS = {
        {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j"},
        {"k", "l", "m", "n", "o", "p", "q", "r", "s", "t"},
        {"u", "v", "w", "x", "y", "z", "0", "1", "2", "3"},
        {"4", "5", "6", "7", "8", "9", "Space", "Del", "OK"},
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
        });
        stage.addView(shelf, new FrameLayout.LayoutParams(-1, -1));
        buildTopBar();
        crumb = label("", 28, 0xCCFFFFFF, false);
        crumb.setVisibility(View.GONE);
        FrameLayout.LayoutParams cr = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cr.leftMargin = u(140);
        cr.topMargin = u(104);
        stage.addView(crumb, cr);
        buildInfo();
        empty = label("", 44, 0x99FFFFFF, true);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(u(140), 0, u(140), 0);
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

        battery.setText(batteryText);
        if (loaded) {
            refresh(true);
        }
    }

    private void buildTopBar() {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(u(140), 0, u(100), 0);

        View flame = new View(this);
        GradientDrawable fd = new GradientDrawable();
        fd.setShape(GradientDrawable.OVAL);
        fd.setColor(AMBER);
        flame.setBackground(fd);
        flame.setElevation(u(8));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(u(22), u(30));
        fp.rightMargin = u(14);
        top.addView(flame, fp);
        top.addView(label("Lantern", 34, Color.WHITE, true));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = u(60);
        top.addView(tabs, tp);
        tabs.addView(glyph("L1", Color.WHITE, false));
        for (String name : TABS) {
            TextView t = label(name, 32, Color.WHITE, false);
            t.setPadding(u(18), u(8), u(18), u(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = u(11);
            lp.rightMargin = u(11);
            tabs.addView(t, lp);
            tabViews.add(t);
        }
        tabs.addView(glyph("R1", Color.WHITE, false));

        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        battery = label("", 30, Color.WHITE, false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.rightMargin = u(26);
        top.addView(battery, bp);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * s);
        top.addView(clock);
        stage.addView(top, new FrameLayout.LayoutParams(-1, u(120), Gravity.TOP));
    }

    private void buildInfo() {
        LinearLayout info = new LinearLayout(this);
        infoBlock = info;
        info.setOrientation(LinearLayout.VERTICAL);
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

        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(u(1400), -2, Gravity.TOP | Gravity.START);
        ip.leftMargin = u(140);
        ip.topMargin = u(735);
        stage.addView(info, ip);
    }

    private void buildHints() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(u(140), 0, u(100), 0);
        banner = label("", 28, AMBER, true);
        banner.setVisibility(View.GONE); // no longer used: the Start hint says what is needed
        bottom.addView(banner);

        hintsRow = new LinearLayout(this);
        hintsRow.setGravity(Gravity.CENTER_VERTICAL);
        setHints(false);
        bottom.addView(hintsRow, new LinearLayout.LayoutParams(-2, u(96)));
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
        lp.rightMargin = u(44);
        row.addView(h, lp);
    }

    // ---- lifecycle ----

    @Override
    protected void onResume() {
        super.onResume();
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
        if (pageOpen) return pageKey(keyCode, first);
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) openSearch();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                shelf.move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                shelf.move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
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
                if (first) toggleFavourite();
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (first) {
                    if (!Library.hasFileAccess()) askForFileAccess();
                    else toggleArt();
                }
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
        if (searchOpen) closeSearch(); else if (pageOpen) closePage(); else leaveSystem();
    }

    /** A on the shelf: a system tile opens that system's games, a game tile opens its page. */
    private void activateShelf() {
        Game g = shelf.itemAt(shelf.getIndex());
        if (g == null) return;
        if (g.systemTile) {
            filter = g.sysId;
            lastId = null;
            refresh(false);
        } else {
            openPage();
        }
    }

    /** B inside a search clears it; B inside a system goes back to the Systems shelf, with that system's tile selected. */
    private void leaveSystem() {
        if (query != null) {
            query = null;
            refresh(true); // the focused game stays focused if the shelf still holds it
            return;
        }
        if (filter == null) return;
        lastId = "sys:" + filter;
        filter = null;
        refresh(true);
    }

    // ---- shelves ----

    private boolean hasRecent() {
        for (Game g : all) if (g.lastPlayed > 0) return true;
        return false;
    }

    private void setTab(int t) {
        tab = (t + TABS.length) % TABS.length;
        filter = null;
        query = null;
        lastId = null;
        refresh(false);
    }

    private void refresh(boolean keepFocus) {
        shown.clear();
        boolean searching = query != null;
        boolean systemTiles = !searching && tab == TAB_SYSTEMS && filter == null;
        if (searching) {
            shown.addAll(matches(query));
        } else if (systemTiles) {
            shown.addAll(systemTiles());
        } else {
            for (Game g : all) {
                if (filter != null && !filter.equals(g.sysId)) continue;
                if (filter == null && tab == 0 && g.lastPlayed == 0) continue;
                if (filter == null && tab == 1 && !g.fav) continue;
                shown.add(g);
            }
            if (filter == null && tab == 0) Collections.sort(shown, (a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        }

        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean on = i == tab && !searching;
            // Inside a system, its tab shows the system's name, so it is clear where you are.
            if (on && i == TAB_SYSTEMS) t.setText(filter == null ? TABS[i] : Library.systemTitle(filter));
            else t.setText(TABS[i]);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setAlpha(on ? 1f : 0.55f);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(u(99));
            pill.setColor(on ? 0x24FFFFFF : 0);
            t.setBackground(pill);
        }

        // Work out which tile to come back to first: setItems announces item 0, which overwrites lastId.
        final String want = keepFocus ? lastId : null;
        int restore = 0;
        if (want != null) {
            for (int i = 0; i < shown.size(); i++) if (shown.get(i).id.equals(want)) { restore = i; break; }
        }
        shelf.setItems(new ArrayList<>(shown), systemTiles);
        shelf.setIndex(restore, false);
        setHints(pageOpen);

        updateCrumb();
        empty.setText(shown.isEmpty() ? emptyMessage() : "");
        if (shown.isEmpty()) {
            title.setText("");
            when.setText("");
            chipView.setVisibility(View.GONE);
            sub.setText("");
            glow.to(0xFF2A2438, 0xFF14121C);
        }
    }

    private String emptyMessage() {
        if (query != null) return "No games match \u201c" + query + "\u201d.\nPress B to clear the search.";
        if (filter != null) return "No games here.";
        if (tab == 0) return "Nothing played yet.\nOpen a game and it will show up here.";
        if (tab == 1) return "No favourites yet.\nPress Y on a game to add one.";
        return Library.hasFileAccess()
                ? "No games found.\nPut ROMs in Lantern/roms/<system> on the device,\nor install an Android game."
                : "No games found yet.";
    }

    /** One tile per system that has games, in a fixed order: consoles by name, Android last. */
    private List<Game> systemTiles() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Game g : all) counts.put(g.sysId, counts.containsKey(g.sysId) ? counts.get(g.sysId) + 1 : 1);
        List<String> ids = new ArrayList<>(counts.keySet());
        Collections.sort(ids, (a, b) -> {
            if (a.equals(Library.APPS)) return b.equals(Library.APPS) ? 0 : 1;
            if (b.equals(Library.APPS)) return -1;
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
        if (g.systemTile) {
            title.setText(g.title);
            chipView.setVisibility(View.VISIBLE);
            chipView.setText(g.count + (g.count == 1 ? " GAME" : " GAMES"));
            when.setText("");
            sub.setText(g.sysId.equals(Library.APPS) ? "Installed Android apps"
                    : Cores.has(this, g.sysId) ? "Plays in Lantern's built-in player"
                    : "No emulator core for this system yet");
            glow.to(Color.HSVToColor(new float[]{g.hue, .60f, .50f}), Color.HSVToColor(new float[]{(g.hue + 35) % 360, .60f, .30f}));
            return;
        }
        title.setText(g.title);
        chipView.setVisibility(View.VISIBLE);
        chipView.setText(g.system.toUpperCase());
        when.setText(g.lastPlayed == 0 ? "Never played" : "Played " + ago(g.lastPlayed));
        long saved = Library.resumeTime(g);
        sub.setText(g.isRom() && !Cores.has(this, g.sysId) ? "No emulator core for " + g.system + " yet"
                : saved > 0 ? "Resume available · saved " + ago(saved) : "Ready to play");
        glow.to(Color.HSVToColor(new float[]{g.hue, .60f, .50f}), Color.HSVToColor(new float[]{(g.hue + 35) % 360, .60f, .30f}));
    }

    // ---- actions ----

    /** Opens a game. For a ROM, resume picks up the saved resume point instead of starting fresh. */
    private void open(Game g, boolean resume) {
        if (g.isRom()) {
            java.io.File core = Cores.file(this, g.sysId);
            if (core == null) {
                Toast.makeText(this, "No emulator core for " + g.system + " yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            Library.markPlayed(this, g);
            startActivity(new Intent(this, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_CORE, core.getAbsolutePath())
                    .putExtra(PlayerActivity.EXTRA_ROM, g.file.getAbsolutePath())
                    .putExtra(PlayerActivity.EXTRA_SYS, Library.biosDir().getAbsolutePath())
                    .putExtra(PlayerActivity.EXTRA_SAVE, Library.saveDir(g.sysId).getAbsolutePath())
                    .putExtra(PlayerActivity.EXTRA_START, resume ? 0 : -1)
                    .putExtra(PlayerActivity.EXTRA_TITLE, g.title)
                    .putExtra(PlayerActivity.EXTRA_SYSID, g.sysId));
            return;
        }
        try {
            Library.markPlayed(this, g);
            startActivity(g.launch);
        } catch (RuntimeException e) {
            // App was removed or is not launchable; the next resume rebuilds the shelf.
        }
    }

    private void toggleFavourite() {
        int i = shelf.getIndex();
        if (i < 0 || i >= shown.size()) return;
        if (shown.get(i).systemTile) return; // systems cannot be favourites
        Library.toggleFav(this, shown.get(i));
        if (tab == 1 && filter == null && query == null) {
            lastId = null;
            refresh(false);
            shelf.setIndex(Math.min(i, shown.size() - 1), false);
        } else {
            shelf.redraw();
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
        if (searchOpen) {
            hint(hintsRow, glyph("◀ ▶ ▲ ▼", Color.WHITE, true), "Choose");
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), "Type");
            hint(hintsRow, glyph("Y", 0xFFFFD36E, true), "Delete");
            hint(hintsRow, glyph("X", 0xFF8FC7FF, true), "Show results");
            hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Cancel");
        } else if (page) {
            hint(hintsRow, glyph("▲ ▼", Color.WHITE, true), "Choose");
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), "Confirm");
            hint(hintsRow, glyph("Y", 0xFFFFD36E, true), "Favourite");
            hint(hintsRow, glyph("L1", Color.WHITE, false), "Previous");
            hint(hintsRow, glyph("R1", Color.WHITE, false), "Next");
            hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Back");
        } else {
            hint(hintsRow, glyph("◀ ▶", Color.WHITE, true), "Browse");
            hint(hintsRow, glyph("L1", Color.WHITE, false), "Shelf");
            hint(hintsRow, glyph("R1", Color.WHITE, false), "Shelf");
            hint(hintsRow, glyph("A", 0xFF8BE28F, true), "Open");
            hint(hintsRow, glyph("Y", 0xFFFFD36E, true), "Favourite");
            hint(hintsRow, glyph("X", 0xFF8FC7FF, true), "Search");
            if (filter != null || query != null) hint(hintsRow, glyph("B", 0xFFFF8F8F, true), "Back");
            // Start asks for file access until Lantern has it, and then switches cover art on and off.
            hint(hintsRow, glyph("Start", Color.WHITE, false),
                    !Library.hasFileAccess() ? "Allow file access" : artOn ? "Cover art: on" : "Cover art: off");
        }
    }

    // ---- search and breadcrumbs ----

    /** Games whose title or system contains every word of the query, in title order. */
    private List<Game> matches(String q) {
        String[] words = q.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
        List<Game> out = new ArrayList<>();
        for (Game g : all) {
            String hay = (g.title + " " + g.system).toLowerCase(java.util.Locale.ROOT);
            boolean ok = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { ok = false; break; }
            if (ok) out.add(g);
        }
        return out;
    }

    /** "Systems > Game Boy Advance . 214 games" inside a system, "Search > "mario" . 12 games" after a search. */
    private void updateCrumb() {
        String where = null;
        if (query != null) where = "Search  \u203A  \u201C" + query + "\u201D";
        else if (filter != null) where = "Systems  \u203A  " + Library.systemTitle(filter);
        if (where == null) {
            crumb.setVisibility(View.GONE);
            return;
        }
        crumb.setText(where + "   \u00B7   " + shown.size() + (shown.size() == 1 ? " game" : " games"));
        crumb.setVisibility(View.VISIBLE);
    }

    private void openSearch() {
        searchOpen = true;
        draft = query == null ? "" : query;
        kbRow = 0;
        kbCol = 0;
        renderSearch();
        searchView.setVisibility(View.VISIBLE);
        searchView.setAlpha(0f);
        searchView.animate().alpha(1f).setDuration(150).start();
        setHints(false);
    }

    private void closeSearch() {
        searchOpen = false;
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
                String k = KEYS[kbRow][kbCol];
                if (k.equals("OK")) applySearch();
                else if (k.equals("Del")) backspace();
                else typeChar(k.equals("Space") ? " " : k);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_DEL:
                backspace();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) applySearch();
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
        searchView.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        TextView head = label("Search", 56, Color.WHITE, true);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hp.leftMargin = u(140);
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
        bp.leftMargin = u(140);
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
                kp.leftMargin = u(140 + c * (cell + gap));
                kp.topMargin = u(350 + r * (cell - 8 + gap));
                searchView.addView(v, kp);
            }
        }

        // Live matches on the right.
        List<Game> hits = draft.trim().isEmpty() ? new ArrayList<Game>() : matches(draft);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        String sum = draft.trim().isEmpty() ? "Pick letters, then press X to see the games"
                : hits.size() + (hits.size() == 1 ? " game" : " games");
        col.addView(label(sum, 28, AMBER, true));
        for (int i = 0; i < Math.min(hits.size(), 8); i++) {
            Game g = hits.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(g.title, 30, Color.WHITE, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(label(g.system, 22, 0x99FFFFFF, false));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = u(18);
            col.addView(row, rp);
        }
        if (hits.size() > 8) {
            TextView more = label("and " + (hits.size() - 8) + " more", 24, 0x99FFFFFF, false);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
            mp.topMargin = u(18);
            col.addView(more, mp);
        }
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(u(560), -2, Gravity.TOP | Gravity.START);
        cp.leftMargin = u(1260);
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
        a.add(new Act("★", g.fav ? "Remove from favourites" : "Add to favourites", ""));
        a.add(new Act("i", infoOpen ? "Hide details" : "Details", ""));
        if (!g.isRom()) a.add(new Act("⚙", "App settings", "Android settings"));
        return a;
    }

    private void openPage() {
        int i = shelf.getIndex();
        if (i < 0 || i >= shown.size()) return;
        pageOpen = true;
        infoOpen = false;
        actIdx = 0;
        renderPage();
        pageView.setVisibility(View.VISIBLE);
        pageView.setAlpha(0f);
        pageView.animate().alpha(1f).setDuration(200).start();
        shelf.setVisibility(View.INVISIBLE);
        infoBlock.setVisibility(View.INVISIBLE);
        setHints(true);
    }

    private void closePage() {
        pageOpen = false;
        pageView.setVisibility(View.GONE);
        pageView.removeAllViews();
        shelf.setVisibility(View.VISIBLE);
        infoBlock.setVisibility(View.VISIBLE);
        setHints(false);
        int i = shelf.getIndex();
        if (tab == 1 && filter == null && query == null) {
            // A game un-favourited on its page leaves the Favourites shelf now.
            lastId = null;
            refresh(false);
            shelf.setIndex(Math.min(i, shown.size() - 1), false);
        } else {
            shelf.redraw();
        }
    }

    private void renderPage() {
        Game g = shown.get(shelf.getIndex());
        pageView.removeAllViews();

        Cover big = new Cover(this, s);
        big.setGame(g);
        big.setWeight(1f);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(u(430), u(620), Gravity.TOP | Gravity.START);
        cp.leftMargin = u(140);
        cp.topMargin = u(170);
        pageView.addView(big, cp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = label(g.title, 96, Color.WHITE, true);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);

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
        if (g.fav) {
            TextView star = label("★ Favourite", 30, AMBER, false);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.leftMargin = u(22);
            meta.addView(star, sp);
        }
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = u(14);
        col.addView(meta, mp);

        TextView blurb = label(infoOpen ? details(g) : summary(g), infoOpen ? 26 : 30, 0xBFFFFFFF, false);
        blurb.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(u(900), -2);
        bp.topMargin = u(22);
        bp.bottomMargin = u(30);
        col.addView(blurb, bp);

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
            col.addView(row, rp);
        }

        FrameLayout.LayoutParams colp = new FrameLayout.LayoutParams(u(1120), -2, Gravity.TOP | Gravity.START);
        colp.leftMargin = u(660);
        colp.topMargin = u(170);
        pageView.addView(col, colp);
    }

    private boolean pageKey(int keyCode, boolean first) {
        List<Act> acts = actionsFor(shown.get(shelf.getIndex()));
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (actIdx > 0) { actIdx--; renderPage(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (actIdx < acts.size() - 1) { actIdx++; renderPage(); }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (first) closePage();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (first) {
                    shelf.setIndex(shelf.getIndex() + (keyCode == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1), false);
                    actIdx = 0;
                    infoOpen = false;
                    renderPage();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (first) {
                    Library.toggleFav(this, shown.get(shelf.getIndex()));
                    renderPage();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) activate(shown.get(shelf.getIndex()), acts.get(actIdx));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
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
        } else if (a.label.startsWith("Add to") || a.label.startsWith("Remove from")) {
            Library.toggleFav(this, g);
            renderPage();
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
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, mockupPx * s);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    /** A button glyph like the mockup's: an outlined circle (letters) or rounded box (shoulders, D-pad). */
    private TextView glyph(String text, int color, boolean round) {
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
