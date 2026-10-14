package com.kevin.midnightai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.media.MediaDiagnostics
import com.kevin.midnightai.media.MetadataFillState
import com.kevin.midnightai.media.NowPlayingController
import com.kevin.midnightai.media.openNotificationAccessSettings
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.delay

/**
 * A plain, honest read of the raw MediaSession state, reachable from the home
 * screen. Kevin's report is that the head unit's OWN stock display shows
 * nothing when his wife plays Spotify from her phone over Bluetooth - this
 * screen exists so one look in the car settles whether AVRCP metadata reaches
 * this ROM at ALL, rather than leaving that question to a guess at a blank
 * card. Every line here is a fact this app can actually observe, never an
 * inference dressed up as one - see [MediaDiagnostics]'s kdoc for why this
 * reads MediaSessionManager directly rather than through
 * [NowPlayingController]'s already-collapsed StateFlow.
 */
@Composable
internal fun MediaDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(MediaDiagnostics.snapshot(context)) }
    // Re-read on a short interval rather than once: this screen's whole job is
    // "watch what happens when she connects Bluetooth and hits play in the
    // car", which has to be live, not a one-shot snapshot taken before she's
    // even paired.
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = MediaDiagnostics.snapshot(context)
            delay(1500)
        }
    }
    val nowPlaying by NowPlayingController.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "‹ BACK",
                style = AriaType.neonLabel,
                color = AriaColors.Amber,
                modifier = Modifier.clickable { onBack() }.padding(vertical = 8.dp, horizontal = 4.dp),
            )
            Text(
                "MEDIA DIAGNOSTICS",
                style = AriaType.stamp.copy(fontSize = 16.sp),
                color = AriaColors.TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(52.dp))
        }
        Spacer(Modifier.height(16.dp))

        DiagnosticLine(
            "Notification access",
            if (snapshot.hasNotificationAccess) "GRANTED" else "NOT GRANTED",
        )
        if (!snapshot.hasNotificationAccess) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Without this, the OS refuses getActiveSessions() and everything " +
                    "below is meaningless - nothing can be read at all, not even " +
                    "\"nothing is playing\".",
                style = AriaType.logbookBody.copy(fontSize = 12.sp),
                color = AriaColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "OPEN NOTIFICATION ACCESS SETTINGS",
                style = AriaType.neonLabel.copy(fontSize = 11.sp),
                color = AriaColors.Amber,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, AriaColors.Amber.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .clickable { openNotificationAccessSettings(context) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        DiagnosticLine("Active MediaSessions found", snapshot.sessions.size.toString())
        Spacer(Modifier.height(8.dp))

        if (snapshot.hasNotificationAccess && snapshot.sessions.isEmpty()) {
            Text(
                "Nothing is playing right now (access is granted, so this is a " +
                    "genuine \"no session\", not a permissions problem).",
                style = AriaType.logbookBody.copy(fontSize = 12.sp),
                color = AriaColors.TextDim,
            )
        }

        snapshot.sessions.forEach { s ->
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AriaColors.Panel.copy(alpha = 0.5f))
                    .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(s.packageName, style = AriaType.neonLabel.copy(fontSize = 12.sp), color = AriaColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("playback state: ${s.playbackStateLabel}", style = AriaType.logbookBody.copy(fontSize = 12.sp), color = AriaColors.TextDim)
                Text(
                    "metadata: ${metadataStateLabel(s.metadataState)}",
                    style = AriaType.logbookBody.copy(fontSize = 12.sp),
                    color = AriaColors.TextDim,
                )
                Text(
                    "declares transport support: ${s.declaredActionsLabel}",
                    style = AriaType.logbookBody.copy(fontSize = 12.sp),
                    color = AriaColors.TextDim,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        DiagnosticLine("NowPlayingController currently holds", if (nowPlaying == null) "nothing" else "a track")
        nowPlaying?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "\"${it.title}\" by \"${it.artist}\" (isPlaying=${it.isPlaying})",
                style = AriaType.logbookBody.copy(fontSize = 12.sp),
                color = AriaColors.TextDim,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "\"declares transport support\" reads what the session itself " +
                "advertises via PlaybackState.getActions() - this app never sends " +
                "a real play/pause here just to test it, since that would change " +
                "actual playback for the sake of a diagnostics screen.",
            style = AriaType.logbookBody.copy(fontSize = 11.sp),
            color = AriaColors.TextDim.copy(alpha = 0.8f),
        )
    }
}

private fun metadataStateLabel(state: MetadataFillState): String = when (state) {
    MetadataFillState.NULL -> "null (the session has no MediaMetadata object at all)"
    MetadataFillState.EMPTY -> "empty (metadata exists, but title/artist/album are all blank)"
    MetadataFillState.POPULATED -> "populated"
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = AriaType.neonLabel.copy(fontSize = 12.sp), color = AriaColors.TextDim, modifier = Modifier.weight(1f))
        Text(value, style = AriaType.neonLabel.copy(fontSize = 12.sp), color = AriaColors.TextPrimary)
    }
}
