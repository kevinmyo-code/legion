package com.kevin.legion.games

/**
 * The three emulators LEGION can hand a game to (ADR 0058). [label] is the name said aloud in the
 * sheet and the launching toast; [packageName] is what package visibility is queried for
 * (`AndroidManifest.xml` `<queries>`) and what the launch intent targets.
 */
enum class Emulator(val label: String, val packageName: String) {
    RETROARCH("RetroArch", "com.retroarch.aarch64"),
    MELONDS("melonDS", "me.magnum.melonds"),
    PPSSPP("PPSSPP", "org.ppsspp.ppsspp"),
}

/**
 * Every system LEGION lists, in shelf order, with everything that differs per system kept in this
 * one table: the subfolder name the user's ROM folder uses, the emulator and (for RetroArch) the
 * libretro core, the extensions that count as "a game", and the libretro-thumbnails repository the
 * box art comes from.
 *
 * [core] is the RetroArch `LIBRETRO` short name; null for the two systems RetroArch does not play.
 * Ticket 02 launched `pcsx_rearmed` on the A25 and confirmed the others are installed, but did not
 * launch them (no ROMs were on the phone): the n64 stem `mupen64plus_next_gles3` is reasoned from
 * the core's label, and which genesis_plus_gx variant is installed is unconfirmed.
 *
 * [extensions] are lowercase, without the dot. For psx, `cue`/`chd`/`pbp`/`iso` are games and
 * `bin` is a track file, kept in the list so a lone `.bin` is still found (see [RomScan]).
 */
enum class GameSystem(
    val folder: String,
    val displayName: String,
    val badge: String,
    val emulator: Emulator,
    val core: String?,
    val extensions: Set<String>,
    val thumbnailRepo: String,
) {
    PSX(
        "psx", "PlayStation", "PS1", Emulator.RETROARCH, "pcsx_rearmed",
        setOf("cue", "chd", "pbp", "iso", "bin"), "Sony_-_PlayStation",
    ),
    GBA(
        "gba", "Game Boy Advance", "GBA", Emulator.RETROARCH, "mgba",
        setOf("gba"), "Nintendo_-_Game_Boy_Advance",
    ),
    SNES(
        "snes", "Super Nintendo", "SNES", Emulator.RETROARCH, "snes9x",
        setOf("sfc", "smc"), "Nintendo_-_Super_Nintendo_Entertainment_System",
    ),
    GBC(
        "gbc", "Game Boy Color", "GBC", Emulator.RETROARCH, "gambatte",
        setOf("gbc"), "Nintendo_-_Game_Boy_Color",
    ),
    GB(
        "gb", "Game Boy", "GB", Emulator.RETROARCH, "gambatte",
        setOf("gb"), "Nintendo_-_Game_Boy",
    ),
    NES(
        "nes", "Nintendo (NES)", "NES", Emulator.RETROARCH, "fceumm",
        setOf("nes"), "Nintendo_-_Nintendo_Entertainment_System",
    ),
    GENESIS(
        "genesis", "Genesis", "Genesis", Emulator.RETROARCH, "genesis_plus_gx",
        setOf("md", "gen", "smd", "bin"), "Sega_-_Mega_Drive_-_Genesis",
    ),
    N64(
        "n64", "Nintendo 64", "N64", Emulator.RETROARCH, "mupen64plus_next_gles3",
        setOf("z64", "n64", "v64"), "Nintendo_-_Nintendo_64",
    ),
    NDS(
        "nds", "Nintendo DS", "DS", Emulator.MELONDS, null,
        setOf("nds"), "Nintendo_-_Nintendo_DS",
    ),
    PSP(
        "psp", "PSP", "PSP", Emulator.PPSSPP, null,
        setOf("iso", "cso", "chd", "pbp"), "Sony_-_PlayStation_Portable",
    ),
    ;

    companion object {
        /** The system a ROM-root subfolder name means, case-insensitively; null for any other folder. */
        fun fromFolder(name: String): GameSystem? =
            entries.firstOrNull { it.folder.equals(name.trim(), ignoreCase = true) }
    }
}
