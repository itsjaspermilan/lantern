package app.lantern;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs one game in a libretro core. Lives in its own process (see the manifest), so a core that
 * crashes takes down this screen and not the launcher.
 *
 * The menu opens with the Guide button (BUTTON_MODE), Select and Start together, or Back. Leaving the
 * game any other way (Home, the menu's Quit) saves a resume point first. Hold R2 to fast-forward.
 * The Retroid's own buttons may report differently; the key choices are in dispatchKeyEvent.
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback, PlayerMenu.Host {
    static final String EXTRA_CORE = "core", EXTRA_ROM = "rom", EXTRA_SYS = "sys", EXTRA_SAVE = "save",
            EXTRA_START = "start", EXTRA_TITLE = "title", EXTRA_SYSID = "sysid";

    private FrameLayout root, picture;
    private SurfaceView surface;
    private ScanlineView scan;
    private TextView hud;
    private PlayerMenu menu;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Bitmap> slotCache = new HashMap<>();
    private SharedPreferences prefs;
    private File romFile, cheatFile;
    private long sessionStart;
    private Controls controls;
    /** Whether the last key came from a pad labelled the Nintendo way (the Retroid's own). */
    private boolean nintendoPad = true;
    private String sysId, title;
    private boolean running, resumeSaved, selectDown, startDown, failed;
    /** The screen went off while playing: the game was put away at its resume point and comes back on waking. */
    private boolean asleep, screenOff, menuWasOpen;
    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            screenOff = true;
        }
    };
    /** Height of the navigation bar that covers the bottom of the screen, in pixels. */
    private int navReserve;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            String status = Player.nativeStatus();
            hud.setText(status);
            // A game that could not start must say so, not sit on a black screen.
            if (status.startsWith("ERROR")) {
                failed = true;
                resumeSaved = true; // nothing ran, so there is nothing to save
                hud.setVisibility(View.VISIBLE);
                hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                hud.setText(status + "\nPress Back to return.");
            }
            fitPicture();
            scan.invalidate();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        asleep = state != null; // the system ended this process while it slept: come back from the resume point
        registerReceiver(screenReceiver, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
        romFile = new File(getIntent().getStringExtra(EXTRA_ROM));
        sysId = getIntent().getStringExtra(EXTRA_SYSID);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        prefs = getSharedPreferences("player_opts", MODE_PRIVATE); // this process only
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        picture = new FrameLayout(this);
        surface = new SurfaceView(this);
        picture.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        scan = new ScanlineView(this);
        picture.addView(scan, new FrameLayout.LayoutParams(-1, -1));
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        hud = new TextView(this); // debugging line, toggled with F3
        hud.setTextColor(Color.WHITE);
        hud.setShadowLayer(4, 0, 1, Color.BLACK);
        hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hp.setMargins(24, 16, 24, 0);
        root.addView(hud, hp);

        menu = new PlayerMenu(this, this);
        menu.scaling = prefs.getInt(sysId + ":scaling", 0);
        menu.effect = prefs.getInt(sysId + ":effect", 0);
        menu.fast = prefs.getInt(sysId + ":fast", 0);
        controls = new Controls(prefs, sysId, Library.baseName(romFile));
        cheatFile = Library.cheatFile(sysId, romFile);
        menu.cheats = Cheats.read(cheatFile);
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitPicture());
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // The game fills the whole screen; only the menu keeps clear of system bars (and of a
            // navigation bar that stays visible), so its hints are never covered.
            getWindow().setDecorFitsSystemWindows(false);
            HomeActivity.fullScreenWindow(this);
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                navReserve = 0; // full screen, same as the home screen
                menu.setInsets(0, 0, 0, 0);
                fitPicture();
                return ins;
            });
        }
        root.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.setDefaultFocusHighlightEnabled(false);
        setContentView(root);
        surface.getHolder().addCallback(this);
        applyOptions();
    }

    // ---- picture ----

    /** Sizes the picture by the chosen scaling: Aspect fits with bars, Integer uses whole multiples, Fill stretches. */
    private void fitPicture() {
        // The navigation bar is drawn over the bottom of the screen; a picture running under it loses its
        // last rows (seen on the Pocket 6), so the picture is fitted and centred in the space above it.
        int w = root.getWidth(), h = root.getHeight() - navReserve;
        if (w <= 0 || h <= 0) return;
        float aspect = Player.nativeAspect();
        int size = Player.nativeFrameSize(), bw = size >>> 16, bh = size & 0xFFFF;
        int pw, ph;
        if (menu.scaling == 2) {
            pw = w;
            ph = h;
        } else {
            pw = Math.min(w, Math.round(h * aspect));
            ph = Math.round(pw / aspect);
            if (menu.scaling == 1 && bw > 0 && bh > 0) {
                int n = Math.min(w / bw, h / bh);
                if (n >= 1) {
                    int iw = bw * n, ih = Math.round(iw / aspect);
                    if (ih <= h) { pw = iw; ph = ih; }
                }
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) picture.getLayoutParams();
        if (lp.width != pw || lp.height != ph || lp.bottomMargin != navReserve) {
            lp.width = pw;
            lp.height = ph;
            lp.setMargins(0, 0, 0, navReserve); // centred in what is left above the bar
            picture.setLayoutParams(lp);
        }
    }

    private void applyOptions() {
        scan.setVisibility(menu.effect == 1 ? View.VISIBLE : View.GONE);
        fitPicture();
    }

    /** Dark lines between the game's pixel rows, one per source row, so the look holds at any size. */
    private static final class ScanlineView extends View {
        private final Paint p = new Paint();

        ScanlineView(android.content.Context c) {
            super(c);
            p.setColor(0x55000000);
        }

        @Override
        protected void onDraw(Canvas c) {
            int rows = Player.nativeFrameSize() & 0xFFFF;
            if (rows <= 0) return;
            float rowH = getHeight() / (float) rows;
            for (int i = 0; i < rows; i++) {
                float y = i * rowH;
                c.drawRect(0, y + rowH * 0.62f, getWidth(), y + rowH, p);
            }
        }
    }

    // ---- game lifetime ----

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Player.nativeCheats(Cheats.codesOn(menu.cheats));
        running = Player.nativeStart(
                getIntent().getStringExtra(EXTRA_CORE), getIntent().getStringExtra(EXTRA_ROM),
                getIntent().getStringExtra(EXTRA_SYS), getIntent().getStringExtra(EXTRA_SAVE), holder.getSurface(),
                asleep ? 0 : getIntent().getIntExtra(EXTRA_START, -1));
        if (asleep && menuWasOpen && running) handler.postDelayed(() -> Player.nativePause(true), 300);
        asleep = false;
        handler.post(poll);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopGame();
    }

    private void stopGame() {
        handler.removeCallbacks(poll);
        if (running) {
            Player.nativeStop();
            running = false;
        }
    }

    /** Saves the resume point (a state plus a screenshot) once, before the game stops. */
    private void saveResume() {
        if (!running || resumeSaved) return;
        resumeSaved = true;
        Bitmap frame = grabFrame();
        if (Player.nativeSaveState(0) > 0 && frame != null) writePng(Library.stateFile(sysId, romFile, ".auto"), frame);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sessionStart > 0) {
            Library.addPlayTime(this, "rom:" + sysId + "/" + romFile.getName(), android.os.SystemClock.elapsedRealtime() - sessionStart);
            sessionStart = 0;
        }
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if ((screenOff || !pm.isInteractive()) && running && !failed && !isFinishing()) {
            // Sleep: keep the place and put the game away, but stay here so waking carries on playing.
            menuWasOpen = menu.isOpen();
            saveResume();
            stopGame();
            asleep = true;
            return;
        }
        // Home, a call, or anything else that takes the screen: keep the place, then end the game.
        saveResume();
        stopGame();
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sessionStart = android.os.SystemClock.elapsedRealtime();
        screenOff = false;
        resumeSaved = failed; // a new run, so it can be saved again
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(screenReceiver);
        super.onDestroy();
    }

    // ---- menu host ----

    @Override
    public String gameTitle() {
        return title != null ? title : romFile.getName();
    }

    @Override
    public Bitmap currentFrame() {
        return grabFrame();
    }

    @Override
    public Bitmap slotPicture(int slot) {
        Bitmap b = slotCache.get(slot);
        if (b == null) {
            File png = new File(Library.stateFile(sysId, romFile, String.valueOf(slot)).getPath() + ".png");
            if (png.exists()) {
                b = BitmapFactory.decodeFile(png.getPath());
                if (b != null) slotCache.put(slot, b);
            }
        }
        return b;
    }

    @Override
    public long slotTime(int slot) {
        File f = Library.stateFile(sysId, romFile, String.valueOf(slot));
        return f.exists() ? f.lastModified() : 0;
    }

    @Override
    public boolean saveSlot(int slot) {
        Bitmap frame = grabFrame();
        if (Player.nativeSaveState(slot) <= 0) return false;
        if (frame != null) writePng(Library.stateFile(sysId, romFile, String.valueOf(slot)), frame);
        slotCache.remove(slot);
        return true;
    }

    @Override
    public boolean loadSlot(int slot) {
        return Player.nativeLoadState(slot);
    }

    @Override
    public void continueGame() {
        menu.close();
        Player.nativePause(false);
    }

    @Override
    public void quit() {
        saveResume();
        stopGame();
        finish();
    }

    @Override
    public void optionsChanged() {
        prefs.edit().putInt(sysId + ":scaling", menu.scaling).putInt(sysId + ":effect", menu.effect)
                .putInt(sysId + ":fast", menu.fast).apply();
        applyOptions();
    }

    @Override
    public String consoleName() {
        return Library.systemName(sysId);
    }

    @Override
    public boolean controlsPerGame() {
        return controls.perGame();
    }

    @Override
    public void setControlsPerGame(boolean game) {
        controls.setPerGame(game);
    }

    @Override
    public int controlKey(int id) {
        return controls.table(nintendoPad)[id];
    }

    @Override
    public void bindControl(int id, int keyCode) {
        controls.bind(id, keyCode, nintendoPad);
    }

    @Override
    public void resetControls() {
        controls.reset();
    }

    @Override
    public void cheatsChanged() {
        Player.nativeCheats(Cheats.codesOn(menu.cheats));
        Cheats.write(cheatFile, menu.cheats);
    }

    private void openMenu() {
        Player.nativePause(true);
        selectDown = startDown = false;
        menu.open();
    }

    /** The latest frame as a bitmap, or null before the first one. */
    private Bitmap grabFrame() {
        int size = Player.nativeFrameSize(), w = size >>> 16, h = size & 0xFFFF;
        if (w <= 0 || h <= 0) return null;
        int[] px = new int[w * h];
        if (!Player.nativeSnapshot(px)) return null;
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Writes "<state file>.png" next to a state, through a temp file. */
    private void writePng(File state, Bitmap b) {
        File out = new File(state.getPath() + ".png"), tmp = new File(out.getPath() + ".tmp");
        out.getParentFile().mkdirs();
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        } catch (java.io.IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(out)) tmp.delete();
    }

    // ---- keys ----

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (Controls.defaultId(code, true) >= 0 && e.getDevice() != null) nintendoPad = nintendoLabelled(e);

        if (failed) { // the game never started: any "back" key leaves, with no menu and no save
            if (down && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B
                    || code == KeyEvent.KEYCODE_BUTTON_MODE)) {
                stopGame();
                finish();
            }
            return true;
        }

        if (menu.isOpen()) {
            // Everything goes to the menu. Held direction keys repeat; other keys act once.
            boolean direction = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (down && (e.getRepeatCount() == 0 || direction)) menu.key(code);
            return true;
        }

        if (code == KeyEvent.KEYCODE_BUTTON_MODE || code == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) openMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) selectDown = down;
        if (code == KeyEvent.KEYCODE_BUTTON_START) startDown = down;
        if (down && selectDown && startDown) { // Select and Start together
            openMenu();
            return true;
        }
        int id = controls.idFor(code, nintendoPad);
        if (id < 0 && code == KeyEvent.KEYCODE_BUTTON_R2) { // hold to fast-forward, unless R2 was given to a game button
            Player.nativeSpeed(down ? fastSpeed() : 1);
            return true;
        }
        if (down && e.getRepeatCount() == 0 && code == KeyEvent.KEYCODE_F3) {
            hud.setVisibility(hud.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        }
        if (id < 0) {
            // a pad key with no job in this game is still not for the system
            return Controls.defaultId(code, true) >= 0 || code == KeyEvent.KEYCODE_BUTTON_R2 || super.dispatchKeyEvent(e);
        }
        if (e.getRepeatCount() == 0) Player.nativeButton(id, down);
        return true;
    }

    /** The sticks go to the game as they are; the core applies its own dead zone. Nothing moves while the menu is open. */
    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent e) {
        if ((e.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) != 0 && e.getAction() == android.view.MotionEvent.ACTION_MOVE) {
            boolean live = !failed && !menu.isOpen();
            Player.nativeAxis(0, live ? stick(e.getAxisValue(android.view.MotionEvent.AXIS_X)) : 0);
            Player.nativeAxis(1, live ? stick(e.getAxisValue(android.view.MotionEvent.AXIS_Y)) : 0);
            Player.nativeAxis(2, live ? stick(e.getAxisValue(android.view.MotionEvent.AXIS_Z)) : 0);
            Player.nativeAxis(3, live ? stick(e.getAxisValue(android.view.MotionEvent.AXIS_RZ)) : 0);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    private static int stick(float v) {
        return Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
    }

    private int fastSpeed() {
        switch (menu.fast) {
            case 0: return 2;
            case 1: return 3;
            case 2: return 4;
            default: return 0; // as fast as it will go
        }
    }

    /**
     * True for the Retroid Pocket's own controller. It reports Nintendo-style labels (A is the right
     * button, B the bottom, X the top, Y the left), read from its key presses on a real Pocket 6. Xbox-style
     * pads label them the other way round (A bottom, B right, X left, Y top).
     */
    private static boolean nintendoLabelled(KeyEvent e) {
        android.view.InputDevice d = e.getDevice();
        return d != null && d.getName() != null && d.getName().toLowerCase(java.util.Locale.ROOT).contains("retroid");
    }
}
