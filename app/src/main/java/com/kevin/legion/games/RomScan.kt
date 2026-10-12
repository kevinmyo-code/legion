package com.kevin.legion.games

/** One file found under a system folder, before it is decided whether it is a game. */
data class RomEntry(val documentId: String, val fileName: String)

/**
 * A game LEGION can launch. [documentId] is the SAF tree document id of the ROM file itself
 * (`primary:ROMs/psx/x.cue`), the only thing every launch path is derived from: RetroArch's
 * filesystem path ([RomPaths]) and melonDS/PPSSPP's content URI.
 */
data class Game(
    val system: GameSystem,
    val title: String,
    val fileName: String,
    val documentId: String,
)

/** The user-facing title of a ROM file: its name without the last extension. Also the box-art key. */
fun titleOf(fileName: String): String = fileName.substringBeforeLast('.', fileName)

/** The lowercase extension of [fileName], "" if it has none. */
fun extensionOf(fileName: String): String =
    if ('.' in fileName) fileName.substringAfterLast('.').lowercase() else ""

/**
 * Pure: turns the files found under one system folder into the games to list.
 *
 * Only files whose extension the system accepts are games. PlayStation is the one place a game is
 * several files: a `.cue` sheet is the game and its `.bin` track files are not listed on their own.
 * A `.bin` is hidden when a `.cue` in the same folder set has the same name, or the same name plus a
 * ` (Track N)` suffix (the usual multi-track dump). A `.bin` with no cue is a game in its own right
 * (RetroArch's pcsx_rearmed opens it), so it stays listed; this is also why a Genesis `.bin` is a
 * game, not a track, and the collapse applies to PSX only. Sorted by title, case-insensitively.
 */
object RomScan {
    private val trackSuffix = Regex("""\s*\(track\s*\d+\)$""", RegexOption.IGNORE_CASE)

    fun games(system: GameSystem, entries: List<RomEntry>): List<Game> {
        val accepted = entries.filter { extensionOf(it.fileName) in system.extensions }
        val cueTitles = if (system == GameSystem.PSX) {
            accepted.filter { extensionOf(it.fileName) == "cue" }.map { titleOf(it.fileName).lowercase() }.toSet()
        } else {
            emptySet()
        }
        return accepted
            .filterNot { system == GameSystem.PSX && extensionOf(it.fileName) == "bin" && coveredByCue(it, cueTitles) }
            .map { Game(system, titleOf(it.fileName), it.fileName, it.documentId) }
            .sortedBy { it.title.lowercase() }
    }

    private fun coveredByCue(bin: RomEntry, cueTitles: Set<String>): Boolean {
        val name = titleOf(bin.fileName).lowercase()
        return name in cueTitles || trackSuffix.replace(name, "") in cueTitles
    }
}
