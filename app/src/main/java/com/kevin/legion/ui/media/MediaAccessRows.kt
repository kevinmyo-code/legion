package com.kevin.legion.ui.media

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Notification-listener access refused - the UI half of the fix commit d683d2c made for
 * `control_music`. [com.kevin.legion.media.NowPlayingController.hasAccess] had zero callers
 * before that commit's voice-tool fallback, and the underlying grant miss is silent everywhere
 * else too: [com.kevin.legion.media.MusicController] swallows the SecurityException into an
 * empty session list, [com.kevin.legion.media.NowPlayingController.init] swallows it on `init`,
 * and pause/skip just do nothing with no line anywhere saying why. This is that line, placed
 * where a driver actually looks for it - CLAUDE.md's worded-state rule applies here the same way
 * it does to ingestion provenance.
 *
 * **Renders nothing when [hasAccess] is true** - not an empty [Surface], not a spacer. This
 * banner exists for exactly one state and must vanish outright once that state is fixed, the same
 * contract [com.kevin.legion.ui.notes.NotificationsBlockedBanner] holds for
 * `POST_NOTIFICATIONS`, which this deliberately matches in shape: a worded line plus one
 * SETTINGS action, no dialog, no dismiss. See [MediaAccessResolver.shouldShowBanner] for the pure
 * predicate this wraps.
 *
 * **`ACTION_NOTIFICATION_LISTENER_SETTINGS`, not `ACTION_APP_NOTIFICATION_SETTINGS`.** The two
 * look interchangeable and are not: the latter (what
 * [com.kevin.legion.ui.notes.NotificationsBlockedBanner] opens) is the page for whether Legion
 * may POST notifications - a different Android permission from reading OTHER apps' media
 * sessions, which is what [com.kevin.legion.media.NowPlayingController.hasAccess] actually gates.
 * The right settings page is a bare list of every listener-capable app with a toggle each - there
 * is no per-app deep link into it, so this can only land the driver on the list, not on Legion's
 * row within it.
 */
@Composable
fun MediaTransportAccessBanner(hasAccess: Boolean) {
    if (!MediaAccessResolver.shouldShowBanner(hasNotificationAccess = hasAccess)) return
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ADVISORY (ticket 13 re-home): a blocked capability, not a failed gate.
        Text(
            MediaAccessResolver.BANNER_MESSAGE,
            style = LegionType.stamp,
            color = sem.estimated,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }) {
            Text("SETTINGS", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Media access: grant missing", widthDp = 360)
@Composable
private fun PreviewMediaAccessBannerMissing() = LegionTheme {
    Surface { MediaTransportAccessBanner(hasAccess = false) }
}

@Preview(name = "Media access: grant present (renders nothing)", widthDp = 360)
@Composable
private fun PreviewMediaAccessBannerGranted() = LegionTheme {
    Surface { MediaTransportAccessBanner(hasAccess = true) }
}
