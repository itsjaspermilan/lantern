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

        /** An emulator core was added or removed: subtitles and the Play hint follow. */
        void coresChanged();
    }

    private static final int APPS = 0, SHOW_APPS = 1, ART = 2, FILES = 3, CLOCK = 4, CORES = 5;
    private static final int WIDTH = 760;

    private final Context ctx;
    private final Host host;
    private final FrameLayout view;
    private boolean open;
    private int index;
    /** Showing the Emulators page instead of the main rows; direct when it was opened from Play (Back then closes). */
    private boolean coresPage, direct;
    /** The core whose removal waits for a second A, and each core's download state ("42%", "Failed: ..."). */
    private String confirmRemove;
    private final java.util.Map<String, String> state = new java.util.HashMap<>();

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
        l.add(CORES);
        if (!host.hasFileAccess()) l.add(FILES);
        return l;
    }

    void open() {
        openAt(false, null);
    }

    /** Opens the Emulators page with the given core focused (from Play on a game whose system has no core yet). */
    void openCores(String lib) {
        openAt(true, lib);
    }

    private void openAt(boolean cores, String lib) {
        open = true;
        coresPage = cores;
        direct = cores;
        confirmRemove = null;
        index = 0;
        if (lib != null) for (int i = 0; i < Cores.CATALOG.length; i++) if (Cores.CATALOG[i].lib.equals(lib)) index = i;
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
            case CORES:
                coresPage = true;
                index = 0;
                confirmRemove = null;
                render();
                break;
            default:
        }
    }

    /** A on a core: download it, or (on an installed, downloaded one) ask, then remove it. */
    private void chooseCore(Cores.Info core) {
        if (CoreInstaller.busy(core.lib) || Cores.packaged(ctx, core.lib)) return;
        if (Cores.fileForLib(ctx, core.lib) != null) {
            if (core.lib.equals(confirmRemove)) {
                confirmRemove = null;
                CoreInstaller.remove(ctx, core.lib);
                state.remove(core.lib);
                host.coresChanged();
            } else {
                confirmRemove = core.lib;
            }
            render();
            return;
        }
        state.put(core.lib, "0%");
        render();
        CoreInstaller.install(ctx, core.lib, new CoreInstaller.Listener() {
            @Override
            public void progress(String lib, int percent) {
                state.put(lib, percent < 0 ? "Downloading" : percent + "%");
                if (open && coresPage) render();
            }

            @Override
            public void done(String lib, String error) {
                if (error == null) state.remove(lib);
                else state.put(lib, "Failed: " + error);
                host.coresChanged();
                if (open && coresPage) render();
            }
        });
    }

    /** Back on the Emulators page: to the main rows, or out if it was opened from Play. */
    private void back() {
        if (coresPage && !direct) {
            coresPage = false;
            index = items().indexOf(CORES);
            render();
        } else {
            close();
        }
    }

    /** A key pressed while the menu is open. Always consumed. */
    boolean key(int keyCode, boolean first) {
        if (coresPage) return coreKey(keyCode, first);
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
                int it = items.get(index);
                if (first && it != APPS && it != FILES && it != CORES) choose(items.get(index));
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

    private boolean coreKey(int keyCode, boolean first) {
        int n = Cores.CATALOG.length;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (index > 0) { index--; confirmRemove = null; render(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (index < n - 1) { index++; confirmRemove = null; render(); }
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) chooseCore(Cores.CATALOG[index]);
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (first) back();
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (first) close();
                return true;
            default:
                return true;
        }
    }

    private String coreStatus(Cores.Info c, boolean on) {
        String s = state.get(c.lib);
        if (CoreInstaller.busy(c.lib)) return s == null ? "Downloading" : s;
        if (Cores.packaged(ctx, c.lib)) return "Built in";
        if (Cores.fileForLib(ctx, c.lib) != null) return c.lib.equals(confirmRemove) ? "A again to remove" : on ? "Installed  ·  A removes" : "Installed";
        if (s != null) return s;
        return on ? "A to download" : "Not added";
    }

    private void renderCores(LinearLayout panel) {
        panel.addView(host.text("Emulators", 52, Color.WHITE, true));
        for (int i = 0; i < Cores.CATALOG.length; i++) {
            final Cores.Info c = Cores.CATALOG[i];
            boolean on = i == index;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(host.px(28), 0, host.px(28), 0);
            GradientDrawable rd = new GradientDrawable();
            rd.setCornerRadius(host.px(16));
            rd.setColor(on ? Color.WHITE : 0x14FFFFFF);
            row.setBackground(rd);
            int ink = on ? 0xFF17131F : Color.WHITE;
            LinearLayout top = new LinearLayout(ctx);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = host.text(c.name, 30, ink, true);
            nm.setSingleLine(true);
            top.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
            String status = coreStatus(c, on);
            boolean good = status.startsWith("Installed") || status.equals("Built in");
            boolean bad = status.startsWith("Failed");
            int sc = good ? (on ? 0xFF1F7A3A : 0xFF8BE28F) : bad ? (on ? 0xFFB3261E : 0xFFFF8A80) : on ? 0xBF17131F : 0xB3FFFFFF;
            top.addView(host.text(status, 24, sc, good));
            row.addView(top);
            TextView plays = host.text(c.plays + " · " + c.licence, 20, on ? 0x9917131F : 0x80FFFFFF, false);
            plays.setSingleLine(true);
            plays.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(plays);
            final int idx = i;
            row.setOnClickListener(x -> { index = idx; chooseCore(c); });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, host.px(106));
            rp.topMargin = host.px(i == 0 ? 32 : 12);
            panel.addView(row, rp);
        }
        TextView note = host.text("Emulators are downloaded from libretro's build server (buildbot.libretro.com) into Lantern's"
                + " own storage. Each has its own licence: the non-commercial ones may not be used to make money.", 22, 0x99FFFFFF, false);
        note.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = host.px(26);
        panel.addView(note, np);
    }

    private int installedCount() {
        int n = 0;
        for (Cores.Info c : Cores.CATALOG) if (Cores.fileForLib(ctx, c.lib) != null) n++;
        return n;
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
        if (coresPage) {
            renderCores(panel);
            view.addView(panel, new FrameLayout.LayoutParams(host.px(WIDTH), -1, Gravity.START));
            return;
        }
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
                case CORES: name = "Emulators"; value = installedCount() + " of " + Cores.CATALOG.length + "  ›"; break;
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
