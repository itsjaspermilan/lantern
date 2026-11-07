package app.lantern;

import android.content.Intent;
import android.graphics.drawable.Drawable;

import java.io.File;

/** One tile on the shelf: an emulated ROM or an installed Android app. */
final class Game {
    /** Stable key: "rom:gba/Name.gba" or "app:com.example.pkg". Used for play history. */
    String id;
    String title;
    /** Short system name shown on the tile, for example "GBA" or "Android". */
    String system;
    /** Folder name of the system under Lantern/roms, such as "gba". Null for apps. */
    String sysId;
    /** Present for ROMs. Null for apps. */
    File file;
    /** Present for apps. Null for ROMs, which will open in the built-in player. */
    Intent launch;
    /** Package name for apps. Null for ROMs. */
    String pkg;
    /** The app's own icon. Null for ROMs, which get art later. */
    Drawable icon;
    /** Cover picture: the person's own, a fetched one, or null. Volatile: the art fetcher sets it from its own thread. */
    volatile File art;
    /** Cache key for the cover (path and modified time), worked out once per file: see ArtCache.key. */
    File artKeyFile;
    String artKey;
    /** Colour for the tile and the glow; taken from the cover once it is known. */
    int hue;
    long lastPlayed;
    /** A resume point is saved for this ROM (shown as a small dot on its tile). */
    boolean resume;

    /** True for a tile that stands for a whole system on the Systems shelf, not for a game. */
    boolean systemTile;
    /** How many games a system tile holds. */
    int count;

    boolean isRom() {
        return file != null;
    }
}
