package app.lantern;

import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Gets a game's cheats from the libretro database (github.com/libretro/libretro-database, CC BY-SA 4.0), which keeps
 * one RetroArch .cht file per game, named like the No-Intro ROM. Only when the person asks for it in the player's
 * menu; the file is saved where the person's own cheat files live, so it can be edited or replaced.
 */
final class CheatFetcher {
    private CheatFetcher() {}

    static final int GOT = 0, NONE = 1, FAILED = 2;
    private static final String BASE = "https://raw.githubusercontent.com/libretro/libretro-database/master/cht/";
    private static final int MAX_BYTES = 512 * 1024;

    /** Whether this system has a cheat folder in the database (and a core here that takes cheats). */
    static boolean supported(String sysId, File rom) {
        switch (sysId == null ? "" : sysId) {
            case "nes": case "snes": case "gb": case "gbc": case "gba": case "mastersystem": case "gamegear": case "md": case "n64": case "nds": return true;
            default: return false;
        }
    }

    static String urlFor(String sysId, File rom) {
        return BASE + Uri.encode(ArtFetcher.systemName(sysId, rom)) + "/" + Uri.encode(ArtFetcher.thumbName(Library.baseName(rom))) + ".cht";
    }

    /** Downloads into dest (blocking: call off the main thread). GOT, NONE (the database has no file for this game) or FAILED. */
    static int fetch(String sysId, File rom, File dest) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlFor(sysId, rom)).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            int code = c.getResponseCode();
            if (code == 404) return NONE;
            if (code != 200) return FAILED;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) return FAILED;
                }
            }
            File dir = dest.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return FAILED;
            File tmp = new File(dest.getPath() + ".part");
            try (FileOutputStream f = new FileOutputStream(tmp)) {
                out.writeTo(f);
            }
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return GOT;
        } catch (IOException e) {
            return FAILED;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
