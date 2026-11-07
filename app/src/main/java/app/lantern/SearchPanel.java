package app.lantern;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen keyboard and the live results beside it. It owns what is typed and which key is chosen; the launcher
 * (the {@link Host}) owns the shelf the finished search is shown on. The tab bar stays visible above it.
 */
final class SearchPanel {
    /** What the panel needs from the launcher. */
    interface Host {
        /** Mockup pixels to screen pixels. */
        int px(float mockupPx);

        /** A text view in the launcher's style (size in mockup pixels). */
        TextView text(String text, float mockupPx, int color, boolean bold);

        /** Games whose title or system matches every word. */
        List<Game> matches(String query);

        /** The search now shown on the shelf, or null. */
        String currentQuery();

        /** Show these results on the shelf (null: no search) with a game focused (null: the first). */
        void showResults(String query, String focusId);

        /** The panel has opened or closed: hints and the empty-shelf message follow. */
        void searchVisible(boolean visible);

        /** L1 (-1) or R1 (+1) pressed in the panel: leave it and move that many tabs. */
        void moveTab(int dir);
    }

    private static final String[][] KEYS = {
        {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
        {"z", "x", "c", "v", "b", "n", "m", "Space", "Del", "OK"},
    };

    private final Context ctx;
    private final Host host;
    private final FrameLayout view;
    private boolean open;
    private String draft = "";
    private int kbRow, kbCol;

    SearchPanel(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        view = new FrameLayout(ctx);
        view.setVisibility(View.GONE);
    }

    /** The layer to add to the screen (it covers it, apart from the tab bar). */
    FrameLayout view() {
        return view;
    }

    boolean isOpen() {
        return open;
    }

    /**
     * Opens the panel. dir says where it comes from: +1 the right (R1 onto the Search tab), -1 the left (L1), 0 below
     * (the X button).
     */
    void open(int dir) {
        open = true;
        String q = host.currentQuery();
        draft = q == null ? "" : q;
        kbRow = 1; // starts on Q
        kbCol = 0;
        host.searchVisible(true);
        render();
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(150).start();
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        for (int ci = 1; ci < view.getChildCount(); ci++) {
            View cv = view.getChildAt(ci);
            boolean results = ((FrameLayout.LayoutParams) cv.getLayoutParams()).leftMargin == host.px(1210);
            cv.setAlpha(0f);
            long delay = Math.min(ci, 30) * 6L;
            if (dir != 0) {
                // From the side of the button pressed, in a wave that starts on that side.
                cv.setTranslationX(dir * host.px(170));
                float x = ((FrameLayout.LayoutParams) cv.getLayoutParams()).leftMargin;
                delay = (long) ((dir > 0 ? host.px(1920) - x : x) / host.px(1920) * 170f);
            } else if (results) {
                cv.setTranslationX(host.px(140));
            } else {
                cv.setTranslationY(host.px(160));
            }
            cv.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(delay).setDuration(300).setInterpolator(ease).start();
        }
    }

    void close() {
        open = false;
        view.setVisibility(View.GONE);
        view.removeAllViews();
        host.searchVisible(false);
    }

    /** Shows the draft's matches on the shelf (an empty draft clears the search). */
    private void apply() {
        String q = draft.trim();
        close();
        host.showResults(q.isEmpty() ? null : q, null);
    }

    /** Touch: a tapped result closes the panel with those matches on the shelf and that game focused. */
    private void pick(Game g) {
        String q = draft.trim();
        close();
        host.showResults(q.isEmpty() ? null : q, g.id);
    }

    private void type(String c) {
        if (draft.length() < 40) draft += c;
        render();
    }

    private void backspace() {
        if (!draft.isEmpty()) draft = draft.substring(0, draft.length() - 1);
        render();
    }

    private void press(String k) {
        if (k.equals("OK")) apply();
        else if (k.equals("Del")) backspace();
        else type(k.equals("Space") ? " " : k);
    }

    /** A key pressed while the panel is open. Always consumed. */
    boolean key(int keyCode, KeyEvent event, boolean first) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                kbCol = (kbCol + KEYS[kbRow].length - 1) % KEYS[kbRow].length;
                render();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                kbCol = (kbCol + 1) % KEYS[kbRow].length;
                render();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                kbRow = (kbRow + KEYS.length - 1) % KEYS.length;
                kbCol = Math.min(kbCol, KEYS[kbRow].length - 1);
                render();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                kbRow = (kbRow + 1) % KEYS.length;
                kbCol = Math.min(kbCol, KEYS[kbRow].length - 1);
                render();
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) press(KEYS[kbRow][kbCol]);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_DEL:
                backspace();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) apply();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (first) {
                    close();
                    host.moveTab(keyCode == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                if (first) close();
                return true;
            default:
                // A real keyboard works too.
                int ch = event.getUnicodeChar();
                if (ch > 0 && (Character.isLetterOrDigit(ch) || ch == ' ')) type(String.valueOf((char) Character.toLowerCase(ch)));
                return true;
        }
    }

    private void render() {
        view.removeAllViews();
        View dim = new View(ctx);
        dim.setBackgroundColor(0xF20B0A10);
        dim.setClickable(true); // swallows touches so the shelf underneath is not tapped
        FrameLayout.LayoutParams dimp = new FrameLayout.LayoutParams(-1, -1);
        dimp.topMargin = host.px(104); // the tab bar stays above the keyboard: L1 and R1 still move between tabs
        view.addView(dim, dimp);

        TextView head = host.text("Search", 56, Color.WHITE, true);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hp.leftMargin = host.px(HomeActivity.EDGE_L);
        hp.topMargin = host.px(110);
        view.addView(head, hp);

        TextView box = host.text(draft.isEmpty() ? "Type a game name" : draft + "│", 44, draft.isEmpty() ? 0x66FFFFFF : Color.WHITE, false);
        box.setSingleLine(true);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(host.px(28), 0, host.px(28), 0);
        GradientDrawable bd = new GradientDrawable();
        bd.setCornerRadius(host.px(18));
        bd.setColor(0x1FFFFFFF);
        bd.setStroke(host.px(3), HomeActivity.AMBER);
        box.setBackground(bd);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(host.px(1040), host.px(96), Gravity.TOP | Gravity.START);
        bp.leftMargin = host.px(HomeActivity.EDGE_L);
        bp.topMargin = host.px(215);
        view.addView(box, bp);

        // The letters: a grid chosen with the D-pad.
        int cell = 92, gap = 10;
        for (int r = 0; r < KEYS.length; r++) {
            for (int c = 0; c < KEYS[r].length; c++) {
                final String k = KEYS[r][c];
                boolean on = r == kbRow && c == kbCol;
                boolean word = k.length() > 1;
                TextView v = host.text(word ? k : k.toUpperCase(), word ? 26 : 38, on ? 0xFF17131F : Color.WHITE, true);
                v.setGravity(Gravity.CENTER);
                GradientDrawable kd = new GradientDrawable();
                kd.setCornerRadius(host.px(16));
                kd.setColor(on ? Color.WHITE : k.equals("OK") ? 0x40FFB454 : 0x1AFFFFFF);
                v.setBackground(kd);
                FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(host.px(cell), host.px(cell - 8), Gravity.TOP | Gravity.START);
                kp.leftMargin = host.px(HomeActivity.EDGE_L + (r == 2 ? (cell + gap) / 2 : 0) + c * (cell + gap)); // the home row is indented, as on a keyboard
                kp.topMargin = host.px(350 + r * (cell - 8 + gap));
                final int fr = r, fc = c;
                v.setOnClickListener(x -> {
                    kbRow = fr;
                    kbCol = fc;
                    press(k);
                });
                view.addView(v, kp);
            }
        }

        // Touch: close, and show the results.
        TextView cancel = host.text("Cancel", 28, Color.WHITE, true);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cdr = new GradientDrawable();
        cdr.setCornerRadius(host.px(99));
        cdr.setColor(0x24FFFFFF);
        cancel.setBackground(cdr);
        cancel.setOnClickListener(x -> close());
        FrameLayout.LayoutParams cap = new FrameLayout.LayoutParams(host.px(170), host.px(64), Gravity.TOP | Gravity.START);
        cap.leftMargin = host.px(HomeActivity.EDGE_L + 870);
        cap.topMargin = host.px(120);
        view.addView(cancel, cap);

        // Live matches on the right.
        List<Game> hits = draft.trim().isEmpty() ? new ArrayList<Game>() : host.matches(draft);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        String sum = draft.trim().isEmpty() ? "Pick letters, then press X to see the games"
                : hits.size() + (hits.size() == 1 ? " game" : " games");
        TextView sumView = host.text(sum, 28, HomeActivity.AMBER, true);
        if (!hits.isEmpty()) {
            sumView.setText(sum + "  —  tap to show all");
            sumView.setOnClickListener(x -> apply());
        }
        col.addView(sumView);
        for (int i = 0; i < Math.min(hits.size(), 8); i++) {
            final Game g = hits.get(i);
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = host.text(g.title, 30, Color.WHITE, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(host.text(g.system, 22, 0x99FFFFFF, false));
            row.setPadding(0, host.px(6), 0, host.px(6));
            row.setOnClickListener(x -> pick(g));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = host.px(12);
            col.addView(row, rp);
        }
        if (hits.size() > 8) {
            TextView more = host.text("and " + (hits.size() - 8) + " more", 24, 0x99FFFFFF, false);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
            mp.topMargin = host.px(18);
            col.addView(more, mp);
        }
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(host.px(600), -2, Gravity.TOP | Gravity.START);
        cp.leftMargin = host.px(1210);
        cp.topMargin = host.px(232);
        view.addView(col, cp);
    }
}
