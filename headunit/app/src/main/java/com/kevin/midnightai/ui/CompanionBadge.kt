package com.kevin.midnightai.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ai.AvatarStudio
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.media.MusicRouter
import com.kevin.midnightai.media.NowPlayingController
import com.kevin.midnightai.service.CompanionPhase
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType

/**
 * The **companion badge** content — a small circular avatar (tap to talk,
 * ringed with the same phase colors as Cruise) and minimal music transport
 * (prev / play-pause / next) below it. Hosted in the floating overlay window
 * built by [com.kevin.midnightai.service.CompanionBadgeController]; see its
 * doc for why this replaced Split-Screen Companion Mode's native pane + full
 * panel. Deliberately minimal — no caption, no notice, no telemetry, no
 * settings/return affordance (Home already returns to Midnight AI).
 */
@Composable
internal fun CompanionBadge(context: Context) {
    val bgVersion by AppBackground.version.collectAsState()
    val avatars = remember(bgVersion) { AvatarStudio.loadStates(context, AvatarStudio.readAvatarId(context)) }

    val phase by CompanionPhase.phase.collectAsState()
    val face = avatars?.let { it[faceKey(phase)] ?: it[AvatarStudio.STATE_IDLE] }

    val nowPlaying by NowPlayingController.state.collectAsState()
    val playing = nowPlaying?.isActive == true

    Column(
        modifier = Modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AriaColors.Panel)
                .border(2.dp, ringColor(phase), CircleShape)
                .clickable { talk(context) },
            contentAlignment = Alignment.Center,
        ) {
            if (face != null) {
                // Same head/shoulders-preserving crop as CruiseScreen's
                // AvatarVibe: the badge circle is small and roughly square,
                // and a Center-aligned Crop on a tall portrait loses the head.
                Image(
                    bitmap = face.asImageBitmap(),
                    contentDescription = "Talk",
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            } else {
                Text("◔", style = AriaType.readout.copy(fontSize = 28.sp), color = AriaColors.Amber)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgeTransportButton("◀◀", 32.dp, 11.sp) { MusicRouter.previous(context) }
            BadgeTransportButton(if (playing) "❚❚" else "▶", 40.dp, 15.sp) {
                if (playing) MusicRouter.pause(context) else MusicRouter.play(context)
            }
            BadgeTransportButton("▶▶", 32.dp, 11.sp) { MusicRouter.next(context) }
        }
    }
}

@Composable
private fun BadgeTransportButton(glyph: String, size: Dp, glyphSize: TextUnit, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.6f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = AriaType.readout.copy(fontSize = glyphSize), color = AriaColors.Amber)
    }
}
