package app.lantern;

import java.io.File;

/**
 * Checks the launcher's plain logic (no Android needed): file and title cleaning, system names and colours.
 * Run with ./test.sh. A failed check prints what was expected and exits with an error.
 */
public final class LogicTest {
    private static int checks, failures;

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.err.println("FAIL " + what + ": expected <" + want + "> but got <" + got + ">");
        }
    }

    public static void main(String[] args) {
        // Titles are cleaned of region tags, brackets and underscores.
        eq("Moonlit Courier", Library.cleanTitle("Moonlit Courier (USA) [!].gba"), "region and dump tags");
        eq("Pokemon - Emerald Version", Library.cleanTitle("Pokemon - Emerald Version (USA, Europe).gba"), "two-part tag");
        eq("Super Mario Bros", Library.cleanTitle("Super_Mario_Bros.nes"), "underscores");
        eq("(USA).gba", Library.cleanTitle("(USA).gba"), "a name that is only a tag is kept");
        eq("noextension", Library.cleanTitle("noextension"), "no extension");

        // The base name is what the save files are named after.
        eq("Advance Wars (USA)", Library.baseName(new File("/roms/gba/Advance Wars (USA).gba")), "base name");
        eq("archive.tar", Library.baseName(new File("/x/archive.tar.gz")), "only the last extension goes");
        eq(".hidden", Library.baseName(new File("/x/.hidden")), "a leading dot is not an extension");

        // Names for the shelves.
        eq("Game Boy Advance", Library.systemTitle("gba"), "system title");
        eq("Android Games", Library.systemTitle(Library.GAMES), "android games title");
        eq("GBA", Library.systemName("gba"), "short system name");
        eq("game", Library.unit("gba", 1), "one game");
        eq("games", Library.unit("gba", 2), "two games");
        eq("app", Library.unit(Library.APPS, 1), "one app");

        // Every system has its own colour; unknown ones have none.
        eq(true, Library.baseHue("gba") >= 0, "gba has a colour");
        eq(-1, Library.baseHue("android"), "apps have no system colour");
        eq(-1, Library.baseHue("nonsense"), "unknown system");

        // Cores: only some systems have one bundled.
        eq("mgba", Cores.libFor("gba"), "gba core");
        eq("gambatte", Cores.libFor("gbc"), "gbc core");
        eq("pcsx2", Cores.libFor("ps2"), "ps2 plays on PCSX2");
        eq(null, Cores.libFor("xbox"), "no xbox core");

        // Cover art names for the fetcher.
        eq("Super Mario_ Bros_", ArtFetcher.thumbName("Super Mario: Bros?"), "characters the thumbnail server replaces");

        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }
}
