package app.lantern;

import android.app.Activity;
import android.graphics.Color;
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

/**
 * Runs one game in a libretro core. Lives in its own process (see the manifest), so a core that
 * crashes takes down this screen and not the launcher.
 *
 * Spike: Back leaves, Home leaves, there is no in-game menu yet. F5 and F7 save and load one state.
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_CORE = "core", EXTRA_ROM = "rom", EXTRA_SYS = "sys", EXTRA_SAVE = "save";

    private FrameLayout root;
    private SurfaceView surface;
    private TextView hud;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            hud.setText(Player.nativeStatus());
            fitPicture();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setShadowLayer(4, 0, 1, Color.BLACK);
        hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hp.setMargins(24, 16, 24, 0);
        root.addView(hud, hp);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitPicture());
        setContentView(root);
        surface.getHolder().addCallback(this);
    }

    /** Shows the picture at the core's aspect ratio, as large as fits, with black bars around it. */
    private void fitPicture() {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        float aspect = Player.nativeAspect();
        int pw = Math.min(w, Math.round(h * aspect)), ph = Math.round(pw / aspect);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != pw || lp.height != ph) {
            lp.width = pw;
            lp.height = ph;
            surface.setLayoutParams(lp);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        running = Player.nativeStart(
                getIntent().getStringExtra(EXTRA_CORE), getIntent().getStringExtra(EXTRA_ROM),
                getIntent().getStringExtra(EXTRA_SYS), getIntent().getStringExtra(EXTRA_SAVE), holder.getSurface());
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

    @Override
    protected void onPause() {
        super.onPause();
        // Spike: leaving the screen ends the game. Saving a resume point comes with the menu.
        stopGame();
        finish();
    }

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
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) finish();
            return true;
        }
        if (down && e.getRepeatCount() == 0) {
            if (code == KeyEvent.KEYCODE_F5) { Player.nativeStateRequest(1); return true; }
            if (code == KeyEvent.KEYCODE_F7) { Player.nativeStateRequest(2); return true; }
        }
        int id = retroButton(code);
        if (id < 0) return super.dispatchKeyEvent(e);
        if (e.getRepeatCount() == 0) Player.nativeButton(id, down);
        return true;
    }

    /**
     * Android gamepad keys to RetroPad buttons, by position: libretro's A is the right face button
     * and B the bottom one, so Android's A (bottom) becomes RetroPad B. The Retroid's own labels
     * may differ; this table is the one place to change.
     */
    private static int retroButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return 0;   // B
            case KeyEvent.KEYCODE_BUTTON_X: return 1;   // Y
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 2;
            case KeyEvent.KEYCODE_BUTTON_START: return 3;
            case KeyEvent.KEYCODE_DPAD_UP: return 4;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 5;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 6;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 7;
            case KeyEvent.KEYCODE_BUTTON_B: return 8;   // A
            case KeyEvent.KEYCODE_BUTTON_Y: return 9;   // X
            case KeyEvent.KEYCODE_BUTTON_L1: return 10;
            case KeyEvent.KEYCODE_BUTTON_R1: return 11;
            case KeyEvent.KEYCODE_BUTTON_L2: return 12;
            case KeyEvent.KEYCODE_BUTTON_R2: return 13;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 14;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 15;
            default: return -1;
        }
    }
}
