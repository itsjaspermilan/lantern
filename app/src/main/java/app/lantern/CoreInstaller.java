package app.lantern;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a libretro core, when the person asks for one, from libretro's nightly build server into the app's own
 * storage, where the player loads it. Lantern never ships or passes on a core: each person downloads their own.
 *
 * <p>The server's files are not signed, so what is checked is what can be: the download is a zip holding one core, it is
 * not absurdly large, and the core is a 64-bit ARM shared library. It is written to a temporary file and only renamed
 * into place when it is complete.
 */
final class CoreInstaller {
    private CoreInstaller() {}

    interface Listener {
        /** Called on the main thread while downloading (0 to 100, or -1 when the size is not known). */
        void progress(String lib, int percent);

        /** Called on the main thread when done: error is null on success, else a short reason to show. */
        void done(String lib, String error);
    }

    static final String BASE = "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService ONE = Executors.newSingleThreadExecutor();
    private static final Set<String> BUSY = ConcurrentHashMap.newKeySet();

    static boolean busy(String lib) {
        return BUSY.contains(lib);
    }

    static void install(final Context context, final String lib, final Listener l) {
        if (!BUSY.add(lib)) return;
        final Context app = context.getApplicationContext();
        ONE.execute(() -> {
            String error = null;
            try {
                download(app, lib, l);
            } catch (IOException e) {
                error = e.getMessage() == null ? "download failed" : e.getMessage();
            } finally {
                BUSY.remove(lib);
                Cores.forget();
            }
            final String err = error;
            MAIN.post(() -> l.done(lib, err));
        });
    }

    /** Removes a downloaded core (a core packaged in the APK cannot be removed). */
    static boolean remove(Context c, String lib) {
        boolean ok = Cores.downloadedFile(c, lib).delete();
        Cores.forget();
        return ok;
    }

    private static void download(Context c, String lib, Listener l) throws IOException {
        File dir = Cores.downloadedFile(c, lib).getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no room to save it");
        File tmp = new File(dir, "lib" + lib + ".so.part");
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + lib + "_libretro_android.so.zip").openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Lantern (open-source launcher)");
        try {
            int code = conn.getResponseCode();
            if (code == 404) throw new IOException("not on the server");
            if (code != 200) throw new IOException("server said " + code);
            long total = conn.getContentLengthLong();
            if (total > MAX_BYTES) throw new IOException("too large");
            boolean found = false;
            try (InputStream raw = conn.getInputStream();
                 CountingStream counted = new CountingStream(raw, total, lib, l);
                 ZipInputStream zip = new ZipInputStream(counted)) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().endsWith("_libretro_android.so")) continue;
                    long written = 0;
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = zip.read(buf)) > 0) {
                            written += n;
                            if (written > MAX_BYTES) throw new IOException("too large");
                            out.write(buf, 0, n);
                        }
                    }
                    found = true;
                    break;
                }
            }
            if (!found) throw new IOException("no core in the download");
            if (!isArm64Library(tmp)) throw new IOException("not a core for this device");
            tmp.setReadOnly(); // code that is loaded should not be writable
            File dest = Cores.downloadedFile(c, lib);
            if (!tmp.renameTo(dest)) throw new IOException("could not save it");
        } finally {
            conn.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    /** A 64-bit little-endian ELF shared library for AArch64 (machine 183). */
    private static boolean isArm64Library(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            if (r.length() < 64) return false;
            byte[] h = new byte[20];
            r.readFully(h);
            boolean elf = h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
            int type = (h[16] & 0xFF) | (h[17] & 0xFF) << 8, machine = (h[18] & 0xFF) | (h[19] & 0xFF) << 8;
            return elf && h[4] == 2 && h[5] == 1 && type == 3 && machine == 183;
        }
    }

    /** Reports how much of the download has arrived. */
    private static final class CountingStream extends java.io.FilterInputStream {
        private final long total;
        private final String lib;
        private final Listener l;
        private long seen;
        private int lastPercent = -2;

        CountingStream(InputStream in, long total, String lib, Listener l) {
            super(in);
            this.total = total;
            this.lib = lib;
            this.l = l;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                seen += n;
                if (seen > MAX_BYTES) throw new IOException("too large");
                final int p = total > 0 ? (int) (seen * 100 / total) : -1;
                if (p != lastPercent) {
                    lastPercent = p;
                    MAIN.post(() -> l.progress(lib, p));
                }
            }
            return n;
        }
    }
}
