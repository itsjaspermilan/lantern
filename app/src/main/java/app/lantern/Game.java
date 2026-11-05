package app.lantern;

import android.content.Intent;
import android.graphics.drawable.Drawable;

import java.io.File;

/** One tile on the shelf: an emulated ROM or an installed Android app. */
final class Game {
    /** Stable key: "rom:gba/Name.gba" or "app:com.example.pkg". Used for favourites and play history. */
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
    /** Cover picture: the person's own, a fetched one, or null. */
    File art;
    /** Colour for the tile and the glow; taken from the cover once it is known. */
    int hue;
    long lastPlayed;
    boolean fav;

    boolean isRom() {
        return file != null;
    }
}
