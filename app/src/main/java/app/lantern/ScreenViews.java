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
    static final String[] LAYOUT = {"Top/Bottom", "Left/Right", "Hybrid Top", "Hybrid Bottom", "Top Only", "Bottom Only"};
    /** For the big/small views: which screen is the small one (melonDS's "Hybrid Small Screen Mode"). */
    static final String SMALL_OPTION = "melonds_hybrid_small_screen";
    static final String[] SMALL = {null, null, "Bottom", "Top", null, null};
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

    static int indexOfLayout(String layout) {
        for (int i = 0; i < LAYOUT.length; i++) if (LAYOUT[i].equals(layout)) return i;
        return -1;
    }
}
