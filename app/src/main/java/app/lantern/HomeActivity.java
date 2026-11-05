package app.lantern;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * M0: a home screen that lists the installed apps as one row of tiles, moves focus with the
 * D-pad, opens the focused app with A, and is what the Home button returns to.
 */
public class HomeActivity extends Activity {
    private static final int BG = 0xFF0D0C12;

    private static class App {
        String pkg;
        String label;
        Intent launch;
        Drawable icon;
        int hue;
    }

    private final List<App> apps = new ArrayList<>();
    private float dp;
    private FrameLayout root;
    private HorizontalScrollView scroller;
    private LinearLayout row;
    private TextView title;
    private TextView sub;
    private ValueAnimator bgAnim;
    private int bgColor = BG;
    private String lastPkg;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // Top bar: name on the left, clock on the right.
        LinearLayout top = new LinearLayout(this);
        top.setPadding(px(48), px(24), px(48), 0);
        TextView brand = text("Lantern", 24, Color.WHITE, true);
        View filler = new View(this);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(20);
        top.addView(brand);
        top.addView(filler, new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(clock);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // The shelf and, below it, the focused app's name.
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(0, px(40), 0, 0);

        scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        row = new LinearLayout(this);
        row.setPadding(px(48), px(32), px(48), px(32));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        scroller.addView(row);
        col.addView(scroller, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(px(60), 0, px(48), 0);
        title = text("", 34, Color.WHITE, true);
        sub = text("", 15, 0xB3FFFFFF, false);
        info.addView(title);
        info.addView(sub);
        col.addView(info);

        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        buildRow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Lantern is already showing: go back to the first tile.
        if (row.getChildCount() > 0) {
            row.getChildAt(0).requestFocus();
            scroller.smoothScrollTo(0, 0);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // Draw behind the system bars and keep them hidden; a swipe shows them briefly.
                getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
                return;
            }
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** A confirms, B does nothing here, Back never leaves the home screen. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            View f = getCurrentFocus();
            if (f != null && event.getRepeatCount() == 0) f.performClick();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // A launcher has nowhere to go back to.
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        apps.clear();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.icon = ri.loadIcon(pm);
            a.hue = (pkg.hashCode() & 0x7fffffff) % 360;
            a.launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            apps.add(a);
        }
        Collections.sort(apps, (x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    private void buildRow() {
        row.removeAllViews();
        View restore = null;
        for (App a : apps) {
            View t = tile(a);
            row.addView(t);
            if (a.pkg.equals(lastPkg)) restore = t;
        }
        if (restore == null && row.getChildCount() > 0) restore = row.getChildAt(0);
        if (restore != null) restore.requestFocus();
    }

    private View tile(final App a) {
        FrameLayout t = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                Color.HSVToColor(new float[]{a.hue, .55f, .60f}),
                Color.HSVToColor(new float[]{(a.hue + 30) % 360, .60f, .28f})});
        bg.setCornerRadius(px(20));
        t.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        t.addView(iv, new FrameLayout.LayoutParams(px(72), px(72), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(120), px(120));
        lp.setMargins(px(16), 0, px(16), 0);
        t.setLayoutParams(lp);
        t.setDefaultFocusHighlightEnabled(false); // we draw our own ring; the stock grey square clashes
        t.setFocusable(true);
        t.setFocusableInTouchMode(true); // a tap must never leave the pad without a focused tile
        t.setClickable(true);
        t.setOnClickListener(v -> launch(a));
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.25f : 1f).scaleY(has ? 1.25f : 1f)
                    .translationZ(has ? px(12) : 0).setDuration(160).start();
            bg.setStroke(has ? px(3) : 0, Color.WHITE);
            if (has) focused(a, v);
        });
        return t;
    }

    private void focused(App a, View tile) {
        lastPkg = a.pkg;
        title.setText(a.label);
        sub.setText(a.pkg);
        scroller.smoothScrollTo(tile.getLeft() + tile.getWidth() / 2 - scroller.getWidth() / 2, 0);
        // The room takes the tile's colour, slowly.
        final int to = Color.HSVToColor(new float[]{a.hue, .55f, .22f});
        if (bgAnim != null) bgAnim.cancel();
        bgAnim = ValueAnimator.ofObject(new ArgbEvaluator(), bgColor, to);
        bgAnim.setDuration(450);
        bgAnim.addUpdateListener(v -> {
            bgColor = (Integer) v.getAnimatedValue();
            root.setBackgroundColor(bgColor);
        });
        bgAnim.start();
    }

    private void launch(App a) {
        try {
            startActivity(a.launch);
        } catch (RuntimeException e) {
            // App was removed or is not launchable; the next resume rebuilds the row.
        }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
