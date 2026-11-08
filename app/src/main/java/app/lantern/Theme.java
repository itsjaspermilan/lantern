package app.lantern;

import android.content.Context;
import android.graphics.Color;

/**
 * The colours of the interface, as a few named roles instead of numbers scattered through the screens. A theme is
 * one set of roles; the person picks one in the menu and it is kept in the preferences. Each screen asks for
 * {@code Theme.t()} when it is built, so a change takes effect by rebuilding the screen.
 *
 * <p>The game's own colours (the glow behind the shelf, the Play button, generated covers) still come from the
 * game; a theme only says how strongly they show and what they sit on.
 */
final class Theme {
    /** The roles. Colours are 0xAARRGGBB. */
    final String name;
    /** What the screen sits on, and the panels over it. */
    final int base, panel;
    /** The room's two colours when nothing is focused. */
    final int glowA, glowB;
    /** How strongly the focused game's colours show in the room (1 = as designed). */
    final float glowAmount;
    /** The highlight: tab and selection details, the line above the title, the search frame. */
    final int accent;
    /** Text and icons, and the row that is selected (with its own dark text). */
    final int ink, selected, onSelected;

    private Theme(String name, int base, int panel, int glowA, int glowB, float glowAmount, int accent, int ink, int selected, int onSelected) {
        this.name = name;
        this.base = base;
        this.panel = panel;
        this.glowA = glowA;
        this.glowB = glowB;
        this.glowAmount = glowAmount;
        this.accent = accent;
        this.ink = ink;
        this.selected = selected;
        this.onSelected = onSelected;
    }

    /** The themes, in the order the menu steps through them. The first is the default. */
    private static final Theme[] ALL = {
            new Theme("Lantern", 0xFF0B0A10, 0xFF141119, 0xFF2A2438, 0xFF14121C, 1f, 0xFF8FE3CF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF17131F),
            new Theme("Ember", 0xFF0F0907, 0xFF1A110D, 0xFF3A2418, 0xFF1C110C, 1f, 0xFFFFB454, 0xFFFFF4E8, 0xFFFFF4E8, 0xFF22140B),
            new Theme("Ocean", 0xFF070B12, 0xFF0E1420, 0xFF1B2A44, 0xFF0D1522, 1f, 0xFF7CC4FF, 0xFFF2F8FF, 0xFFF2F8FF, 0xFF0C1626),
            new Theme("Night", 0xFF000000, 0xFF0A0A0A, 0xFF181818, 0xFF080808, .45f, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000),
    };

    private static Theme current = ALL[0];

    /** The theme in use. */
    static Theme t() {
        return current;
    }

    /** Reads the saved choice (call when a screen starts). */
    static void load(Context c) {
        String saved = c.getSharedPreferences("lantern", Context.MODE_PRIVATE).getString("theme", ALL[0].name);
        current = ALL[0];
        for (Theme t : ALL) if (t.name.equals(saved)) current = t;
    }

    /** Moves to the next (dir 1) or previous (dir -1) theme and saves it. */
    static void step(Context c, int dir) {
        int i = 0;
        for (int k = 0; k < ALL.length; k++) if (ALL[k] == current) i = k;
        current = ALL[(i + dir + ALL.length) % ALL.length];
        c.getSharedPreferences("lantern", Context.MODE_PRIVATE).edit().putString("theme", current.name).apply();
    }

    /** Text colour at a strength (0 to 255). */
    int ink(int alpha) {
        return withAlpha(ink, alpha);
    }

    /** The dark text on a selected row, at a strength (0 to 255). */
    int onSelected(int alpha) {
        return withAlpha(onSelected, alpha);
    }

    /** The accent at a strength (0 to 255). */
    int accent(int alpha) {
        return withAlpha(accent, alpha);
    }

    /** The base at a strength (0 to 255): for scrims and veils. */
    int base(int alpha) {
        return withAlpha(base, alpha);
    }

    /** The panel colour at a strength (0 to 255). */
    int panel(int alpha) {
        return withAlpha(panel, alpha);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /** The room's two colours for a game of this hue: the game's colours, a little dark, at the theme's strength. */
    int glowOne(float hue) {
        return Color.HSVToColor(new float[]{hue, .60f * Math.min(1f, glowAmount + .25f), .46f * glowAmount + .06f * (1f - glowAmount)});
    }

    int glowTwo(float hue) {
        return Color.HSVToColor(new float[]{(hue + 35) % 360, .60f * Math.min(1f, glowAmount + .25f), .28f * glowAmount + .04f * (1f - glowAmount)});
    }
}
