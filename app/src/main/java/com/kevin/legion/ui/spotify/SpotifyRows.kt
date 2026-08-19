package com.kevin.legion.ui.spotify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Plain UI half of `ui/SpotifyScreen.kt` (the state-holder/UI split,
 * `.claude/skills/compose-state-holder-ui-split`, same shape as
 * `ui/sync/DriveSyncRows.kt`). Everything here is display plus callbacks,
 * driven by [SpotifyConnectResolver.Stage] rather than owning
 * [com.kevin.legion.media.SpotifyController]/[com.kevin.legion.media.SpotifyWebApi]
 * itself, so it previews without a `Context` and without the App Remote aar.
 */

/**
 * The one-line state plus what it means, and the two hard environmental
 * requirements App Remote has that the app cannot satisfy for the driver.
 *
 * [spotifyAppInstalled] false is stated in [LegionSemantics.estimated] - ADVISORY
 * per ticket 04's tiers (mission-control ticket 13 re-home: a blocked capability
 * is not a failed gate), because it is a real blocker; the Premium note is
 * [LegionSemantics.faint] because it is a requirement, not an observed failure -
 * the app has no way to read the account tier and must not imply it can.
 */
@Composable
fun SpotifySetupStatusRow(
    stage: SpotifyConnectResolver.Stage,
    spotifyAppInstalled: Boolean,
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            SpotifyConnectResolver.headline(stage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            SpotifyConnectResolver.detail(stage),
            style = LegionType.stamp,
            color = sem.faint,
        )
        if (!spotifyAppInstalled) {
            Spacer(Modifier.height(6.dp))
            Text(
                SpotifyConnectResolver.APP_MISSING_MESSAGE,
                style = LegionType.stamp,
                color = sem.estimated,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(SpotifyConnectResolver.PREMIUM_NOTE, style = LegionType.stamp, color = sem.faint)
    }
}

/**
 * The client-ID field and its SAVE action. The stored ID is shown in full
 * rather than masked: a Spotify client ID is public by design (it is sent in
 * the authorize URL as a query parameter), so hiding it would imply a secrecy
 * it does not have and would stop the driver checking a bad paste.
 *
 * [onSave] is enabled on any non-blank text, including a value that fails
 * [SpotifyConnectResolver.checkClientId] - that check cautions, it does not
 * block (see its own doc for why).
 *
 * [onRemove] is offered only when an ID is actually stored ([hasStoredId]).
 * Without it there is no way out: SAVE is disabled on blank text, so clearing
 * the field and saving cannot remove anything, and DISCONNECT deliberately
 * keeps the ID. A driver who pasted the wrong value, or who wants Spotify off
 * this device entirely, would otherwise be stuck with it until a reinstall.
 */
@Composable
fun SpotifyClientIdRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    hasStoredId: Boolean,
    working: Boolean,
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Client ID", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "From your own app at developer.spotify.com/dashboard. Nothing ships a shared one.",
            style = LegionType.stamp,
            color = sem.faint,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            // Mono, and no autocorrect/autocapitalise: this is an opaque hex
            // token, and a keyboard that "helpfully" capitalises the first
            // character produces an ID that fails with no visible difference.
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            label = { Text("Paste client ID", style = LegionType.stamp) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (hasStoredId) {
                TextButton(onClick = onRemove, enabled = !working) {
                    Text("REMOVE", style = LegionType.stamp, color = sem.faint)
                }
            }
            TextButton(onClick = onSave, enabled = !working && value.isNotBlank()) {
                Text("SAVE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Everything the driver has to type into Spotify's dashboard, shown verbatim
 * with a copy action each: the app-fixed Redirect URI, and the package name +
 * SHA-1 fingerprint pair Spotify's "Android Packages" section requires before
 * App Remote will bind at all.
 *
 * These are the most error-prone steps in the whole flow, and they fail
 * silently: Spotify rejects the authorize request on any redirect mismatch,
 * and refuses the App Remote bind when the package/fingerprint pair is not
 * registered - neither with a message that names the cause. So each value is
 * monospace text to copy, never prose to transcribe.
 *
 * [sha1] null means the signature could not be read (see [com.kevin.legion.util.AppSigning.sha1]).
 * Said plainly rather than papered over with a placeholder, because a
 * fingerprint that is wrong is worse than one that is absent.
 */
@Composable
fun SpotifyRegistrationRow(
    packageName: String,
    sha1: String?,
    redirectUri: String,
    onCopy: (label: String, value: String) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "Register this build",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "In your Spotify app's settings: paste the Redirect URI, and add the package " +
                    "name and fingerprint under Android Packages. All three must match exactly.",
                style = LegionType.stamp,
                color = sem.faint,
            )

            CopyableValue("Redirect URI", redirectUri, onCopy)
            CopyableValue("Package name", packageName, onCopy)
            if (sha1 != null) {
                CopyableValue("SHA-1 fingerprint", sha1, onCopy)
            } else {
                Spacer(Modifier.height(8.dp))
                Text("SHA-1 fingerprint", style = LegionType.stamp, color = sem.faint)
                // ADVISORY (ticket 13 re-home): a blocked capability, not a failed gate.
                Text(
                    "Couldn't read this build's signing certificate.",
                    style = LegionType.stamp,
                    color = sem.estimated,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "The fingerprint is this build's. A release build signed with a different key " +
                    "needs its own entry - the package name is the same for both.",
                style = LegionType.stamp,
                color = sem.faint,
            )
        }
    }
}

/** One labelled monospace value with a COPY action. */
@Composable
private fun CopyableValue(label: String, value: String, onCopy: (String, String) -> Unit) {
    val sem = LocalLegionSemantics.current
    Spacer(Modifier.height(8.dp))
    Text(label, style = LegionType.stamp, color = sem.faint)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCopy(label, value) }) {
            Text("COPY", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The Web API browser grant (search-by-name) and, once that is done, the App
 * Remote player link.
 *
 * The two are shown as separate rows on purpose - they are separate grants
 * that fail for different reasons, and collapsing them into one "connected"
 * light is what would leave a driver staring at "Spotify isn't connected" with
 * no idea which half is missing. [playerLinked] is App Remote's live state,
 * re-read on resume rather than remembered, because App Remote drops on its
 * own whenever the Spotify app is killed.
 */
@Composable
fun SpotifyAuthorizeRow(
    stage: SpotifyConnectResolver.Stage,
    playerLinked: Boolean,
    working: Boolean,
    onAuthorize: () -> Unit,
    onLinkPlayer: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Search access", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        when (stage) {
                            SpotifyConnectResolver.Stage.READY -> "Approved"
                            // Deliberately NOT "Not approved" - the driver DID approve this
                            // before; the copy has to say the approval is stale, not absent.
                            SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION -> "Approval is out of date"
                            else -> "Not approved"
                        },
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                when (stage) {
                    SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID -> {
                        Text("SAVE AN ID FIRST", style = LegionType.stamp, color = sem.ghost)
                    }
                    SpotifyConnectResolver.Stage.NEEDS_AUTHORIZATION -> {
                        TextButton(onClick = onAuthorize, enabled = !working) {
                            Text(
                                if (working) "OPENING" else "AUTHORIZE",
                                style = LegionType.stamp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    // Same action as NEEDS_AUTHORIZATION (beginAuthorization asks for the CURRENT
                    // SCOPES regardless of what the old grant had), different label - RE-AUTHORIZE
                    // reads as "renew", not "start over", which is the whole point of this stage.
                    SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION -> {
                        TextButton(onClick = onAuthorize, enabled = !working) {
                            Text(
                                if (working) "OPENING" else "RE-AUTHORIZE",
                                style = LegionType.stamp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    SpotifyConnectResolver.Stage.READY -> {
                        TextButton(onClick = onDisconnect, enabled = !working) {
                            Text("DISCONNECT", style = LegionType.stamp, color = sem.faint)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Player link", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (playerLinked) "Linked to the Spotify app" else "Not linked",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                TextButton(
                    onClick = onLinkPlayer,
                    enabled = !working && stage != SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID,
                ) {
                    Text(
                        if (playerLinked) "RELINK" else "LINK PLAYER",
                        style = LegionType.stamp,
                        color = if (stage == SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID) sem.ghost else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Runs one real search and reports exactly what Spotify said.
 *
 * **Debug builds only** - [com.kevin.legion.ui.SpotifyScreen] renders it behind
 * `BuildConfig.DEBUG`. It shows raw HTTP statuses and Spotify's unedited error
 * bodies, which is a developer console, not a settings control.
 *
 * Exists because on this device app-authored `Log` output never reaches logcat
 * (observed 2026-08-12: zero LEGION-tagged lines across a full buffer covering a
 * confirmed failing request - the OEM appears to filter third-party app logs).
 * A failure that can only be diagnosed by reading logcat is, on this phone, a
 * failure that cannot be diagnosed at all, so the diagnostic has to be a surface
 * in the app. It also beats the voice path for this job: a spoken reply is
 * paraphrased by the model, and the exact status code and error string are what
 * matter here.
 */
@Composable
fun SpotifySearchTestRow(
    enabled: Boolean,
    working: Boolean,
    resultLine: String?,
    resultIsError: Boolean,
    onRunTest: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Search test", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Asks Spotify for one track and shows the raw answer.",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                TextButton(onClick = onRunTest, enabled = enabled && !working) {
                    Text(
                        if (working) "RUNNING" else "RUN TEST",
                        style = LegionType.stamp,
                        color = if (enabled) MaterialTheme.colorScheme.primary else sem.ghost,
                    )
                }
            }
            if (resultLine != null) {
                Spacer(Modifier.height(8.dp))
                // ADVISORY (ticket 13 re-home): a test-run result, not a failed gate.
                Text(
                    resultLine,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (resultIsError) sem.estimated else sem.faint,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Spotify: not set up", widthDp = 360)
@Composable
private fun PreviewStatusNotSetUp() = LegionTheme {
    Surface { SpotifySetupStatusRow(SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID, spotifyAppInstalled = true) }
}

@Preview(name = "Spotify: app missing", widthDp = 360)
@Composable
private fun PreviewStatusAppMissing() = LegionTheme {
    Surface { SpotifySetupStatusRow(SpotifyConnectResolver.Stage.NEEDS_AUTHORIZATION, spotifyAppInstalled = false) }
}

@Preview(name = "Spotify: set up", widthDp = 360)
@Composable
private fun PreviewStatusReady() = LegionTheme {
    Surface { SpotifySetupStatusRow(SpotifyConnectResolver.Stage.READY, spotifyAppInstalled = true) }
}

@Preview(name = "Spotify: needs re-approving (stale grant)", widthDp = 360)
@Composable
private fun PreviewStatusNeedsReauthorization() = LegionTheme {
    Surface { SpotifySetupStatusRow(SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION, spotifyAppInstalled = true) }
}

@Preview(name = "Spotify: client id empty", widthDp = 360)
@Composable
private fun PreviewClientIdEmpty() = LegionTheme {
    Surface {
        SpotifyClientIdRow(
            value = "", onValueChange = {}, onSave = {}, onRemove = {},
            hasStoredId = false, working = false,
        )
    }
}

@Preview(name = "Spotify: client id stored (REMOVE offered)", widthDp = 360)
@Composable
private fun PreviewClientIdFilled() = LegionTheme {
    Surface {
        SpotifyClientIdRow(
            value = "0123456789abcdef0123456789abcdef",
            onValueChange = {},
            onSave = {},
            onRemove = {},
            hasStoredId = true,
            working = false,
        )
    }
}

@Preview(name = "Spotify: registration values", widthDp = 360)
@Composable
private fun PreviewRegistration() = LegionTheme {
    Surface {
        SpotifyRegistrationRow(
            packageName = "com.kevin.legion",
            sha1 = "AE:C0:22:FB:19:02:8B:B4:66:49:0D:9E:2F:7B:C7:25:EE:84:F5:5B",
            redirectUri = "com.kevin.legion://spotify-callback",
            onCopy = { _, _ -> },
        )
    }
}

@Preview(name = "Spotify: registration, signature unreadable", widthDp = 360)
@Composable
private fun PreviewRegistrationNoSha() = LegionTheme {
    Surface {
        SpotifyRegistrationRow(
            packageName = "com.kevin.legion",
            sha1 = null,
            redirectUri = "com.kevin.legion://spotify-callback",
            onCopy = { _, _ -> },
        )
    }
}

@Preview(name = "Spotify: authorize (no id yet)", widthDp = 360)
@Composable
private fun PreviewAuthorizeNoId() = LegionTheme {
    Surface {
        SpotifyAuthorizeRow(
            stage = SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID,
            playerLinked = false, working = false,
            onAuthorize = {}, onLinkPlayer = {}, onDisconnect = {},
        )
    }
}

@Preview(name = "Spotify: authorize (ready to approve)", widthDp = 360)
@Composable
private fun PreviewAuthorizePending() = LegionTheme {
    Surface {
        SpotifyAuthorizeRow(
            stage = SpotifyConnectResolver.Stage.NEEDS_AUTHORIZATION,
            playerLinked = false, working = false,
            onAuthorize = {}, onLinkPlayer = {}, onDisconnect = {},
        )
    }
}

@Preview(name = "Spotify: authorized and linked", widthDp = 360)
@Composable
private fun PreviewAuthorizeReady() = LegionTheme {
    Surface {
        SpotifyAuthorizeRow(
            stage = SpotifyConnectResolver.Stage.READY,
            playerLinked = true, working = false,
            onAuthorize = {}, onLinkPlayer = {}, onDisconnect = {},
        )
    }
}

@Preview(name = "Spotify: needs re-approving (was linked before)", widthDp = 360)
@Composable
private fun PreviewAuthorizeNeedsReauthorization() = LegionTheme {
    Surface {
        SpotifyAuthorizeRow(
            stage = SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION,
            playerLinked = true, working = false,
            onAuthorize = {}, onLinkPlayer = {}, onDisconnect = {},
        )
    }
}
