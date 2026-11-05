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
    /** Present for ROMs. Null for apps. */
    File file;
    /** Present for apps. Null for ROMs, which will open in the built-in player. */
    Intent launch;
    /** The app's own icon. Null for ROMs, which get art later. */
    Drawable icon;
    int hue;
    long lastPlayed;
    boolean fav;

    boolean isRom() {
        return file != null;
    }
}
