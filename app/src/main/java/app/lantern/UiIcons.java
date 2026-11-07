package app.lantern;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Kenney's button prompts and icons (CC0), read once from assets/ui and kept. See licenses/kenney-ui.md. */
final class UiIcons {
    private UiIcons() {}

    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    /** The picture for a name like "btn_a" or "arrow_left", or null if there is none. */
    static synchronized Bitmap get(Context c, String name) {
        if (CACHE.containsKey(name)) return CACHE.get(name);
        Bitmap b = null;
        try (InputStream in = c.getAssets().open("ui/" + name + ".png")) {
            b = BitmapFactory.decodeStream(in);
        } catch (IOException ignored) {
        }
        CACHE.put(name, b);
        return b;
    }

    /** The prompt picture for a hint's label ("A", "L1", "Select", "▲ ▼"...), or null if it has none. */
    static String promptFor(String text) {
        switch (text) {
            case "A": return "btn_a";
            case "B": return "btn_b";
            case "X": return "btn_x";
            case "Y": return "btn_y";
            case "L1": return "btn_l";
            case "R1": return "btn_r";
            case "Select": return "btn_minus";
            case "Start": return "btn_plus";
            case "▲ ▼": return "dpad_vertical";
            case "◀ ▶": return "dpad_horizontal";
            case "◀ ▶ ▲ ▼": return "dpad_all";
            case "▲": return "dpad_up";
            default: return null;
        }
    }
}
