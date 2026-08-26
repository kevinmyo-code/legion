package com.kevin.legion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.sync.SyncCapability
import com.kevin.legion.ui.SettingsNavRow
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.media.MediaTransportAccessBanner
import com.kevin.legion.ui.spotify.SpotifyConnectResolver
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * "Connections" - the third subscreen `settings/` split into (command-center ticket 02). Every
 * credential and external account the app holds a live status on: the Gemini key, Google (Drive/
 * Calendar/Gmail), and Spotify, plus the media-transport access banner that sits directly under
 * Spotify because that grant is what pause/skip/previous need and Spotify playback itself does not.
 *
 * OBD/vehicle pairing lives under the Fleet tab ([com.kevin.legion.ui.fleet.ObdDeviceScreen]), not
 * here - it was never a row on the old settings screen, so there is nothing of that shape to move.
 *
 * Every row is the same composable the old monolith called, unmoved in substance.
 */
@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    onOpenKeyScreen: () -> Unit,
    onOpenGoogleAccess: () -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenBackendMigration: () -> Unit,
) {
    val context = LocalContext.current

    var hasKey by remember { mutableStateOf(GeminiKeyProvider.hasKey()) }
    var driveConnected by remember { mutableStateOf(SyncCapability.syncAvailable(context)) }
    var playServices by remember { mutableStateOf(SyncCapability.playServicesAvailable(context)) }
    var spotifyStage by remember {
        mutableStateOf(
            SpotifyConnectResolver.stage(
                hasClientId = CompanionProfile.hasSpotifyClientId(context),
                isAuthorized = SpotifyWebApi.isAuthorized(context),
            ),
        )
    }
    // Notification-listener access - the system gate media transport (pause/skip/previous) needs
    // and the Gemini key/Drive/Spotify grants above have no equivalent of.
    var hasMediaAccess by remember { mutableStateOf(NowPlayingController.hasAccess(context)) }

    // Re-read on every resume, same reasoning the old monolith's own doc comment gave: coming BACK
    // from a sub-screen having just set a key or connected Drive is exactly the moment those need
    // to be fresh, and all four reads here are synchronous and on-device.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasKey = GeminiKeyProvider.hasKey()
        driveConnected = SyncCapability.syncAvailable(context)
        playServices = SyncCapability.playServicesAvailable(context)
        spotifyStage = SpotifyConnectResolver.stage(
            hasClientId = CompanionProfile.hasSpotifyClientId(context),
            isAuthorized = SpotifyWebApi.isAuthorized(context),
        )
        hasMediaAccess = NowPlayingController.hasAccess(context)
    }

    val sem = LocalLegionSemantics.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Connections", onBack = onBack)
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Credentials live on this device and in your own accounts. Nothing goes " +
                        "through a server I run.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
                Spacer(Modifier.height(12.dp))

                // attention on "Not set": no key means the assistant and every LLM fallback path
                // are dead, which is the one genuinely blocking state on this screen.
                SettingsNavRow(
                    label = "Gemini key",
                    status = if (hasKey) "Set" else "Not set - the assistant can't run without one",
                    onClick = onOpenKeyScreen,
                    attention = !hasKey,
                )

                // One row for all three Google grants (Drive/Calendar/Gmail) - ticket 06's Answer
                // §2: "the question 'what does this app have access to in my Google account'
                // deserves exactly one place to read the answer".
                Spacer(Modifier.height(8.dp))
                SettingsNavRow(
                    label = "Google",
                    status = when {
                        !playServices -> "Drive unavailable - no Play Services on this device"
                        driveConnected -> "Drive connected"
                        else -> "Drive not connected - data stays on this device"
                    },
                    onClick = onOpenGoogleAccess,
                )

                Spacer(Modifier.height(8.dp))
                SettingsNavRow(
                    label = "Spotify",
                    status = SpotifyConnectResolver.headline(spotifyStage),
                    onClick = onOpenSpotify,
                )

                // Renders nothing at all once the grant is present - kept right under the Spotify
                // row because that is the other place media transport shows up: Spotify playback
                // works without this grant, pause/skip do not.
                MediaTransportAccessBanner(hasAccess = hasMediaAccess)

                // Backend-erp Phase 4's hands path (`ui/settings/BackendMigrationScreen.kt`) -
                // the trigger for the three reconciles, which otherwise have zero production
                // callers despite being built and tested. Lives here rather than under the
                // Household section of the Gemini key screen because it is an action, not a
                // credential.
                Spacer(Modifier.height(8.dp))
                SettingsNavRow(
                    label = "Backend migration",
                    status = "Upload places, pantry, and notes+dates to your Supabase project.",
                    onClick = onOpenBackendMigration,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
