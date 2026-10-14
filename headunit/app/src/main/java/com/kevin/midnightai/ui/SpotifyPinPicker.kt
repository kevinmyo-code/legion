package com.kevin.midnightai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.media.SpotifyPinLogic
import com.kevin.midnightai.media.SpotifyPins
import com.kevin.midnightai.media.SpotifyPlaylist
import com.kevin.midnightai.media.SpotifyPlaylists
import com.kevin.midnightai.media.SpotifyWebApi
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaType

/**
 * Settings > Connections > Spotify > "Pinned playlists". Choose which (up to
 * [SpotifyPinLogic.MAX_PINS]) playlists the Cruise grid shows, and in what order. With none
 * pinned Cruise falls back to the first ten in library order. Up/down buttons rather than
 * drag: it is a parked surface but the head unit's touch panel is not one to trust with drags.
 */
@Composable
internal fun SpotifyPinPicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by SpotifyPlaylists.state.collectAsState()
    val pins by SpotifyPins.pinned.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        SpotifyPins.load(context)
        SpotifyPlaylists.refreshIfStale(context)
    }

    Column(modifier.fillMaxWidth()) {
        Text("Pinned playlists", style = AriaType.readout.copy(fontSize = 15.sp), color = AriaColors.TextPrimary)
        Text(
            "${pins.size}/${SpotifyPinLogic.MAX_PINS} pinned. Pinned playlists fill the home screen grid in " +
                "this order; with none pinned it shows the first ten in your library. Long-press a " +
                "playlist on the home screen to come back here.",
            style = AriaType.readout.copy(fontSize = 12.sp),
            color = AriaColors.TextDim,
            modifier = Modifier.padding(top = AriaDimens.s1),
        )
        message?.let {
            Text(
                it,
                style = AriaType.neonLabel.copy(fontSize = 11.sp),
                color = AriaColors.Amber,
                modifier = Modifier.padding(top = AriaDimens.s1),
            )
        }
        Spacer(Modifier.height(AriaDimens.s2))

        if (!SpotifyWebApi.isAuthorized(context)) {
            Text(
                "Authorize Spotify above to see your playlists.",
                style = AriaType.readout.copy(fontSize = 12.sp),
                color = AriaColors.TextDim,
            )
            return@Column
        }
        if (state.needsReconnect) {
            PinButton("RECONNECT SPOTIFY") { SpotifyWebApi.beginAuthorization(context) }
            Spacer(Modifier.height(AriaDimens.s2))
        }

        val libraryIds = state.library.map { it.id }.toSet()
        pins.forEachIndexed { i, p ->
            PinRow(
                title = p.name,
                note = if (state.library.isNotEmpty() && p.id !in libraryIds) "not in your library right now" else null,
            ) {
                if (i > 0) PinButton("UP") { SpotifyPins.move(context, p.id, -1) }
                if (i < pins.lastIndex) PinButton("DOWN") { SpotifyPins.move(context, p.id, +1) }
                PinButton("UNPIN") { message = null; SpotifyPins.unpin(context, p.id) }
            }
        }

        val pinnedIds = pins.map { it.id }.toSet()
        val available = state.library.filter { it.id !in pinnedIds }
        if (available.isNotEmpty()) {
            Spacer(Modifier.height(AriaDimens.s3))
            Text("Your playlists", style = AriaType.readout.copy(fontSize = 13.sp), color = AriaColors.TextDim)
            Spacer(Modifier.height(AriaDimens.s1))
            available.forEach { p ->
                PinRow(title = p.name, note = null) {
                    PinButton("PIN") { message = pinMessage(context, p) }
                }
            }
        } else if (state.library.isEmpty()) {
            Text(
                "No playlists loaded yet - connect to the internet and reopen this page.",
                style = AriaType.readout.copy(fontSize = 12.sp),
                color = AriaColors.TextDim,
            )
        }
    }
}

/** Pins [p]; returns the line to show (null on success, the refusal once the grid is full). */
private fun pinMessage(context: android.content.Context, p: SpotifyPlaylist): String? =
    if (SpotifyPins.pin(context, p)) null
    else "${SpotifyPinLogic.MAX_PINS} already pinned - unpin one first."

@Composable
private fun PinRow(title: String, note: String?, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AriaColors.Panel.copy(alpha = 0.6f))
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .padding(horizontal = AriaDimens.s2, vertical = AriaDimens.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AriaDimens.s1),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AriaType.readout.copy(fontSize = 13.sp),
                color = AriaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null) {
                Text(note, style = AriaType.readout.copy(fontSize = 10.sp), color = AriaColors.TextDim)
            }
        }
        actions()
    }
}

@Composable
private fun PinButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = AriaType.neonLabel.copy(fontSize = 11.sp, letterSpacing = 1.sp),
        color = AriaColors.Amber,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = AriaDimens.s2, vertical = AriaDimens.s1 + 2.dp),
    )
}
