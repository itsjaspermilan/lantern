package app.lantern;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * Recent's Play / Resume button. It is always the selected thing on that page, so it shows it: a soft band of
 * light sweeps across it and a ring breathes outwards from its edge, over and over, while it is on screen.
 * The parents must not clip their children, or the ring is cut off at the button's edge.
 */
final class PlayPill extends TextView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private ValueAnimator anim;
    private float phase;

    PlayPill(Context c) {
        super(c);
    }

    @Override
    protected void onVisibilityChanged(android.view.View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE) start(); else stop();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void start() {
        if (anim != null) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(3200);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            phase = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private void stop() {
        if (anim != null) anim.cancel();
        anim = null;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            super.onDraw(c);
            return;
        }
        // The ring: it grows from the button's edge and fades, twice in every cycle.
        float rt = (phase * 2f) % 1f;
        float grow = h * .32f * rt;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, h * .05f));
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(120 * (1f - rt) * (1f - rt)));
        box.set(-grow, -grow, w + grow, h + grow);
        c.drawRoundRect(box, h / 2f + grow, h / 2f + grow, paint);

        // The band of light: crosses the button in the first part of the cycle, then rests.
        float bt = Math.max(0f, Math.min(1f, phase / .45f));
        if (bt > 0f && bt < 1f) {
            float band = h * 1.1f, x = -band + (w + band * 2f) * (bt * bt * (3f - 2f * bt));
            clip.reset();
            box.set(0, 0, w, h);
            clip.addRoundRect(box, h / 2f, h / 2f, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(x, 0, x + band, 0,
                    new int[]{0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF}, new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
            paint.setAlpha(255);
            c.drawRect(x, 0, x + band, h, paint);
            paint.setShader(null);
            c.restore();
        }
        super.onDraw(c); // the text on top
    }
}
