@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.games.AndroidGame
import com.kevin.legion.games.Game
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftColors

// Shelves, tiles, covers and the launching toast for the Games sheet. Split from GamesSheet.kt, which
// holds the sheet window, the folder prompts and the Continue card, to stay under detekt's
// functions-per-file limit.

@Composable
internal fun Shelf(shelf: Shelf, state: GamesUiState, games: GamesHome) {
    val emulator = shelf.system.emulator
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            shelf.system.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = SoftColors.text,
        )
        // Said in words before a tap fails, never by colour alone.
        val missing = emulator in state.missingEmulators
        Text(
            if (missing) "${emulator.label} · not installed" else emulator.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (missing) SoftColors.caution else SoftColors.text3,
        )
    }
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(shelf.games, key = { it.documentId }) { game -> Tile(game, games) }
    }
}

@Composable
private fun Tile(game: Game, games: GamesHome) {
    Column(
        Modifier
            .width(TILE_WIDTH)
            .clickable(role = Role.Button, onClickLabel = "Play ${game.title}") { games.callbacks.onPlay(game) },
    ) {
        RomCover(game, games, Modifier.width(TILE_WIDTH).height(TILE_HEIGHT), small = false)
        TileTitle(game.title)
    }
}


@Composable
private fun TileTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = SoftColors.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp).heightIn(min = 30.dp),
    )
}

/** The "Android games" shelf: installed apps the system files under games. Hidden when there are none. */

@Composable
internal fun AndroidShelf(games: GamesHome) {
    val apps = games.state.androidGames
    if (apps.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text("Android games", style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
        Text("Installed apps", style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
    }
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            Column(
                Modifier
                    .width(TILE_WIDTH)
                    .clickable(role = Role.Button, onClickLabel = "Play ${app.label}") {
                        games.callbacks.onPlayApp(app)
                    },
            ) {
                AppCover(app, games, Modifier.width(TILE_WIDTH).height(TILE_HEIGHT))
                TileTitle(app.label)
            }
        }
    }
}

/**
 * A ROM's cover: the cached box art if there is one, else a flat colour that says the title and
 * system in words (no cover is never a blank tile). The system badge sits top-left either way.
 */
@Composable
internal fun RomCover(game: Game, games: GamesHome, modifier: Modifier, small: Boolean) {
    val art by produceState<ImageBitmap?>(null, game.documentId) { value = games.cover(game) }
    val flat = AreaAccent.entries[game.system.ordinal % AreaAccent.entries.size].container
    CoverBox(modifier, flat, game.system.badge) {
        val image = art
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!small) {
            Text(
                game.title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = SoftColors.text2,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            )
        }
    }
}

/** A native game's tile: the app's own icon on a flat colour, no box-art fetch. */
@Composable
internal fun AppCover(app: AndroidGame, games: GamesHome, modifier: Modifier) {
    val icon by produceState<ImageBitmap?>(null, app.packageName) { value = games.appIcon(app) }
    CoverBox(modifier, SoftColors.cardHighest, "APP") {
        val image = icon
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.align(Alignment.Center).size(56.dp))
        } else {
            Text(
                app.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = SoftColors.text2,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun CoverBox(modifier: Modifier, flat: Color, badge: String, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(TILE_SHAPE).background(flat)) {
        content()
        Text(
            badge,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(5.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** The launching toast on HOME (prototype A): shown only for a launch that [GamesViewModel] saw succeed. */
@Composable
fun GamesToast(notice: GameNotice?, modifier: Modifier = Modifier) {
    if (notice == null || notice.failed) return
    // Split at the LAST " in " so a title containing one ("Zelle in the Sky") stays whole.
    val title = notice.text.substringBeforeLast(" in ", notice.text)
    val rest = if (" in " in notice.text) notice.text.substringAfterLast(" in ") else ""
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SoftColors.text)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).background(GamesTone.onContainer, RoundedCornerShape(5.dp)))
        Column {
            val body = MaterialTheme.typography.bodyMedium
            Text(title, style = body.copy(fontWeight = FontWeight.Bold), color = SoftColors.ground)
            if (rest.isNotEmpty()) Text("in $rest", style = body, color = SoftColors.ground)
        }
    }
}
