package app.lantern;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.TextView;

/**
 * Recent's Play / Resume button. It is always the selected thing on that page, so it wears the selection ring the
 * game tiles have: a white outline with a small gap between it and the button. The button fills itself with a
 * colour that runs on the diagonal. The parents must not clip their children, or the ring is cut off.
 */
final class PlayPill extends TextView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private int[] colours = colours(255);
    private LinearGradient fill;
    private int fillW, fillH;
    /** For a copy that grows into something else: how far its colours have gone to white, its corner radius (-1: a pill) and its ring's strength. */
    private float whiteMix, corner = -1f, ringAlpha = 1f;

    PlayPill(Context c) {
        super(c);
    }

    /** The button takes the colour of the game: lighter at the top left, deeper at the bottom right, drifting a little in hue. */
    void setHue(int hue) {
        colours = colours(hue);
        fill = null;
        invalidate();
    }

    /** Makes this button look exactly like another one (colours, text, size) so it can stand in for it. */
    void copyLook(PlayPill o) {
        colours = o.colours;
        fill = null;
        setText(o.getText());
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, o.getTextSize());
        setTextColor(Color.WHITE);
        setTypeface(o.getTypeface());
        setPadding(o.getPaddingLeft(), o.getPaddingTop(), o.getPaddingRight(), o.getPaddingBottom());
        setShadowLayer(o.getShadowRadius(), o.getShadowDx(), o.getShadowDy(), o.getShadowColor());
        setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
    }

    /** Colours drained toward white (0 to 1), the corner radius in pixels (-1 for a pill) and the ring's strength (0 to 1). */
    void setMorph(float white, float cornerPx, float ring) {
        whiteMix = white;
        corner = cornerPx;
        ringAlpha = ring;
        invalidate();
    }

    /** The button's three colours, for a shape that grows out of it. */
    int[] colours() {
        return colours;
    }

    private static int[] colours(int hue) {
        return new int[]{
                Color.HSVToColor(new float[]{(hue + 340) % 360, .50f, .82f}),
                Color.HSVToColor(new float[]{hue % 360, .62f, .70f}),
                Color.HSVToColor(new float[]{(hue + 40) % 360, .70f, .56f})};
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w > 0 && h > 0) {
            if (fill == null || fillW != (int) w || fillH != (int) h) { // steep: the colour changes along a slant, not just across
                fill = new LinearGradient(w * .3f, -h * .7f, w * .7f, h * 1.7f, colours, new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP);
                fillW = (int) w;
                fillH = (int) h;
            }
            paint.setStyle(Paint.Style.FILL);
            Shader sh = fill;
            if (whiteMix > 0f) {
                int[] cc = new int[colours.length];
                for (int k = 0; k < cc.length; k++) cc[k] = (Integer) new android.animation.ArgbEvaluator().evaluate(whiteMix, colours[k], Color.WHITE);
                sh = new LinearGradient(w * .3f, -h * .7f, w * .7f, h * 1.7f, cc, new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP);
            }
            float rad = corner >= 0f ? corner : h / 2f;
            paint.setShader(sh);
            box.set(0, 0, w, h);
            c.drawRoundRect(box, rad, rad, paint);
            paint.setShader(null);
            float gap = h * .1f, stroke = Math.max(2f, h * .04f), off = gap + stroke / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(Color.WHITE);
            paint.setAlpha(Math.round(255 * ringAlpha));
            box.set(-off, -off, w + off, h + off);
            if (ringAlpha > 0f) c.drawRoundRect(box, rad + off, rad + off, paint);
        }
        super.onDraw(c); // the button's own text on top
    }
}
