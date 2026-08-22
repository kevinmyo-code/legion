package com.kevin.legion.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * A compact now-playing strip - title/artist plus a single play/pause control - built for
 * command-center ticket 01's Home rewrite to drop into the day's command center alongside news,
 * todos, and the rest (`.scratch/command-center/issues/01-*.md` is the consumer; this ticket
 * (04) only exports the composable, it does not place it). Deliberately the SMALLEST useful
 * slice of [MediaScreen] - no next/previous, no volume, no queue - a tap anywhere on the row is
 * meant to open the full panel, which [onOpenMedia] wires.
 *
 * Renders nothing when nothing is playing ([NowPlayingController.state] null) rather than an
 * empty shell - Home's command center is asked to show what IS true about the day, and "nothing
 * playing" is a fact worth a line elsewhere (or worth just not taking up a card), not a reason to
 * draw a card with two blank labels in it. That call belongs to ticket 01's own layout; this
 * composable stays honest either way by returning early.
 *
 * Calls [NowPlayingController.init] on first composition, same as [MediaScreen] - see that
 * screen's own doc for why this is safe (idempotent, never connects to Spotify, never a side
 * effect ADR-relevant rule 4 in the map's ticket forbids).
 */
@Composable
fun MediaMiniBar(onOpenMedia: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { NowPlayingController.init(context) }

    val info by NowPlayingController.state.collectAsStateWithLifecycle()
    val current = info ?: return

    Surface(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenMedia),
            ) {
                Text(
                    current.title,
                    style = LegionType.reading,
                    color = sem.chromeText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current.artist.isNotBlank()) {
                    Text(
                        current.artist,
                        style = LegionType.stamp,
                        color = sem.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DeckButton(
                text = if (current.isPlaying) "PAUSE" else "PLAY",
                enabled = !working,
                onClick = {
                    working = true
                    scope.launch {
                        MediaTransport.run(context, if (current.isPlaying) MediaTransport.Action.PAUSE else MediaTransport.Action.PLAY)
                        working = false
                    }
                },
            )
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Mini bar: playing", widthDp = 360)
@Composable
private fun PreviewMediaMiniBarPlaying() = LegionTheme {
    Surface { PreviewMiniBarRow(title = "Discovery", artist = "Daft Punk", isPlaying = true) }
}

@Preview(name = "Mini bar: paused", widthDp = 360)
@Composable
private fun PreviewMediaMiniBarPaused() = LegionTheme {
    Surface { PreviewMiniBarRow(title = "Discovery", artist = "Daft Punk", isPlaying = false) }
}

/** Preview-only stand-in for [MediaMiniBar]'s row - previews cannot fake [NowPlayingController]'s live StateFlow. */
@Composable
private fun PreviewMiniBarRow(title: String, artist: String, isPlaying: Boolean) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LegionType.reading, color = sem.chromeText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = LegionType.stamp, color = sem.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DeckButton(text = if (isPlaying) "PAUSE" else "PLAY", onClick = {})
    }
}
