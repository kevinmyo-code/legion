package com.kevin.midnightai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kevin.midnightai.ai.CompanionProfile
import android.widget.Toast
import com.kevin.midnightai.media.AppRemoteLink
import com.kevin.midnightai.media.SpotifyAppProbe
import com.kevin.midnightai.media.SpotifyConnect
import com.kevin.midnightai.media.SpotifyConnectLogic
import com.kevin.midnightai.media.SpotifyController
import com.kevin.midnightai.media.SpotifyPlaylists
import com.kevin.midnightai.media.SpotifyScopes
import com.kevin.midnightai.media.SpotifyWebApi
import com.kevin.midnightai.ui.theme.AriaColors

private const val SPOTIFY_DASHBOARD_URL = "https://developer.spotify.com/dashboard"

/**
 * Reusable Spotify client-ID field (BYO, optional power-user add-on, 2026-07-21
 * reopen - see memory/library/decisions.md). The driver registers their own
 * Spotify developer app and pastes only its client ID here; the redirect URI is
 * app-fixed ([SpotifyController.REDIRECT_URI]) and shown so they can copy it
 * verbatim into their own Spotify dashboard.
 *
 * Unlike the Gemini key this is never required - leaving it blank just keeps
 * music on phone-BT / mixtape, optional.
 */
@Composable
fun SpotifyKeyEntry(modifier: Modifier = Modifier, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    var clientId by remember { mutableStateOf(CompanionProfile.spotifyClientId(context)) }
    var statusVersion by remember { mutableIntStateOf(0) }

    // The browser consent returns to MainActivity, which finishes the token exchange and kicks a
    // playlist refresh; neither touches this composable's state. Resume re-reads the stored grant,
    // and the playlists flow (collected below) re-reads it again when the refresh lands.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statusVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val playlists = SpotifyPlaylists.state.collectAsState().value

    val status = remember(statusVersion) {
        when {
            !CompanionProfile.hasSpotifyClientId(context) ->
                "No Spotify client ID - music stays on phone Bluetooth / mixtapes."
            // Authorized means the Web API search is available, which is the
            // difference between playing a spoken request in place and having to
            // hand off to the Spotify app's own screen.
            SpotifyWebApi.needsReconnect(context) ->
                "Spotify needs re-approving - new permissions for playlists, queue and library. " +
                    "Tap CONNECT and approve once."
            SpotifyWebApi.isAuthorized(context) ->
                "Spotify authorized - \"play <song>\" plays here without leaving Cruise."
            else ->
                "Client ID saved. Tap CONNECT and approve in the browser so \"play <song>\" " +
                    "can search - without it, playing by name opens the Spotify app instead."
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Spotify client ID (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(10.dp))
        // Keyed on App Remote's link (as ControlPanelScreen is): a connect or failure that lands
        // while this screen is up - CONNECT's consent sheet, onResume's silent retry - updates it.
        val appRemoteLink by SpotifyController.link.collectAsState()
        if (CompanionProfile.hasSpotifyClientId(context)) {
            val lines = remember(statusVersion, playlists, appRemoteLink) {
                val appRemoteConnected = SpotifyController.effectiveLink() == AppRemoteLink.CONNECTED
                spotifyStatusLines(
                    appRemoteConnected = appRemoteConnected,
                    webAuthorized = SpotifyWebApi.isAuthorized(context),
                    grantedScope = SpotifyWebApi.grantedScope(context),
                    playlistCount = playlists.library.size,
                    lastError = playlists.lastError,
                    appRemoteFailure = SpotifyController.failureDetail(SpotifyController.lastFailure),
                    // Which Spotify app App Remote would bind to, only while it is not connected.
                    spotifyApp = if (appRemoteConnected) null else SpotifyAppProbe.describe(SpotifyAppProbe.probe(context)),
                    webPlaybackFailure = SpotifyConnect.lastFailure?.let { SpotifyConnectLogic.shortReason(it) },
                )
            }
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AriaColors.TextDim)
            }
            Spacer(Modifier.height(10.dp))
            Row {
                // Always begins the consent flow, whatever the stored state: CONNECT below skips it
                // for an authorized grant, so a grant that is authorized but broken (missing
                // scopes, stale) could otherwise only be fixed by CLEAR, which wipes the client ID.
                OutlinedButton(
                    onClick = {
                        if (!SpotifyWebApi.beginAuthorization(context)) {
                            Toast.makeText(context, "Can't open Spotify sign-in on this unit", Toast.LENGTH_SHORT).show()
                        }
                        statusVersion++
                    },
                ) { Text("RE-AUTHORIZE SPOTIFY") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        SpotifyPlaylists.refreshIfStale(context, force = true)
                        statusVersion++
                    },
                    enabled = SpotifyWebApi.isAuthorized(context),
                ) { Text("REFRESH PLAYLISTS") }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row {
            Button(
                onClick = {
                    CompanionProfile.saveSpotifyClientId(context, clientId)
                    statusVersion++
                    onSaved()
                },
                enabled = clientId.isNotBlank(),
            ) { Text("SAVE ID") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    CompanionProfile.saveSpotifyClientId(context, clientId)
                    // App Remote alone can only transport and play a URI it's handed -
                    // it has no search. Authorizing the Web API (PKCE, opens a browser
                    // and returns via the redirect) is what lets "play <name>" resolve
                    // in-app instead of foregrounding the Spotify app.
                    //
                    // ONE consent at a time (2026-10-13). This used to start App Remote's
                    // consent AND open the browser in the same tap: Spotify's own consent
                    // sheet (shown by the Spotify app) was immediately buried under the
                    // browser, and the redirect's connectWithConsent then joined that buried
                    // attempt instead of asking again - so approving in the browser never
                    // produced an App Remote grant. Now: browser first when the Web grant is
                    // missing (the redirect in MainActivity connects App Remote WITH its
                    // sheet once we are back in front), App Remote directly otherwise.
                    val needsWeb = !SpotifyWebApi.isAuthorized(context) || SpotifyWebApi.needsReconnect(context)
                    if (!needsWeb || !SpotifyWebApi.beginAuthorization(context)) {
                        SpotifyController.connect(context)
                    }
                    statusVersion++
                },
                enabled = clientId.isNotBlank(),
            ) { Text("CONNECT") }
            // The client ID is optional, so clearing it must be possible - blank-save
            // is the path in CompanionProfile.saveSpotifyClientId, but SAVE is disabled
            // on blank text, so give clearing its own button, and drop any live connection.
            if (CompanionProfile.hasSpotifyClientId(context)) {
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        SpotifyController.disconnect()
                        CompanionProfile.clearSpotifyTokens(context)
                        CompanionProfile.saveSpotifyClientId(context, "")
                        clientId = ""
                        statusVersion++
                    },
                ) { Text("CLEAR", color = AriaColors.TextDim) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Register your own app at developer.spotify.com/dashboard, " +
                "then set its Redirect URI to exactly:",
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.TextDim,
        )
        Text(
            text = SpotifyController.REDIRECT_URI,
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.Amber,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Open developer.spotify.com/dashboard",
            style = MaterialTheme.typography.bodySmall,
            color = AriaColors.Amber,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(SPOTIFY_DASHBOARD_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
        )
    }
}

/**
 * The Settings status block, as plain sentences so a silent Cruise row is explainable. Names only
 * for missing scopes (never the token). Pure: every input is passed in, so it runs in a JVM test.
 *
 * "Not fetched yet" is only claimed when there is neither a list nor a recorded failure - an
 * empty list with an error always reports the error instead.
 */
internal fun spotifyStatusLines(
    appRemoteConnected: Boolean,
    webAuthorized: Boolean,
    grantedScope: String,
    playlistCount: Int,
    lastError: String?,
    appRemoteFailure: String? = null,
    spotifyApp: String? = null,
    webPlaybackFailure: String? = null,
): List<String> = buildList {
    add(
        "App Remote (playback): " + when {
            appRemoteConnected -> "connected"
            // The exact reason the last attempt failed, so a car test says WHY, not just "no".
            appRemoteFailure != null -> "not connected - $appRemoteFailure"
            else -> "not connected - tap CONNECT and accept Spotify's prompt"
        },
    )
    spotifyApp?.let { add(it) }
    add("Web API (search, playlists): " + if (webAuthorized) "authorized" else "not authorized")
    if (webAuthorized) {
        val missing = SpotifyScopes.missing(grantedScope)
        add(
            if (missing.isEmpty()) {
                "Permissions granted: all requested"
            } else {
                "Permissions missing: " + missing.joinToString(", ")
            },
        )
        // Playback without App Remote goes through the Web API and needs its two player scopes.
        val playbackMissing = SpotifyConnectLogic.missingPlaybackScopes(grantedScope)
        add(
            "Web API playback (used when App Remote is off): " + when {
                playbackMissing.isNotEmpty() ->
                    "needs RE-AUTHORIZE SPOTIFY (missing ${playbackMissing.joinToString(", ")})"
                webPlaybackFailure != null -> "last try failed - $webPlaybackFailure"
                else -> "ready"
            },
        )
        add(
            when {
                playlistCount > 0 -> "$playlistCount playlists loaded"
                lastError != null -> "Playlists not loaded: $lastError"
                else -> "Playlists: not fetched yet"
            },
        )
    }
}
