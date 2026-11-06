package app.lantern;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * The in-game menu, in the style of MinUI: one plain list over the frozen game. Continue, Save and
 * Load with a slot to pick (and a screenshot of what is in it), Options, Cheats when the game has
 * any, Quit. Laid out in the
 * 1920 x 1080 units of the mockup, like the rest of Lantern.
 */
final class PlayerMenu extends FrameLayout {
    /** What the menu needs from the player screen. */
    interface Host {
        String gameTitle();
        /** The frame the game was showing when the menu opened, or null. */
        Bitmap currentFrame();
        /** The screenshot stored with a slot, or null. */
        Bitmap slotPicture(int slot);
        /** When the slot was saved (milliseconds), or 0 if it is empty. */
        long slotTime(int slot);
        boolean saveSlot(int slot);
        boolean loadSlot(int slot);
        void continueGame();
        /** Saves the resume point and leaves the game. */
        void quit();
        void optionsChanged();
        void cheatsChanged();
    }

    static final String[] SCALING = {"Aspect", "Integer", "Fill"};
    static final String[] EFFECT = {"Off", "Scanlines"};
    static final String[] FAST = {"2×", "3×", "4×", "Max"};

    private static final int AMBER = 0xFFFFB454;
    private static final String[] MAIN = {"Continue", "Save", "Load", "Options", "Quit"};
    private static final String[] MAIN_CHEATS = {"Continue", "Save", "Load", "Options", "Cheats", "Quit"};
    private static final int CHEATS_SHOWN = 7;
    private static final String[] OPTIONS = {"Scaling", "Screen effect", "Fast-forward"};

    private final Host host;
    private float s = 1f;
    private int level, idx, slot = 1;
    private String message = "";
    private Bitmap current;
    /** Chosen option values, set by the player screen. */
    int scaling, effect, fast;
    /** This game's cheats, empty if it has none. Set by the player screen. */
    List<Cheats.Cheat> cheats = Collections.emptyList();

    PlayerMenu(Context c, Host host) {
        super(c);
        this.host = host;
        setBackgroundColor(0xB8000000);
        setVisibility(GONE);
        setClickable(true); // swallow touches so the game underneath is not poked
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    void open() {
        level = 0;
        idx = 0;
        message = "";
        current = host.currentFrame();
        setVisibility(VISIBLE);
        render();
    }

    void close() {
        setVisibility(GONE);
    }

    private int insetL, insetT, insetR, insetB;

    /** Space the system keeps for its bars. The menu is laid out inside what is left. */
    void setInsets(int l, int t, int r, int b) {
        if (l == insetL && t == insetT && r == insetR && b == insetB) return;
        insetL = l;
        insetT = t;
        insetR = r;
        insetB = b;
        if (isOpen()) post(this::render);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (isOpen()) post(this::render);
    }

    /** Handles one key press while the menu is open. Always consumes it. */
    boolean key(int code) {
        int count = names().length;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                idx = (idx + count - 1) % count;
                message = "";
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                idx = (idx + 1) % count;
                message = "";
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                change(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                change(1);
                break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                activate();
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (level > 0) { idx = level == 1 ? 3 : 4; level = 0; message = ""; }
                else { host.continueGame(); return true; }
                break;
            case KeyEvent.KEYCODE_BUTTON_MODE:
                host.continueGame();
                return true;
            default:
                return true;
        }
        render();
        return true;
    }

    private void change(int d) {
        message = "";
        if (level == 0) {
            if (idx == 1 || idx == 2) slot = (slot - 1 + d + 9) % 9 + 1;
            return;
        }
        if (level == 2) {
            Cheats.Cheat c = cheats.get(idx);
            c.on = !c.on;
            host.cheatsChanged();
            return;
        }
        if (idx == 0) scaling = (scaling + d + SCALING.length) % SCALING.length;
        else if (idx == 1) effect = (effect + d + EFFECT.length) % EFFECT.length;
        else fast = (fast + d + FAST.length) % FAST.length;
        host.optionsChanged();
    }

    private void activate() {
        if (level > 0) {
            change(1);
            return;
        }
        switch (idx) {
            case 0:
                host.continueGame();
                break;
            case 1:
                message = host.saveSlot(slot) ? "Saved to slot " + slot : "Could not save";
                break;
            case 2:
                if (host.slotTime(slot) == 0) message = "Slot " + slot + " is empty";
                else if (host.loadSlot(slot)) host.continueGame();
                else message = "Could not load slot " + slot;
                break;
            case 3:
                level = 1;
                idx = 0;
                message = "";
                break;
            case 4:
                if (!cheats.isEmpty()) {
                    level = 2;
                    idx = 0;
                    message = "";
                    break;
                }
                // fall through: without cheats, row 4 is Quit
            default:
                host.quit();
        }
    }

    private String[] names() {
        if (level == 1) return OPTIONS;
        if (level == 0) return cheats.isEmpty() ? MAIN : MAIN_CHEATS;
        String[] n = new String[cheats.size()];
        for (int i = 0; i < n.length; i++) n[i] = cheats.get(i).name;
        return n;
    }

    // ---- drawing ----

    private void render() {
        removeAllViews();
        int aw = getWidth() - insetL - insetR, ah = getHeight() - insetT - insetB;
        if (aw <= 0 || ah <= 0) return;
        s = Math.min(aw / 1920f, ah / 1080f);
        FrameLayout stage = new FrameLayout(getContext());
        stage.setClipChildren(false);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(u(1920), u(1080), Gravity.TOP | Gravity.START);
        sp.leftMargin = insetL + (aw - u(1920)) / 2;
        sp.topMargin = insetT + (ah - u(1080)) / 2;
        addView(stage, sp);

        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(level == 0 ? host.gameTitle() : level == 1 ? "Options" : "Cheats", 44, 0xA6FFFFFF, true);
        title.setSingleLine(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.bottomMargin = u(28);
        col.addView(title, tp);

        String[] names = names();
        boolean wide = level == 2; // cheat names are long, so the list takes the preview's place
        int first = wide ? Math.max(0, Math.min(idx - CHEATS_SHOWN / 2, names.length - CHEATS_SHOWN)) : 0;
        int end = wide ? Math.min(names.length, first + CHEATS_SHOWN) : names.length;
        for (int i = first; i < end; i++) {
            boolean on = i == idx;
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(u(36), 0, u(36), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(u(99));
            bg.setColor(on ? Color.WHITE : 0);
            row.setBackground(bg);
            int ink = on ? 0xFF17131F : Color.WHITE;
            TextView name = label(names[i], wide ? 34 : 50, ink, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(label(valueOf(i), 34, on ? 0xBF17131F : 0xBFFFFFFF, false));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(u(wide ? 1640 : 820), u(96));
            rp.bottomMargin = u(10);
            col.addView(row, rp);
        }
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(u(wide ? 1640 : 820), -2, Gravity.TOP | Gravity.START);
        cp.leftMargin = u(140);
        cp.topMargin = u(110);
        stage.addView(col, cp);

        if (!wide) addPreview(stage);

        TextView msg = label(message, 30, AMBER, true);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        mp.leftMargin = u(140);
        mp.bottomMargin = u(110);
        stage.addView(msg, mp);

        LinearLayout hints = new LinearLayout(getContext());
        hints.setGravity(Gravity.CENTER_VERTICAL);
        hint(hints, glyph("▲ ▼", Color.WHITE, true), "Choose");
        hint(hints, glyph("◀ ▶", Color.WHITE, true), "Change");
        hint(hints, glyph("A", 0xFF8BE28F, true), "Select");
        hint(hints, glyph("B", 0xFFFF8F8F, true), level == 0 ? "Continue" : "Back");
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, u(96), Gravity.BOTTOM | Gravity.START);
        hp.leftMargin = u(140);
        stage.addView(hints, hp);
    }

    private String valueOf(int i) {
        if (level == 0) return i == 1 || i == 2 ? "◀ Slot " + slot + " ▶" : i == 3 || (i == 4 && !cheats.isEmpty()) ? "›" : "";
        if (level == 2) return cheats.get(i).on ? "◀ On ▶" : "◀ Off ▶";
        String[] set = i == 0 ? SCALING : i == 1 ? EFFECT : FAST;
        int v = i == 0 ? scaling : i == 1 ? effect : fast;
        return "◀ " + set[v] + " ▶";
    }

    /** The picture on the right: the slot's screenshot on Save and Load, otherwise the current frame. */
    private void addPreview(FrameLayout stage) {
        boolean slotRow = level == 0 && (idx == 1 || idx == 2);
        Bitmap pic = slotRow ? host.slotPicture(slot) : current;
        long when = slotRow ? host.slotTime(slot) : 0;
        String caption = !slotRow ? "Current game" : when == 0 ? "Slot " + slot + " · Empty" : "Slot " + slot + " · " + ago(when);

        FrameLayout box = new FrameLayout(getContext());
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(u(14));
        border.setColor(0xFF0B0A10);
        border.setStroke(u(4), when == 0 && slotRow ? 0x59FFFFFF : 0xD9FFFFFF);
        box.setBackground(border);
        box.setClipToOutline(true);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), u(14));
            }
        });
        if (pic != null) {
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(pic);
            iv.setPadding(u(4), u(4), u(4), u(4));
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        TextView cap = label(caption, 28, Color.WHITE, true);
        cap.setBackgroundColor(0x8C000000);
        cap.setPadding(u(22), u(14), u(22), u(14));
        box.addView(cap, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(u(700), u(394), Gravity.TOP | Gravity.START);
        bp.leftMargin = u(1080);
        bp.topMargin = u(190);
        stage.addView(box, bp);
    }

    // ---- helpers ----

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        return (h / 24) + " days ago";
    }

    private int u(float mockupPx) {
        return Math.round(mockupPx * s);
    }

    private TextView label(String text, float mockupPx, int color, boolean bold) {
        TextView v = new TextView(getContext());
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, mockupPx * s);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView glyph(String text, int color, boolean round) {
        boolean one = text.length() == 1;
        TextView v = label(text, one ? 24 : 20, color, true);
        v.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setStroke(u(3), color);
        d.setCornerRadius(round ? u(99) : u(10));
        v.setBackground(d);
        v.setMinWidth(u(42));
        v.setMinHeight(u(42));
        v.setPadding(u(10), 0, u(10), 0);
        return v;
    }

    private void hint(LinearLayout row, View glyph, String text) {
        LinearLayout h = new LinearLayout(getContext());
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(glyph);
        TextView t = label(text, 28, 0xD1FFFFFF, false);
        t.setPadding(u(12), 0, 0, 0);
        h.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = u(44);
        row.addView(h, lp);
    }
}
