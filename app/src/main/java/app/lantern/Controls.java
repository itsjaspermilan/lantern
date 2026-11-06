package app.lantern;

import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Which physical button does what in a game. The defaults are by position (see {@link #defaultKey}); a
 * person's changes are kept as a small list of "RetroPad button : key" pairs, either for one game or for
 * every game on the console. Whichever the person chose is the one in use.
 */
final class Controls {
    /** RetroPad button ids, in the order the menu lists them. */
    static final int[] ORDER = {4, 5, 6, 7, 8, 0, 9, 1, 10, 11, 12, 13, 14, 15, 2, 3};
    private static final String[] NAMES = new String[16];
    static {
        String[] n = {"B (bottom)", "Y (left)", "Select", "Start", "Up", "Down", "Left", "Right",
                "A (right)", "X (top)", "L", "R", "L2", "R2", "L3", "R3"};
        System.arraycopy(n, 0, NAMES, 0, 16);
    }
    /** Keys that open the menu; never bound to a game button. */
    static boolean reserved(int code) {
        return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE;
    }

    /** Every key a game button can start as by default. */
    static final int[] KEYS = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR};

    static String buttonName(int id) {
        return NAMES[id];
    }

    /**
     * Android gamepad keys to RetroPad buttons, by position. libretro's A is the right face button, B
     * the bottom, X the top and Y the left. An Xbox-style pad's A is at the bottom, so it becomes RetroPad B.
     * The Retroid's A is already at the right, so it stays A. Returns -1 for a key with no default.
     */
    static int defaultId(int keyCode, boolean nintendo) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return nintendo ? 8 : 0;
            case KeyEvent.KEYCODE_BUTTON_X: return nintendo ? 9 : 1;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 2;
            case KeyEvent.KEYCODE_BUTTON_START: return 3;
            case KeyEvent.KEYCODE_DPAD_UP: return 4;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 5;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 6;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 7;
            case KeyEvent.KEYCODE_BUTTON_B: return nintendo ? 0 : 8;
            case KeyEvent.KEYCODE_BUTTON_Y: return nintendo ? 1 : 9;
            case KeyEvent.KEYCODE_BUTTON_L1: return 10;
            case KeyEvent.KEYCODE_BUTTON_R1: return 11;
            case KeyEvent.KEYCODE_BUTTON_L2: return 12;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 14;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 15;
            default: return -1;
        }
    }

    /** A readable name for a key, as printed on a pad. */
    static String keyName(int code) {
        if (code == 0) return "Not set";
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return "D-pad Up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D-pad Down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "D-pad Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "D-pad Right";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            default:
        }
        String n = KeyEvent.keyCodeToString(code);
        if (n.startsWith("KEYCODE_BUTTON_")) return n.substring(15);
        if (n.startsWith("KEYCODE_")) n = n.substring(8);
        return n.charAt(0) + n.substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private final SharedPreferences sp;
    private final String consoleKey, gameKey, useKey;
    /** RetroPad button id to key, only for the buttons the person changed. 0 means "no key". */
    private final Map<Integer, Integer> changes = new HashMap<>();
    private boolean perGame;
    private final Map<Boolean, int[]> tables = new HashMap<>();

    Controls(SharedPreferences sp, String sysId, String gameId) {
        this.sp = sp;
        consoleKey = "ctl:console:" + sysId;
        gameKey = "ctl:game:" + sysId + "/" + gameId;
        useKey = "ctl:use:" + sysId + "/" + gameId;
        perGame = sp.getBoolean(useKey, false);
        load();
    }

    boolean perGame() {
        return perGame;
    }

    /** Chooses where changes are kept. Choosing "this game" starts from the console's controls. */
    void setPerGame(boolean game) {
        if (game && !sp.contains(gameKey)) sp.edit().putString(gameKey, sp.getString(consoleKey, "")).apply();
        perGame = game;
        sp.edit().putBoolean(useKey, game).apply();
        load();
    }

    private String activeKey() {
        return perGame ? gameKey : consoleKey;
    }

    private void load() {
        changes.clear();
        tables.clear();
        String v = sp.getString(activeKey(), "");
        for (String pair : v.split(",")) {
            String[] p = pair.split(":");
            if (p.length != 2) continue;
            try {
                int id = Integer.parseInt(p[0]), code = Integer.parseInt(p[1]);
                if (id >= 0 && id < 16) changes.put(id, code);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void save() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : changes.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append(':').append(e.getValue());
        }
        sp.edit().putString(activeKey(), b.toString()).apply();
        tables.clear();
    }

    /** The key for each RetroPad button now (0 for none), for a pad labelled the Nintendo way or not. */
    int[] table(boolean nintendo) {
        int[] t = tables.get(nintendo);
        if (t != null) return t;
        t = new int[16];
        for (int k : KEYS) {
            int id = defaultId(k, nintendo);
            if (id >= 0) t[id] = k;
        }
        for (Map.Entry<Integer, Integer> e : changes.entrySet()) t[e.getKey()] = e.getValue();
        // a key given to one button is taken from any other button that still has it by default
        for (int id = 0; id < 16; id++) {
            if (changes.containsKey(id) || t[id] == 0) continue;
            for (Map.Entry<Integer, Integer> e : changes.entrySet()) if (e.getValue() == t[id]) t[id] = 0;
        }
        tables.put(nintendo, t);
        return t;
    }

    /** The RetroPad button a key presses, or -1. */
    int idFor(int keyCode, boolean nintendo) {
        int[] t = table(nintendo);
        for (int id = 0; id < 16; id++) if (t[id] == keyCode) return id;
        return -1;
    }

    /** Gives a button a key. If another button had that key, it gets this button's old key (a swap). */
    void bind(int id, int keyCode, boolean nintendo) {
        int[] t = table(nintendo).clone();
        int old = t[id];
        for (int other = 0; other < 16; other++) {
            if (other != id && t[other] == keyCode) changes.put(other, old);
        }
        changes.put(id, keyCode);
        save();
    }

    /** Back to the default buttons, for whichever of game or console is in use. */
    void reset() {
        changes.clear();
        save();
    }
}
