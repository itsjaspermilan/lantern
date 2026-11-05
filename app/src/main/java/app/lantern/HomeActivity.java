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
import java.util.List;

/**
 * The home screen, laid out to the 1920 x 1080 mockup: three shelves switched with L1 and R1,
 * D-pad to browse, A to open, Y to favourite. Every size below is in mockup pixels, scaled to the screen.
 */
public class HomeActivity extends Activity {
    private static final int AMBER = 0xFFFFB454;
    private static final String[] TABS = {"Recent", "Favourites", "All games"};

    private final List<Game> all = new ArrayList<>();
    private final List<Game> shown = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();

    private float s = 1f;
    private boolean loaded;
    private String batteryText = "";
    private FrameLayout root, safe, stage;
    private Glow glow;
    private Shelf shelf;
    private TextView title, when, chipView, sub, empty, banner, battery;
    private int tab = -1;
    private String lastId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The screen is built once the window's real size is known: some devices keep space for
        // system bars even in full screen, and the 16:9 layout then has to fit what is left.
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
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
                // Keep the bottom clear of the navigation bar even while it is hidden: a swipe or a
                // system that never hides it would otherwise cover the button hints.
                int navBottom = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom;
                safe.setPadding(i.left, i.top, i.right, Math.max(i.bottom, navBottom));
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

    private void buildUi() {
        safe.removeAllViews();
        tabViews.clear();
        glow.to(0xFF2A2438, 0xFF14121C);
        stage = new FrameLayout(this);
        stage.setClipChildren(false); // on screens wider than 16:9 the row may run past the layout box
        safe.addView(stage, new FrameLayout.LayoutParams(u(1920), u(1080), Gravity.CENTER));

        shelf = new Shelf(this, s);
        shelf.setListener(this::onFocused);
        stage.addView(shelf, new FrameLayout.LayoutParams(-1, -1));
        buildTopBar();
        buildInfo();
        empty = label("", 44, 0x99FFFFFF, true);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(u(140), 0, u(140), 0);
        stage.addView(empty, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        buildHints();

        battery.setText(batteryText);
        if (loaded) {
            banner.setText(Library.hasFileAccess() ? "" : "Lantern needs file access to find your games. Press Start to allow it.");
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
        info.setOrientation(LinearLayout.VERTICAL);
        title = label("", 76, Color.WHITE, true);
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
        banner.setPadding(0, 0, 0, u(8));
        bottom.addView(banner);

        LinearLayout hints = new LinearLayout(this);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        hint(hints, glyph("◀ ▶", Color.WHITE, true), "Browse");
        hint(hints, glyph("L1", Color.WHITE, false), "Shelf");
        hint(hints, glyph("R1", Color.WHITE, false), "Shelf");
        hint(hints, glyph("A", 0xFF8BE28F, true), "Open");
        hint(hints, glyph("Y", 0xFFFFD36E, true), "Favourite");
        bottom.addView(hints, new LinearLayout.LayoutParams(-2, u(96)));
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
        all.clear();
        all.addAll(Library.load(this));
        if (tab < 0) tab = hasRecent() ? 0 : 2;
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            batteryText = level >= 0 ? (level * 100 / scale) + "%" : "";
        }
        loaded = true;
        if (stage != null) buildUi(); // already sized: rebuild with the fresh library

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
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
        switch (keyCode) {
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
                if (first && shelf.getIndex() < shown.size()) open(shown.get(shelf.getIndex()));
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (first) toggleFavourite();
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (first && !Library.hasFileAccess()) askForFileAccess();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onBackPressed() {
        // A launcher has nowhere to go back to.
    }

    // ---- shelves ----

    private boolean hasRecent() {
        for (Game g : all) if (g.lastPlayed > 0) return true;
        return false;
    }

    private void setTab(int t) {
        tab = (t + TABS.length) % TABS.length;
        lastId = null;
        refresh(false);
    }

    private void refresh(boolean keepFocus) {
        shown.clear();
        for (Game g : all) {
            if (tab == 0 && g.lastPlayed == 0) continue;
            if (tab == 1 && !g.fav) continue;
            shown.add(g);
        }
        if (tab == 0) Collections.sort(shown, (a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));

        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean on = i == tab;
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setAlpha(on ? 1f : 0.55f);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(u(99));
            pill.setColor(on ? 0x24FFFFFF : 0);
            t.setBackground(pill);
        }

        List<Cover> covers = new ArrayList<>();
        int restore = 0;
        for (int i = 0; i < shown.size(); i++) {
            Game g = shown.get(i);
            Cover c = new Cover(this, g, s);
            c.setOnClickListener(v -> {
                int at = shelf.indexOfChild(v);
                if (at == shelf.getIndex()) open(g); else shelf.setIndex(at, true);
            });
            covers.add(c);
            if (keepFocus && g.id.equals(lastId)) restore = i;
        }
        shelf.setCovers(covers);
        shelf.setIndex(restore, false);

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
        if (tab == 0) return "Nothing played yet.\nOpen a game and it will show up here.";
        if (tab == 1) return "No favourites yet.\nPress Y on a game to add one.";
        return Library.hasFileAccess()
                ? "No games found.\nPut ROMs in Lantern/roms/<system> on the device,\nor install an Android game."
                : "No games found yet.";
    }

    private void onFocused(int index) {
        if (index < 0 || index >= shown.size()) return;
        Game g = shown.get(index);
        lastId = g.id;
        title.setText(g.title);
        chipView.setVisibility(View.VISIBLE);
        chipView.setText(g.system.toUpperCase());
        when.setText(g.lastPlayed == 0 ? "Never played" : "Played " + ago(g.lastPlayed));
        sub.setText(g.isRom() ? "Opens in the built-in player (not built yet)" : "Ready to play");
        glow.to(Color.HSVToColor(new float[]{g.hue, .60f, .50f}), Color.HSVToColor(new float[]{(g.hue + 35) % 360, .60f, .30f}));
    }

    // ---- actions ----

    private void open(Game g) {
        if (g.isRom()) {
            Toast.makeText(this, "No player yet. " + g.title + " will open here in M2.", Toast.LENGTH_SHORT).show();
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
        Library.toggleFav(this, shown.get(i));
        if (tab == 1) {
            lastId = null;
            refresh(false);
            shelf.setIndex(Math.min(i, shown.size() - 1), false);
        } else {
            shelf.getChildAt(i).invalidate();
        }
    }

    private void askForFileAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
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
