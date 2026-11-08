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

    PlayPill(Context c) {
        super(c);
    }

    /** The button takes the colour of the game: lighter at the top left, deeper at the bottom right, drifting a little in hue. */
    void setHue(int hue) {
        colours = colours(hue);
        fill = null;
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
            paint.setShader(fill);
            box.set(0, 0, w, h);
            c.drawRoundRect(box, h / 2f, h / 2f, paint);
            paint.setShader(null);
            float gap = h * .1f, stroke = Math.max(2f, h * .04f), off = gap + stroke / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(Color.WHITE);
            box.set(-off, -off, w + off, h + off);
            c.drawRoundRect(box, h / 2f + off, h / 2f + off, paint);
        }
        super.onDraw(c); // the button's own text on top
    }
}
