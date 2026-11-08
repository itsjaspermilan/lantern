package app.lantern;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The strip along the top: the logo, the tabs, the battery and the clock. The tabs have a fixed total width, so the
 * bar never grows into the battery; they share it by weight, and the tab that holds a breadcrumb (a system's name, a
 * search) takes room from the others, which slide aside and back. One highlight glides to the active tab and takes the
 * colour of the system when inside one. A name too long for its tab slides back and forth.
 */
final class TabBar {
    /** What the bar needs from the launcher. */
    interface Host {
        /** Mockup pixels to screen pixels. */
        int px(float mockupPx);

        /** Screen pixels per mockup pixel. */
        float scale();

        TextView text(String text, float mockupPx, int color, boolean bold);

        /** A button prompt (the L1 and R1 pictures beside the tabs). */
        View glyph(String name, int color, boolean round);
    }

    private final Context ctx;
    private final Host host;
    private final String[] names;
    private final LinearLayout top;
    private final LinearLayout tabs;
    private final FrameLayout tabHost;
    private final View tabPill;
    private final GradientDrawable pillBg;
    private final List<TextView> tabViews = new ArrayList<>();
    private final float[] tabNat;
    private final TextView batteryNum;
    private final BatteryIcon batteryIcon;
    private final LinearLayout batteryBox;
    private final TextClock clock;
    private int active = -1;
    private int pillX, pillW;
    private android.animation.ValueAnimator pillAnim;
    private android.animation.ValueAnimator marquee;
    private TextView marqueeTab;

    TabBar(Context ctx, Host host, String[] names, boolean clock24) {
        this.ctx = ctx;
        this.host = host;
        this.names = names;
        top = new LinearLayout(ctx);
        top.setClipChildren(false);
        top.setClipToPadding(false);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(host.px(HomeActivity.EDGE_L), 0, host.px(HomeActivity.EDGE_R), 0);

        LanternLogo logo = new LanternLogo(ctx);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(host.px(80), host.px(80));
        fp.rightMargin = host.px(22);
        top.addView(logo, fp);
        // Spaced capitals with a lantern-lit gradient; the first letter is the warmest.
        final TextView word = host.text("LANTERN", 30, Color.WHITE, true);
        word.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        word.setLetterSpacing(0.3f);
        word.setShadowLayer(host.px(12), 0, 0, 0x55FFB454);
        word.post(() -> word.getPaint().setShader(new android.graphics.LinearGradient(0, 0, word.getWidth(), 0,
                new int[]{0xFFFFC46B, 0xFFFFFFFF, 0xFFFFE9C4}, new float[]{0f, .35f, 1f}, android.graphics.Shader.TileMode.CLAMP)));
        top.addView(word);

        tabs = new LinearLayout(ctx);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabHost = new FrameLayout(ctx);
        tabHost.setClipChildren(false); // the highlight swells past the bar's edges when it pulses
        tabHost.setClipToPadding(false);
        tabPill = new View(ctx);
        pillBg = new GradientDrawable();
        pillBg.setCornerRadius(host.px(99));
        pillBg.setColor(DEFAULT_PILL);
        tabPill.setBackground(pillBg);
        tabHost.addView(tabPill, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.START));
        tabHost.addView(tabs, new FrameLayout.LayoutParams(-1, -2));
        // A fixed width: the bar never grows into the battery and the clock. Tabs share it by weight (see applyTabWeights).
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(host.px(1000), -2);
        tp.leftMargin = host.px(60);
        top.addView(tabHost, tp);
        tabs.addView(host.glyph("L1", Color.WHITE, false));
        for (String name : names) {
            TextView t = host.text(name, 32, Color.WHITE, false);
            t.setPadding(host.px(18), host.px(6), host.px(18), host.px(10));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = host.px(11);
            lp.rightMargin = host.px(11);
            tabs.addView(t, lp);
            tabViews.add(t);
        }
        tabs.addView(host.glyph("R1", Color.WHITE, false));
        tabNat = new float[names.length];
        android.graphics.Paint bold = new android.graphics.Paint(tabViews.get(0).getPaint());
        bold.setTypeface(Typeface.DEFAULT_BOLD);
        for (int i = 0; i < names.length; i++) tabNat[i] = bold.measureText(names[i]);
        applyTabWeights(-1);

        top.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        batteryBox = new LinearLayout(ctx);
        batteryBox.setGravity(Gravity.CENTER_VERTICAL);
        batteryNum = host.text("", 36, Color.WHITE, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.rightMargin = host.px(10);
        batteryBox.addView(batteryNum, np);
        batteryIcon = new BatteryIcon(ctx);
        batteryBox.addView(batteryIcon, new LinearLayout.LayoutParams(host.px(62), host.px(31)));
        batteryBox.setVisibility(View.GONE); // until the level is known
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.rightMargin = host.px(26);
        top.addView(batteryBox, bp);
        clock = new TextClock(ctx);
        setClock24(clock24);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, host.px(36 * HomeActivity.FONT));
        top.addView(clock);
    }

    private static final int DEFAULT_PILL = 0x2EFFFFFF;

    /** The strip itself, to put at the top of the screen (120 mockup pixels tall). */
    View view() {
        return top;
    }

    /** The battery: a drawn icon (with a bolt while charging), and the number beside it if showNumber. level < 0: unknown. */
    void setBattery(int level, boolean charging, boolean showNumber) {
        batteryBox.setVisibility(level < 0 ? View.GONE : View.VISIBLE);
        batteryIcon.set(level, charging);
        batteryNum.setText(String.valueOf(level));
        batteryNum.setVisibility(showNumber ? View.VISIBLE : View.GONE);
    }

    /** A small battery drawn in white: the outline, a fill for the level (red when low), and a bolt when charging. */
    private static final class BatteryIcon extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path bolt = new android.graphics.Path();
        private int level;
        private boolean charging;

        BatteryIcon(Context c) {
            super(c);
        }

        void set(int level, boolean charging) {
            this.level = level;
            this.charging = charging;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight(), stroke = h * .09f, nub = w * .07f;
            float bodyR = w - nub - 1f;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setColor(Color.WHITE);
            android.graphics.RectF body = new android.graphics.RectF(stroke / 2, stroke / 2, bodyR - stroke / 2, h - stroke / 2);
            cv.drawRoundRect(body, h * .22f, h * .22f, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            cv.drawRoundRect(new android.graphics.RectF(bodyR, h * .32f, w, h * .68f), nub / 2, nub / 2, p);
            float in = stroke * 2.1f, fw = (bodyR - in * 2) * Math.max(0, Math.min(100, level)) / 100f;
            // Green while charging; otherwise yellow when low (30 or less) and red when very low (15 or less).
            p.setColor(charging ? 0xFF5BD66F : level <= 15 ? 0xFFFF5A4F : level <= 30 ? 0xFFFFC94D : Color.WHITE);
            if (fw > 0) cv.drawRoundRect(new android.graphics.RectF(in, in, in + Math.max(fw, h * .1f), h - in), h * .1f, h * .1f, p);
            if (charging) { // a bolt over the middle, dark with a white edge so it shows on the fill and on the gap
                float cx = bodyR / 2f, t = h * .14f, b = h * .86f, hw = h * .2f;
                bolt.reset();
                bolt.moveTo(cx + hw * .3f, t);
                bolt.lineTo(cx - hw, h * .55f);
                bolt.lineTo(cx - hw * .05f, h * .55f);
                bolt.lineTo(cx - hw * .3f, b);
                bolt.lineTo(cx + hw, h * .42f);
                bolt.lineTo(cx + hw * .05f, h * .42f);
                bolt.close();
                p.setColor(0xFF17131F);
                cv.drawPath(bolt, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(stroke * .6f);
                p.setColor(Color.WHITE);
                cv.drawPath(bolt, p);
            }
        }
    }

    void setClock24(boolean h24) {
        String f = h24 ? "HH:mm" : "h:mm a";
        clock.setFormat24Hour(f);
        clock.setFormat12Hour(f);
    }

    /**
     * Shows which tab is active. crumbAt is the tab that holds a breadcrumb instead of its name (-1 for none) and
     * crumbText what it says (a back arrow comes with it). hue is the colour of the system being browsed, or -1.
     */
    void update(int activeTab, int crumbAt, String crumbText, int hue) {
        active = activeTab;
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean on = i == active;
            String before = String.valueOf(t.getText());
            boolean back = i == crumbAt;
            t.setText(back ? crumbText : names[i]);
            if (back) {
                android.graphics.Bitmap arrow = UiIcons.get(ctx, "arrow_left");
                if (arrow != null) {
                    android.graphics.drawable.BitmapDrawable ad = new android.graphics.drawable.BitmapDrawable(ctx.getResources(), arrow);
                    ad.setBounds(0, 0, host.px(34), host.px(34));
                    t.setCompoundDrawables(ad, null, null, null);
                    t.setCompoundDrawablePadding(host.px(12));
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
        pillBg.setColor(hue >= 0 ? (0x55000000 | (0xFFFFFF & Color.HSVToColor(new float[]{hue, .45f, 1f}))) : DEFAULT_PILL);
        applyTabWeights(crumbAt);
        glidePill();
    }

    /** A small, friendly pulse of the highlight: it swells a little and settles back with a bounce. */
    void pulse() {
        tabPill.animate().cancel();
        tabPill.setPivotX(pillW / 2f);
        tabPill.setPivotY(tabPill.getHeight() / 2f);
        tabPill.setScaleX(1f);
        tabPill.setScaleY(1f);
        float grow = host.px(26); // the same growth in pixels both ways, so it swells in height as much as in width
        tabPill.animate().scaleX(1f + grow / Math.max(1, pillW)).scaleY(1f + grow / Math.max(1, tabPill.getHeight())).setDuration(140)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .withEndAction(() -> tabPill.animate().scaleX(1f).scaleY(1f).setDuration(380)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start())
                .start();
    }

    /** The tabs fade up and away (or come back): used while the details page is open. */
    void away(boolean away, long ms) {
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        tabHost.animate().cancel();
        tabHost.animate().alpha(away ? 0f : 1f).translationY(away ? -host.px(44) : 0f).setStartDelay(0).setDuration(ms).setInterpolator(ease).start();
    }

    /** Stops the animations (the marquee repeats for ever): call when the screen is going away. */
    void stop() {
        stopMarquee();
        if (weightAnim != null) weightAnim.cancel();
        if (pillAnim != null) pillAnim.cancel();
    }

    /** A pause or resume of the marquee with the screen. */
    void pause(boolean paused) {
        if (marquee == null) return;
        if (paused) marquee.pause(); else marquee.resume();
    }

    private void stopMarquee() {
        if (marquee != null) marquee.cancel();
        marquee = null;
        if (marqueeTab != null) marqueeTab.setScrollX(0);
        marqueeTab = null;
    }

    /** A system name too long for its place in the tab bar slides slowly to its end and back, pausing at each end. */
    private void startMarquee(TextView t) {
        if (t == null || active < 0 || active >= tabViews.size() || tabViews.get(active) != t || t.getWidth() == 0) return;
        stopMarquee();
        float avail = t.getWidth() - t.getTotalPaddingLeft() - t.getTotalPaddingRight();
        float over = t.getPaint().measureText(String.valueOf(t.getText())) - avail;
        if (over < 4f) return;
        marqueeTab = t;
        marquee = android.animation.ValueAnimator.ofFloat(0f, over + host.px(6));
        marquee.setDuration(Math.max(2600L, (long) (over / host.scale() / 40f * 1000f))); // about 40 mockup pixels a second
        marquee.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        marquee.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        marquee.setInterpolator(x -> {
            float f = Math.max(0f, Math.min(1f, (x - .18f) / .64f)); // rests at both ends
            return f * f * (3f - 2f * f);
        });
        marquee.addUpdateListener(a -> t.setScrollX(Math.round((Float) a.getAnimatedValue())));
        marquee.start();
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
            total += tabNat[i] + host.px(58);
            if (i != crumb) others += tabNat[i] + host.px(24);
        }
        final float[] tw = new float[n], tm = new float[n], tp = new float[n];
        for (int i = 0; i < n; i++) {
            boolean tight = crumb >= 0 && i != crumb;
            tw[i] = crumb < 0 ? tabNat[i] + host.px(58) : i == crumb ? total - others : tabNat[i] + host.px(24);
            tm[i] = tight ? host.px(4) : host.px(11);
            tp[i] = tight ? host.px(8) : host.px(18);
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
            v.setPadding(Math.round(curP[i]), host.px(6), Math.round(curP[i]), host.px(10));
            v.setLayoutParams(lp);
        }
        if (weightAnim != null && weightAnim.isRunning()) movePill();
    }

    /** While the tabs slide, the highlight is placed from the same numbers the layout uses, so it never lags behind. */
    private void movePill() {
        if (active < 0 || active >= tabViews.size() || tabPill == null || pillW == 0) return;
        float sumW = 0, sumM = 0;
        for (int i = 0; i < curW.length; i++) {
            sumW += curW[i];
            sumM += 2 * curM[i];
        }
        float width = tabHost.getWidth() > 0 ? tabHost.getWidth() : host.px(1000);
        float avail = width - 2 * host.px(52) - sumM, x = host.px(52);
        for (int j = 0; j < active; j++) x += 2 * curM[j] + avail * curW[j] / sumW;
        x += curM[active];
        pillX = Math.round(x);
        pillW = Math.round(avail * curW[active] / sumW);
        FrameLayout.LayoutParams l2 = (FrameLayout.LayoutParams) tabPill.getLayoutParams();
        l2.width = pillW;
        tabPill.setTranslationX(pillX);
        tabPill.setLayoutParams(l2);
    }

    /** Moves the highlight to the active tab once the bar has been laid out with its new labels. */
    private void glidePill() {
        if (tabHost == null) return;
        tabHost.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                tabHost.getViewTreeObserver().removeOnPreDrawListener(this);
                if (weightAnim != null && weightAnim.isRunning()) return true; // the sliding tabs place the highlight themselves
                if (active < 0 || active >= tabViews.size()) return true;
                TextView t = tabViews.get(active);
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
        tabs.invalidate();
    }
}
