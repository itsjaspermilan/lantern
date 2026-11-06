package app.lantern;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A game's cheats, kept in RetroArch's .cht format so files move between emulators unchanged:
 * cheat0_desc, cheat0_code and cheat0_enable lines. Saving rewrites only the enable lines.
 */
final class Cheats {
    private Cheats() {}

    static final class Cheat {
        final int index;
        final String name, code;
        boolean on;

        Cheat(int index, String name, String code, boolean on) {
            this.index = index;
            this.name = name;
            this.code = code;
            this.on = on;
        }
    }

    private static final Pattern LINE = Pattern.compile("^\\s*cheat(\\d+)_(desc|code|enable)\\s*=\\s*\"?(.*?)\"?\\s*$");

    /** The file's cheats that have a code, in file order. Empty if the file is missing or unreadable. */
    static List<Cheat> read(File f) {
        if (!f.isFile()) return Collections.emptyList();
        Map<Integer, String[]> parts = new java.util.TreeMap<>();
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                Matcher m = LINE.matcher(line);
                if (!m.matches()) continue;
                String[] p = parts.computeIfAbsent(Integer.valueOf(m.group(1)), k -> new String[3]);
                p["desc".equals(m.group(2)) ? 0 : "code".equals(m.group(2)) ? 1 : 2] = m.group(3);
            }
        } catch (IOException | NumberFormatException e) {
            return Collections.emptyList();
        }
        List<Cheat> out = new ArrayList<>();
        for (Map.Entry<Integer, String[]> e : parts.entrySet()) {
            String[] p = e.getValue();
            if (p[1] == null || p[1].isEmpty()) continue;
            String name = p[0] == null || p[0].isEmpty() ? "Cheat " + (e.getKey() + 1) : p[0];
            out.add(new Cheat(e.getKey(), name, p[1], "true".equalsIgnoreCase(p[2])));
        }
        return out;
    }

    static String[] codesOn(List<Cheat> cheats) {
        List<String> on = new ArrayList<>();
        for (Cheat c : cheats) if (c.on) on.add(c.code);
        return on.toArray(new String[0]);
    }

    /** Stores which cheats are on, through a temp file so a failure keeps the old file. */
    static void write(File f, List<Cheat> cheats) {
        Map<Integer, Cheat> byIndex = new HashMap<>();
        for (Cheat c : cheats) byIndex.put(c.index, c);
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                Matcher m = LINE.matcher(line);
                if (m.matches() && "enable".equals(m.group(2)) && byIndex.containsKey(Integer.valueOf(m.group(1)))) {
                    int i = Integer.parseInt(m.group(1));
                    lines.add("cheat" + i + "_enable = " + byIndex.remove(i).on);
                } else {
                    lines.add(line);
                }
            }
            for (Cheat c : byIndex.values()) lines.add("cheat" + c.index + "_enable = " + c.on);
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (IOException e) {
            android.util.Log.e("Lantern", "cannot save " + f, e);
        }
    }
}
