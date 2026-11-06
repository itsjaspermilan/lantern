package app.lantern;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Lantern's mark: an app-icon tile with a lantern whose flame breathes slowly. Drawn in code on a 76 x 76 sheet. */
final class LanternLogo extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path flame = new Path();
    private float breath = 1f;
    private ValueAnimator anim;

    LanternLogo(Context c) {
        super(c);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(2600);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            breath = .88f + .12f * (float) Math.sin(t * Math.PI); // a slow swell, not a flicker
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float k = Math.min(getWidth(), getHeight()) / 76f;
        c.save();
        c.scale(k, k);

        // The app-icon tile: dark warm glass with the lantern's light pooling inside it.
        r.set(4f, 4f, 72f, 72f);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 4, 0, 72, 0xFF2B2018, 0xFF120E16, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 17f, 17f, p);
        c.save();
        c.clipRect(4f, 4f, 72f, 72f);
        p.setShader(new RadialGradient(38, 40, 40, new int[]{(int) (0x80 * breath) << 24 | 0xFFA040, 0x00FFA040},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(38, 40, 40, p);
        c.restore();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(0x55FFC878);
        r.set(4.8f, 4.8f, 71.2f, 71.2f);
        c.drawRoundRect(r, 16.5f, 16.5f, p);
        c.save();
        c.scale(1.22f, 1.22f, 38f, 40f); // the lantern fills most of the tile

        // Handle.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.6f);
        p.setColor(0xFFF6DDAA);
        r.set(31.5f, 8f, 44.5f, 21f);
        c.drawArc(r, 180, 180, false, p);

        // Cap and base.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFF6DDAA);
        r.set(28f, 16.5f, 48f, 22f);
        c.drawRoundRect(r, 2.5f, 2.5f, p);
        r.set(29.5f, 54f, 46.5f, 59.5f);
        c.drawRoundRect(r, 2.5f, 2.5f, p);

        // Glass, lit from inside.
        r.set(28.5f, 22f, 47.5f, 54f);
        p.setShader(new LinearGradient(0, 22, 0, 54, 0xFFFFD27A, 0xFFFF8A3D, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 6.5f, 6.5f, p);
        p.setShader(null);

        // The flame, brighter at its heart, swelling slowly.
        float fx = 38f, fy = 40f, s = breath;
        flame.reset();
        flame.moveTo(fx, fy - 10.5f * s);
        flame.cubicTo(fx + 7f * s, fy - 3f * s, fx + 6.5f * s, fy + 7f * s, fx, fy + 9f * s);
        flame.cubicTo(fx - 6.5f * s, fy + 7f * s, fx - 7f * s, fy - 3f * s, fx, fy - 10.5f * s);
        p.setColor(0xFFFFF3C9);
        c.drawPath(flame, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(fx, fy + 3.5f * s, 3.2f * s, p);

        // Frame bars over the glass.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(0x66401E00);
        c.drawLine(34.7f, 22.5f, 34.7f, 53.5f, p);
        c.drawLine(41.3f, 22.5f, 41.3f, 53.5f, p);
        p.setStyle(Paint.Style.FILL);
        c.restore();
        c.restore();
    }
}
