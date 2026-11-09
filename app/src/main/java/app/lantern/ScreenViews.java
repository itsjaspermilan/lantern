package app.lantern;

/**
 * The ways the Nintendo DS's two screens can be shown. Each is one value of melonDS's "Screen Layout" setting; the
 * big/small ones make one screen large and the other small. The player cycles through the ones the person keeps
 * "in the cycle" with a button of their choice.
 */
final class ScreenViews {
    private ScreenViews() {}

    static final String OPTION = "melonds_screen_layout";
    static final String[] NAMES = {
            "Both, one above the other", "Both, side by side", "Top screen big", "Bottom screen big",
            "Top screen only", "Bottom screen only"};
    /** The core's layout for each view. The big ones use side by side: Lantern redraws that one bigger itself. */
    static final String[] LAYOUT = {"Top/Bottom", "Left/Right", "Left/Right", "Left/Right", "Top Only", "Bottom Only"};
    /** Lantern's own setting (not the core's): 1 draws the top screen big, 2 the bottom one, 0 as the core drew it. */
    static final String BIG_OPTION = "lantern_screen_big";
    static final String[] BIG = {"0", "0", "1", "2", "0", "0"};
    /** Kept in the cycle until the person says otherwise: both, top big, bottom big. */
    static final String DEFAULT_CYCLE = "0,2,3";

    static boolean contains(String cycle, int view) {
        for (String p : cycle.split(",")) if (p.equals(String.valueOf(view))) return true;
        return false;
    }

    static String with(String cycle, int view, boolean on) {
        StringBuilder b = new StringBuilder();
        for (int v = 0; v < NAMES.length; v++) {
            if (v == view ? on : contains(cycle, v)) {
                if (b.length() > 0) b.append(',');
                b.append(v);
            }
        }
        return b.toString();
    }

    /** The view after {@code current} that is in the cycle, or -1 if none is. */
    static int next(String cycle, int current) {
        for (int k = 1; k <= NAMES.length; k++) {
            int v = (current + k + NAMES.length) % NAMES.length;
            if (contains(cycle, v)) return v;
        }
        return -1;
    }

    /** Which view the core's layout and Lantern's big-screen setting add up to, or -1. */
    static int viewOf(String layout, String big) {
        for (int i = 0; i < LAYOUT.length; i++) if (LAYOUT[i].equals(layout) && BIG[i].equals(big)) return i;
        return -1;
    }
}
