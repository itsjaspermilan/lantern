package app.lantern;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** The room behind the shelf: two soft pools of the focused game's colours over near-black. */
final class Glow extends View {
    private static final ArgbEvaluator EVAL = new ArgbEvaluator();
    private final Paint p = new Paint();
    private final float s;
    private int a1 = 0xFF2A2438;
    private int a2 = 0xFF14121C;
    private ValueAnimator anim;
    private Bitmap cur, prev;
    private float fade = 1f;
    private ValueAnimator artAnim;

    Glow(Context c, float s) {
        super(c);
        this.s = s;
    }

    /** Fades to a new pair of colours. */
    void to(final int c1, final int c2) {
        final int f1 = a1, f2 = a2;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(600);
        anim.addUpdateListener(v -> {
            float t = (Float) v.getAnimatedValue();
            a1 = (Integer) EVAL.evaluate(t, f1, c1);
            a2 = (Integer) EVAL.evaluate(t, f2, c2);
            invalidate();
        });
        anim.start();
    }

    /** Crossfades the soft, blurred cover of the focused game across the whole screen (null: none). */
    void art(Bitmap b) {
        if (b == cur) return;
        prev = cur;
        cur = b;
        if (artAnim != null) artAnim.cancel();
        artAnim = ValueAnimator.ofFloat(0f, 1f);
        artAnim.setDuration(500);
        fade = 0f;
        artAnim.addUpdateListener(v -> {
            fade = (Float) v.getAnimatedValue();
            invalidate();
        });
        artAnim.start();
    }

    private void drawArt(Canvas c, Bitmap b, float alpha) {
        if (b == null || alpha <= 0f) return;
        p.setAlpha((int) (255 * alpha));
        p.setFilterBitmap(true);
        c.drawBitmap(b, null, new android.graphics.RectF(0, 0, getWidth(), getHeight()), p);
        p.setAlpha(255);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(0xFF0B0A10);
        drawArt(c, prev, .50f * (1f - fade));
        drawArt(c, cur, .50f * fade);
        pool(c, w * .28f, h * .45f, 1250 * s, a1, .70f);
        pool(c, w * .85f, h * .10f, 1000 * s, a2, .60f);
        p.setShader(new LinearGradient(0, h * .55f, 0, h, 0x00000000, 0xA6000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    private void pool(Canvas c, float cx, float cy, float r, int color, float alpha) {
        int solid = (color & 0x00FFFFFF) | ((int) (alpha * 255) << 24);
        p.setShader(new RadialGradient(cx, cy, r, new int[]{solid, color & 0x00FFFFFF},
                new float[]{0f, .7f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, getWidth(), getHeight(), p);
        p.setShader(null);
    }
}
