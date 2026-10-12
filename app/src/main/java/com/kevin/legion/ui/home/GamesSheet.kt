@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.games.AndroidGame
import com.kevin.legion.games.Game
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftColors

/** The Games tone: the calendar's blue pair, the closest existing tokens to the prototype's blue chip. */
internal val GamesTone = AreaAccent.CALENDAR

internal val TILE_WIDTH = 100.dp
internal val TILE_HEIGHT = 132.dp
internal val TILE_SHAPE = RoundedCornerShape(12.dp)
private const val SYSTEM_LIST = "gb, gbc, gba, nes, snes, n64, genesis, psx, nds, psp"

// The play triangle, as fractions of its 14dp box.
private const val PLAY_LEFT = 0.25f
private const val PLAY_RIGHT = 0.95f
private const val PLAY_TOP = 0.1f
private const val PLAY_MID = 0.5f
private const val PLAY_BOTTOM = 0.9f

/**
 * The Games sheet (retro-games ticket 03, prototype A): a bottom sheet over HOME. A window around
 * [GamesSheetContent], split so a screenshot can draw the content without a window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesSheet(games: GamesHome, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SoftColors.card,
    ) {
        GamesSheetContent(games, onDismiss)
    }
}

@Composable
fun GamesSheetContent(games: GamesHome, onClose: () -> Unit) {
    val state = games.state
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Games",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = SoftColors.text,
                )
                Text(subtitle(state), style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
            }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Done", color = SoftColors.text)
            }
        }
        state.notice?.takeIf { it.failed }?.let {
            Text(
                it.text,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.caution,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        // Fills the sheet's height and scrolls past it; HOME itself never scrolls.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            state.resume?.let { ContinueCard(it, state, games) }
            when (val folder = state.folder) {
                GamesFolderState.None -> FolderPrompt(
                    "Pick your ROMs folder",
                    "LEGION lists the games in the folder you choose and nothing else. Each system is a " +
                        "subfolder named $SYSTEM_LIST.",
                    "Pick your ROMs folder",
                    games.callbacks.onPickFolder,
                )
                is GamesFolderState.Lost -> FolderPrompt(
                    "LEGION can no longer read ${folder.label}",
                    "Android took the folder permission back. Pick the folder again to see your games.",
                    "Pick your ROMs folder",
                    games.callbacks.onPickFolder,
                )
                is GamesFolderState.Granted -> GrantedBody(games, folder.label)
            }
            AndroidShelf(games)
            if (state.folder is GamesFolderState.Granted && state.games.isNotEmpty()) {
                TextButton(
                    onClick = games.callbacks.onPickFolder,
                    modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 44.dp),
                ) { Text("Change folder", color = SoftColors.text2) }
            }
        }
    }
}

private fun subtitle(state: GamesUiState): String = when (val folder = state.folder) {
    is GamesFolderState.Granted -> "${state.chipSub} · ${folder.label}"
    is GamesFolderState.Lost -> "Folder access lost · ${folder.label}"
    GamesFolderState.None -> "No folder picked yet"
}

@Composable
private fun FolderPrompt(title: String, body: String, button: String, onPick: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
        Button(
            onClick = onPick,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GamesTone.onContainer,
                contentColor = GamesTone.container,
            ),
        ) { Text(button, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun GrantedBody(games: GamesHome, folderLabel: String) {
    val state = games.state
    when {
        state.unreadable -> FolderPrompt(
            "Couldn't read $folderLabel",
            "The folder is still granted but Android would not list it. Pick it again to refresh the permission.",
            "Pick your ROMs folder",
            games.callbacks.onPickFolder,
        )
        state.games.isEmpty() && !state.scanned -> Note("Looking for games in $folderLabel...")
        state.games.isEmpty() -> FolderPrompt(
            "No games found in $folderLabel",
            if (state.systemFoldersSeen == 0) {
                "None of its subfolders is named for a system. Name them $SYSTEM_LIST."
            } else {
                "The system folders are there but hold no game files LEGION recognises."
            },
            "Pick a different folder",
            games.callbacks.onPickFolder,
        )
        else -> {
            state.shelves.forEach { Shelf(it, state, games) }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = SoftColors.text2,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
private fun ContinueCard(resume: ResumeUi, state: GamesUiState, games: GamesHome) {
    val rom = resume.rom
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SoftColors.cardHigh)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val thumb = Modifier.width(64.dp).height(84.dp)
        if (rom != null) RomCover(rom, games, thumb, small = true) else resume.app?.let { AppCover(it, games, thumb) }
        Column(Modifier.weight(1f)) {
            Text(
                "CONTINUE",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.06.sp),
                color = GamesTone.onContainer,
            )
            Text(
                resume.title,
                style = MaterialTheme.typography.titleSmall,
                color = SoftColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${rom?.system?.emulator?.label ?: "Android game"}${missingNote(state, rom)} · ${resume.ago}",
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text2,
            )
        }
        Button(
            onClick = games.callbacks.onPlayResume,
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Play ${resume.title}" },
            colors = ButtonDefaults.buttonColors(
                containerColor = GamesTone.onContainer,
                contentColor = GamesTone.container,
            ),
        ) {
            PlayGlyph(GamesTone.container)
            Text("Play", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

private fun missingNote(state: GamesUiState, game: Game?): String =
    if (game != null && game.system.emulator in state.missingEmulators) " (not installed)" else ""

@Composable
private fun PlayGlyph(tint: Color) {
    Canvas(Modifier.size(14.dp)) {
        val path = Path().apply {
            moveTo(size.width * PLAY_LEFT, size.height * PLAY_TOP)
            lineTo(size.width * PLAY_RIGHT, size.height * PLAY_MID)
            lineTo(size.width * PLAY_LEFT, size.height * PLAY_BOTTOM)
            close()
        }
        drawPath(path, tint)
    }
}

