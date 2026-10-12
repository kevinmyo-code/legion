package com.kevin.legion.ui.home

import androidx.compose.ui.graphics.ImageBitmap
import com.kevin.legion.games.AndroidGame
import com.kevin.legion.games.Emulator
import com.kevin.legion.games.Game
import com.kevin.legion.games.GameSystem

/** Where the ROMs folder grant stands. [label] is the folder as the user would say it ("/sdcard/ROMs"). */
sealed interface GamesFolderState {
    data object None : GamesFolderState

    /** A folder was picked once, but the system no longer holds the grant. */
    data class Lost(val label: String) : GamesFolderState

    data class Granted(val label: String) : GamesFolderState
}

/** A line the launcher says in words. [failed] puts it in the sheet; a success is the toast on HOME. */
data class GameNotice(val text: String, val failed: Boolean)

/**
 * The Continue card: the last game launched from LEGION, only while it is still there.
 * Exactly one of [rom] and [app] is set.
 */
data class ResumeUi(val rom: Game?, val app: AndroidGame?, val ago: String) {
    val title: String get() = rom?.title ?: app?.label.orEmpty()
}

data class Shelf(val system: GameSystem, val games: List<Game>)

data class GamesUiState(
    val folder: GamesFolderState = GamesFolderState.None,
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    /** The root could not be listed at all, which is not the same as holding no games. */
    val unreadable: Boolean = false,
    val systemFoldersSeen: Int = 0,
    val games: List<Game> = emptyList(),
    /** Installed apps filed under games; empty hides the shelf. */
    val androidGames: List<AndroidGame> = emptyList(),
    /** The last scan's count, shown on the chip until this scan finishes. */
    val cachedCount: Int? = null,
    val resume: ResumeUi? = null,
    /** Emulators not installed, so a shelf can say so before a tap fails. */
    val missingEmulators: Set<Emulator> = emptySet(),
    val notice: GameNotice? = null,
) {
    /** One shelf per system that has games, in [GameSystem] order. */
    val shelves: List<Shelf>
        get() = GameSystem.entries.mapNotNull { system ->
            games.filter { it.system == system }.takeIf { it.isNotEmpty() }?.let { Shelf(system, it) }
        }

    /** The chip's second line, in words. */
    val chipSub: String
        get() = when {
            folder is GamesFolderState.Granted && scanned -> countLabel(games.size + androidGames.size)
            folder is GamesFolderState.Granted && cachedCount != null -> countLabel(cachedCount + androidGames.size)
            folder is GamesFolderState.Granted -> "looking"
            androidGames.isNotEmpty() -> countLabel(androidGames.size)
            else -> "pick folder"
        }
}

internal fun countLabel(n: Int): String = if (n == 1) "1 game" else "$n games"

/** What the Games chip and sheet ask their owner to do. */
data class GamesCallbacks(
    val onOpen: () -> Unit = {},
    val onPlay: (Game) -> Unit = {},
    val onPlayResume: () -> Unit = {},
    val onPlayApp: (AndroidGame) -> Unit = {},
    val onPickFolder: () -> Unit = {},
)

/** Everything HOME needs to draw Games: state, callbacks, and how a tile gets its cover (null = none). */
class GamesHome(
    val state: GamesUiState = GamesUiState(),
    val callbacks: GamesCallbacks = GamesCallbacks(),
    val cover: suspend (Game) -> ImageBitmap? = { null },
    val appIcon: suspend (AndroidGame) -> ImageBitmap? = { null },
)
