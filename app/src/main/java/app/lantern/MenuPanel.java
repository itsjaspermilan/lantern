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
 * The menu that slides in from the left when Select is pressed: the Android apps list, and the switches (apps on every
 * shelf, cover art, 12 or 24 hour clock). It draws the rows and handles the keys; the launcher (the {@link Host})
 * keeps the settings and does what each row means.
 */
final class MenuPanel {
    /** What the menu needs from the launcher. */
    interface Host {
        int px(float mockupPx);

        TextView text(String text, float mockupPx, int color, boolean bold);

        int appCount();

        boolean showApps();

        boolean coverArt();

        boolean clock24();

        boolean hasFileAccess();

        void toggleShowApps();

        void toggleCoverArt();

        void toggleClock();

        /** The menu has closed (a row that leaves it was chosen): show the Android apps list. */
        void openAppsList();

        void askForFileAccess();

        /** The menu opened or closed: the hints follow. */
        void menuVisible(boolean visible);

        /** The screen behind the menu moves aside (dx in mockup pixels, 0 to come back). */
        void shiftScreen(float dx, long ms);
    }

    private static final int APPS = 0, SHOW_APPS = 1, ART = 2, FILES = 3, CLOCK = 4;
    private static final int WIDTH = 700;

    private final Context ctx;
    private final Host host;
    private final FrameLayout view;
    private boolean open;
    private int index;

    MenuPanel(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        view = new FrameLayout(ctx);
        view.setVisibility(View.GONE);
    }

    /** The layer to add to the screen. */
    FrameLayout view() {
        return view;
    }

    boolean isOpen() {
        return open;
    }

    private List<Integer> items() {
        List<Integer> l = new ArrayList<>();
        l.add(APPS);
        l.add(SHOW_APPS);
        l.add(ART);
        l.add(CLOCK);
        if (!host.hasFileAccess()) l.add(FILES);
        return l;
    }

    void open() {
        open = true;
        index = 0;
        render();
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(150).start();
        // The Select button is on the left, so the menu comes from the left and pushes the screen a little to the right.
        View panel = view.getChildAt(view.getChildCount() - 1);
        panel.setTranslationX(-host.px(WIDTH));
        panel.animate().translationX(0f).setDuration(280).setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        host.shiftScreen(80, 280);
        host.menuVisible(true);
    }

    void close() {
        open = false;
        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(2f);
        host.shiftScreen(0, 240);
        View panel = view.getChildAt(view.getChildCount() - 1);
        if (panel != null && view.getVisibility() == View.VISIBLE) {
            panel.animate().translationX(-host.px(WIDTH)).setDuration(220).setInterpolator(ease).withEndAction(() -> {
                if (open) return;
                view.setVisibility(View.GONE);
                view.removeAllViews();
            }).start();
            view.animate().alpha(0f).setDuration(220).start();
        } else {
            view.setVisibility(View.GONE);
            view.removeAllViews();
        }
        host.menuVisible(false);
    }

    private void choose(int item) {
        switch (item) {
            case APPS:
                close();
                host.openAppsList();
                break;
            case SHOW_APPS:
                host.toggleShowApps(); // the shelf behind the menu follows the switch
                render();
                break;
            case ART:
                host.toggleCoverArt();
                render();
                break;
            case CLOCK:
                host.toggleClock();
                render();
                break;
            case FILES:
                close();
                host.askForFileAccess();
                break;
            default:
        }
    }

    /** A key pressed while the menu is open. Always consumed. */
    boolean key(int keyCode, boolean first) {
        List<Integer> items = items();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (index > 0) { index--; render(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (index < items.size() - 1) { index++; render(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Left and right flip a switch, but do not open a list.
                if (first && items.get(index) != APPS && items.get(index) != FILES) choose(items.get(index));
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) choose(items.get(index));
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (first) close();
                return true;
            default:
                return true;
        }
    }

    private void render() {
        view.removeAllViews();
        View scrim = new View(ctx);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(x -> close());
        view.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true); // taps on the panel are not taps on the scrim
        panel.setPadding(host.px(HomeActivity.EDGE_L), host.px(70), host.px(48), host.px(40));
        GradientDrawable pd = new GradientDrawable();
        pd.setColor(0xF2141119);
        pd.setCornerRadii(new float[]{0, 0, host.px(28), host.px(28), host.px(28), host.px(28), 0, 0});
        panel.setBackground(pd);
        panel.addView(host.text("Menu", 52, Color.WHITE, true));

        List<Integer> items = items();
        for (int i = 0; i < items.size(); i++) {
            final int item = items.get(i);
            boolean on = i == index;
            String name, value;
            switch (item) {
                case APPS: name = "Android apps"; value = host.appCount() + "  ›"; break;
                case SHOW_APPS: name = "Show apps on every shelf"; value = host.showApps() ? "On" : "Off"; break;
                case ART: name = "Cover art"; value = host.coverArt() ? "On" : "Off"; break;
                case CLOCK: name = "Clock"; value = host.clock24() ? "24 hour" : "12 hour"; break;
                default: name = "Allow file access"; value = "›";
            }
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(host.px(28), 0, host.px(28), 0);
            GradientDrawable rd = new GradientDrawable();
            rd.setCornerRadius(host.px(16));
            rd.setColor(on ? Color.WHITE : 0x14FFFFFF);
            row.setBackground(rd);
            int ink = on ? 0xFF17131F : Color.WHITE;
            TextView nm = host.text(name, 30, ink, true);
            nm.setSingleLine(true);
            row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
            boolean toggle = item == SHOW_APPS || item == ART;
            TextView val = host.text(value, 28, toggle && value.equals("On") ? (on ? 0xFF1F7A3A : 0xFF8BE28F) : on ? 0xBF17131F : 0xB3FFFFFF, toggle);
            row.addView(val);
            final int idx = i;
            row.setOnClickListener(x -> { index = idx; choose(item); });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, host.px(88));
            rp.topMargin = host.px(i == 0 ? 36 : 14);
            panel.addView(row, rp);
        }
        TextView note = host.text("Apps are hidden from the shelves unless the switch is on. Open the list here any time.", 24, 0x99FFFFFF, false);
        note.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = host.px(30);
        panel.addView(note, np);
        view.addView(panel, new FrameLayout.LayoutParams(host.px(WIDTH), -1, Gravity.START));
    }
}
