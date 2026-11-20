package com.kevin.midnightai.ui.cruise

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.media.MediaEmptyState
import com.kevin.midnightai.media.MusicDeckLogic
import com.kevin.midnightai.media.NowPlayingInfo
import com.kevin.midnightai.ui.DeckMetrics
import com.kevin.midnightai.ui.NowPlayingBody
import com.kevin.midnightai.ui.PinnedPlaylistGrid
import com.kevin.midnightai.ui.SmallKey
import com.kevin.midnightai.ui.SourceLabelRow
import com.kevin.midnightai.ui.TransportKeys
import com.kevin.midnightai.ui.VolumeBars
import com.kevin.midnightai.ui.deckCard
import com.kevin.midnightai.ui.deckLineColor
import com.kevin.midnightai.ui.deckTileColor
import com.kevin.midnightai.ui.rememberTransportKeys
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaType

/** Which face the HU-1 music card shows. */
internal enum class MusicFace { NOW, LISTS }

/**
 * The HU-1 music card (Kevin's approved prototype, 2026-11-19: "song on the right"): ONE card
 * whose face flips between NOW PLAYING and the pinned PLAYLISTS with the NOW / LISTS keys (a cut,
 * no animation), while the transport row and the volume row stay on both faces. Keys are about
 * three times the split deck's ([MusicDeckLogic.heroKeys]).
 *
 * Kept from the split deck so no hands path is lost: the PHONE | SPOTIFY switch (header), shuffle
 * and like (beside the title, NOW face), the seeker, swipe-to-skip on the art, GRANT ACCESS, and
 * the pin picker on a long-press or an empty tile (LISTS face).
 */
@Composable
internal fun MusicHeroCard(
    u: Dp,
    m: DeckMetrics,
    info: NowPlayingInfo?,
    art: Bitmap?,
    contextName: String?,
    emptyState: MediaEmptyState,
    playing: Boolean,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
    debugLine: String? = null,
) {
    var face by remember { mutableStateOf(MusicFace.NOW) }
    val keys = rememberTransportKeys(info)
    Column(modifier.deckCard(m).padding(u), verticalArrangement = Arrangement.spacedBy(u)) {
        SourceLabelRow(m, title = null, modifier = Modifier.height(AriaDimens.driveTouchMin)) {
            SegmentPair(
                u = u,
                left = "NOW",
                right = "LISTS",
                rightSelected = face == MusicFace.LISTS,
                onLeft = { face = MusicFace.NOW },
                onRight = { face = MusicFace.LISTS },
                keyMinWidth = AriaDimens.driveTouchMin,
                labelSize = uSp(u, 1.3f, 11.sp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when (face) {
                MusicFace.NOW -> NowPlayingBody(
                    m = m,
                    info = info,
                    art = art,
                    contextName = contextName,
                    emptyState = emptyState,
                    artModifier = Modifier.fillMaxWidth().weight(1f),
                    debugLine = debugLine,
                    titleTrailing = {
                        SmallKey(m, "⇄", on = keys.extras && keys.shuffling, enabled = keys.extras, description = "Shuffle", onClick = keys.onShuffle)
                        SmallKey(m, if (keys.extras && keys.liked) "♥" else "♡", on = keys.extras && keys.liked, enabled = keys.extras, description = "Like", onClick = keys.onLike)
                    },
                )
                MusicFace.LISTS -> PinnedPlaylistGrid(
                    m = m,
                    playing = playing,
                    onOpenPicker = onOpenPicker,
                    modifier = Modifier.fillMaxSize(),
                    rowHeight = AriaDimens.driveTouchMin,
                    framed = false,
                    scroll = true,
                )
            }
        }
        HeroKeys(u, keys)
    }
}

/** Previous / play / next, then volume - / readout / +. Sized by [MusicDeckLogic.heroKeys] from the measured width. */
@Composable
private fun HeroKeys(u: Dp, k: TransportKeys) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sizes = MusicDeckLogic.heroKeys(maxWidth.value, u.value)
        Column(verticalArrangement = Arrangement.spacedBy(u)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u, Alignment.CenterHorizontally)) {
                HeroKey(u, sizes.mid.dp, sizes.height.dp, "◀◀", "Previous", accent = false, onClick = k.onPrevious)
                HeroKey(
                    u, sizes.big.dp, sizes.height.dp,
                    if (k.playing) "❚❚" else "▶", if (k.playing) "Pause" else "Play",
                    accent = true, onClick = k.onPlayPause,
                )
                HeroKey(u, sizes.mid.dp, sizes.height.dp, "▶▶", "Next", accent = false, onClick = k.onNext)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeroKey(u, sizes.volumeWidth.dp, sizes.volumeHeight.dp, "−", "Volume down", accent = false, onClick = k.onVolumeDown)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (sizes.meterFits) {
                        VolumeBars(percent = k.volumePct, height = sizes.volumeHeight.dp * 0.55f, barWidth = 6.dp, barGap = 4.dp)
                    }
                }
                HeroKey(u, sizes.volumeWidth.dp, sizes.volumeHeight.dp, "＋", "Volume up", accent = false, onClick = k.onVolumeUp)
            }
        }
    }
}

/** A tall rounded key: tile-coloured with a hairline, or ([accent]) the play key in the deck's coral. */
@Composable
private fun HeroKey(u: Dp, width: Dp, height: Dp, glyph: String, description: String, accent: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(u * 1.75f)
    Box(
        Modifier
            .size(width, height)
            .clip(shape)
            .background(if (accent) AriaColors.NeonMagenta else deckTileColor())
            .border(1.dp, if (accent) AriaColors.NeonMagenta else deckLineColor(), shape)
            .clickable(onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = AriaType.readout.copy(fontSize = uSp(u, if (accent) 4.2f else 3.4f, 18.sp), letterSpacing = 0.sp),
            color = if (accent) AriaColors.Panel else AriaColors.TextPrimary,
        )
    }
}
