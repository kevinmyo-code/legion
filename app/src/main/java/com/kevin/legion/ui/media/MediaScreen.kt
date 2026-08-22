package com.kevin.legion.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.QueuedTrack
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.media.VolumeController
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.common.ReadingRow
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.spotify.SpotifyConnectResolver
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * `settings/spotify/media` - the hands path onto all five music voice tools (ADR 0035,
 * command-center ticket 04, `.scratch/command-center/issues/04-media-panel.md`). Now-playing,
 * transport, and volume mirror `control_music`/`control_volume`'s exact dispatch
 * ([MediaTransport], traced from [com.kevin.legion.service.LiveToolbox]'s `controlMusicTransport`
 * - see that object's own doc); search-and-play, library browse, and the queue read mirror
 * `play_music`/`browse_my_music`/`get_music_queue`'s exact resolution over [SpotifyWebApi] and
 * [SpotifyController.playUri] ([MediaSearchResolver], [MediaLibraryResolver] carry the identical
 * wording those tools use for every failure).
 *
 * **Now-playing/transport/volume need no Spotify at all** - they read/drive whatever media
 * session is actually active, same as [NowPlayingController]/[MediaTransport] themselves. Only
 * search-and-play, library browse, and the queue read need the Web API grant, so only THOSE
 * three sections are gated on [MediaSpotifyGateResolver.searchReady] - gating the whole screen on
 * Spotify would be dishonest to a driver whose music is coming from their phone over Bluetooth.
 *
 * **Opening this screen causes no Spotify side effect.** [SpotifyController.ensureConnected] and
 * [SpotifyWebApi] calls only ever run from an explicit tap (SEARCH, a browse source, PLAY on a
 * result) - never from [LaunchedEffect(Unit)][LaunchedEffect], which only calls
 * [NowPlayingController.init] (idempotent, connects nothing, see that function's own doc).
 * Command-center ticket 04's rule, restated: "no auto-connect side effects from merely opening
 * the panel."
 */
@Composable
fun MediaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current

    val nowPlaying by NowPlayingController.state.collectAsStateWithLifecycle()
    var hasNotificationAccess by remember { mutableStateOf(NowPlayingController.hasAccess(context)) }
    var volumePercent by remember { mutableStateOf(VolumeController.current(context)) }
    var transportWorking by remember { mutableStateOf(false) }
    var transportMessage by remember { mutableStateOf<String?>(null) }

    // Same three booleans SpotifyScreen derives its own stage from - see MediaSpotifyGateResolver's
    // own doc for why this screen reuses SpotifyConnectResolver.Stage wholesale rather than a
    // second vocabulary.
    var hasClientId by remember { mutableStateOf(CompanionProfile.hasSpotifyClientId(context)) }
    var isAuthorized by remember { mutableStateOf(SpotifyWebApi.isAuthorized(context)) }
    var hasStaleGrant by remember { mutableStateOf(SpotifyWebApi.hasStaleGrant(context)) }
    val stage = SpotifyConnectResolver.stage(hasClientId, isAuthorized, hasStaleGrant)
    val searchReady = MediaSpotifyGateResolver.searchReady(stage)

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<MediaSearchResolver.Row?>(null) }
    var searchMessage by remember { mutableStateOf<String?>(null) }

    var browseSource by remember { mutableStateOf<BrowseSource?>(null) }
    var browseRows by remember { mutableStateOf(emptyList<BrowseRow>()) }
    var browseMessage by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }

    var queueRows by remember { mutableStateOf(emptyList<QueuedTrack>()) }
    var queueMessage by remember { mutableStateOf<String?>(null) }
    var queueLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { NowPlayingController.init(context) }

    // Re-read on resume, same shape as SpotifyScreen's own doc: this screen is left and returned
    // to (a browser hop for a Spotify approval could easily happen mid-visit) and the notification
    // grant can be flipped in Android's own Settings while this screen is backgrounded.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasNotificationAccess = NowPlayingController.hasAccess(context)
        volumePercent = VolumeController.current(context)
        hasClientId = CompanionProfile.hasSpotifyClientId(context)
        isAuthorized = SpotifyWebApi.isAuthorized(context)
        hasStaleGrant = SpotifyWebApi.hasStaleGrant(context)
    }

    fun runTransport(action: MediaTransport.Action) {
        transportWorking = true
        transportMessage = null
        scope.launch {
            val ok = MediaTransport.run(context, action)
            transportMessage = if (ok) null else MediaTransport.failureMessage(context)
            transportWorking = false
        }
    }

    /** Same play path play_music uses - [SpotifyController.playUri] awaits the real App Remote result. */
    fun playUri(uri: String, label: String, onOutcome: (String?) -> Unit) {
        scope.launch {
            val outcome = SpotifyController.playUri(context, uri, pickedLabel = label)
            if (SpotifyController.succeeded(outcome)) {
                NowPlayingController.markLegionInitiatedPlay()
            }
            onOutcome(SpotifyController.message(outcome, label))
        }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank()) {
            searchMessage = "What should I play?"
            return
        }
        searching = true
        searchResult = null
        searchMessage = null
        scope.launch {
            // Identical sequence to LiveToolbox's resolveSpotifyUri: a silent reconnect attempt,
            // then confirm the Web API grant, then search - see that function's own doc.
            if (!SpotifyController.ensureConnected(context)) {
                searchMessage = "Spotify isn't connected - connect your Spotify account in Setup, " +
                    "or pick something on your phone yourself and I'll control play/pause/skip from here."
                searching = false
                return@launch
            }
            if (!SpotifyWebApi.isAuthorized(context)) {
                searchMessage = if (SpotifyWebApi.hasStaleGrant(context)) {
                    "Spotify needs re-approving - open Setup, Spotify, and tap AUTHORIZE again."
                } else {
                    "Spotify isn't finished connecting - open Setup, tap CONNECT under the Spotify " +
                        "client ID, and approve it in the browser. Then you can search by name."
                }
                searching = false
                return@launch
            }
            when (val outcome = SpotifyWebApi.search(context, q, "track")) {
                is SpotifyWebApi.SearchOutcome.Found -> searchResult = MediaSearchResolver.rowFor(outcome)
                else -> searchMessage = MediaSearchResolver.failureMessage(outcome, q)
            }
            searching = false
        }
    }

    fun runBrowse(source: BrowseSource) {
        browseSource = source
        browseRows = emptyList()
        browseMessage = null
        browsing = true
        scope.launch {
            when (source) {
                BrowseSource.SAVED_ALBUMS -> {
                    val outcome = SpotifyWebApi.getSavedAlbums(context, BROWSE_LIMIT)
                    browseMessage = MediaLibraryResolver.message(outcome, "saved albums", SpotifyWebApi.hasStaleGrant(context))
                    if (outcome is SpotifyWebApi.LibraryOutcome.Found) {
                        browseRows = outcome.items.map { BrowseRow(it.name, it.artist, it.uri) }
                    }
                }
                BrowseSource.RECENTLY_PLAYED -> {
                    val outcome = SpotifyWebApi.getRecentlyPlayed(context, BROWSE_LIMIT)
                    browseMessage = MediaLibraryResolver.message(outcome, "recently played", SpotifyWebApi.hasStaleGrant(context))
                    if (outcome is SpotifyWebApi.LibraryOutcome.Found) {
                        // No uri on this endpoint's payload (Spotify's own history, not a play
                        // target) - display-only, same as get_music_queue's read-only shape.
                        browseRows = outcome.items.map { BrowseRow(it.name, it.artist, null) }
                    }
                }
                BrowseSource.TOP_TRACKS -> {
                    val outcome = SpotifyWebApi.getTopTracks(context, BROWSE_LIMIT)
                    browseMessage = MediaLibraryResolver.message(outcome, "top tracks", SpotifyWebApi.hasStaleGrant(context))
                    if (outcome is SpotifyWebApi.LibraryOutcome.Found) {
                        browseRows = outcome.items.map { BrowseRow(it.name, it.artist, null) }
                    }
                }
                BrowseSource.LEGION_HISTORY -> {
                    // Not a SpotifyWebApi.LibraryOutcome at all - LEGION's own observed-listening
                    // table, same source browse_my_music's legion_history reads. Empty gets its
                    // own honest line, matching browseLegionHistory's own wording.
                    val recent = CarDatabase.getDatabase(context).musicPlayHistoryDao().getRecent(BROWSE_LIMIT)
                    if (recent.isEmpty()) {
                        browseMessage = "LEGION hasn't observed anything playing yet on this device - " +
                            "this fills in as music is actually played while LEGION is running, it " +
                            "isn't Spotify's own history."
                    } else {
                        browseRows = recent.map { BrowseRow(it.title, it.artist, it.spotifyUri) }
                    }
                }
            }
            browsing = false
        }
    }

    fun runQueue() {
        queueLoading = true
        queueMessage = null
        scope.launch {
            val outcome = SpotifyWebApi.getQueue(context, QUEUE_LIMIT)
            queueMessage = MediaLibraryResolver.message(outcome, "the upcoming queue", SpotifyWebApi.hasStaleGrant(context))
            if (outcome is SpotifyWebApi.LibraryOutcome.Found) queueRows = outcome.items
            queueLoading = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Media", onBack = onBack)

            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                item { MediaTransportAccessBanner(hasAccess = hasNotificationAccess) }

                // ------------------------------------------------------------- now playing
                item {
                    SectionHeader(left = "Now playing")
                    val info = nowPlaying
                    if (info == null) {
                        Text(
                            "Nothing playing right now.",
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else {
                        ReadingRow(label = info.title, value = if (info.isPlaying) "PLAYING" else "PAUSED", sub = info.artist)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeckButton("PREV", enabled = !transportWorking, onClick = { runTransport(MediaTransport.Action.PREVIOUS) })
                        DeckButton(
                            if (nowPlaying?.isPlaying == true) "PAUSE" else "PLAY",
                            enabled = !transportWorking,
                            onClick = { runTransport(if (nowPlaying?.isPlaying == true) MediaTransport.Action.PAUSE else MediaTransport.Action.PLAY) },
                        )
                        DeckButton("NEXT", enabled = !transportWorking, onClick = { runTransport(MediaTransport.Action.NEXT) })
                    }
                    transportMessage?.let {
                        Text(it, style = LegionType.stamp, color = sem.estimated, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }

                // ------------------------------------------------------------------- volume
                item {
                    SectionHeader(left = "Volume", right = "$volumePercent%")
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeckButton("LOWER", onClick = { volumePercent = VolumeController.lower(context) })
                        DeckButton("RAISE", onClick = { volumePercent = VolumeController.raise(context) })
                        DeckButton("MUTE", onClick = { volumePercent = VolumeController.mute(context, true) })
                        DeckButton("UNMUTE", onClick = { volumePercent = VolumeController.mute(context, false) })
                    }
                }

                // -------------------------------------------------------------------- queue
                item {
                    SectionHeader(left = "Queue")
                    if (!searchReady) {
                        Text(
                            MediaSpotifyGateResolver.notReadyMessage(stage),
                            style = LegionType.stamp,
                            color = sem.estimated,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else {
                        DeckButton(
                            if (queueLoading) "LOADING..." else "WHAT'S NEXT",
                            enabled = !queueLoading,
                            onClick = ::runQueue,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        queueMessage?.let {
                            Text(it, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
                items(queueRows) { track -> ReadingRow(label = track.name, value = track.artist) }

                // ---------------------------------------------------------- search & play
                item {
                    SectionHeader(left = "Search & play")
                    if (!searchReady) {
                        Text(
                            MediaSpotifyGateResolver.notReadyMessage(stage),
                            style = LegionType.stamp,
                            color = sem.estimated,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else {
                        DeckTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = "Song or artist",
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        DeckButton(
                            if (searching) "SEARCHING..." else "SEARCH",
                            enabled = !searching,
                            onClick = ::runSearch,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        searchResult?.let { row ->
                            ReadingRow(
                                label = row.title,
                                value = "PLAY",
                                sub = row.subtitle,
                                modifier = Modifier.clickable {
                                    playUri(row.uri, row.title) { message -> searchMessage = message }
                                },
                            )
                        }
                        searchMessage?.let {
                            Text(it, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }

                // -------------------------------------------------------------------- library
                item {
                    SectionHeader(left = "Library")
                    if (!searchReady) {
                        Text(
                            MediaSpotifyGateResolver.notReadyMessage(stage),
                            style = LegionType.stamp,
                            color = sem.estimated,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BrowseSource.entries.forEach { source ->
                                DeckButton(
                                    text = source.label,
                                    enabled = !browsing,
                                    onClick = { runBrowse(source) },
                                )
                            }
                        }
                        browseMessage?.let {
                            Text(it, style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
                items(browseRows) { row ->
                    ReadingRow(
                        label = row.title,
                        value = if (row.uri != null) "PLAY" else "",
                        sub = row.subtitle,
                        modifier = if (row.uri != null) {
                            Modifier.clickable { playUri(row.uri, row.title) { message -> browseMessage = message } }
                        } else {
                            Modifier
                        },
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** browse_my_music's `source` vocabulary, the subset with a minimal on-screen surface (ticket 04's "minimal library browse"). */
internal enum class BrowseSource(val wireValue: String, val label: String) {
    SAVED_ALBUMS("saved_albums", "ALBUMS"),
    RECENTLY_PLAYED("recently_played", "RECENT"),
    TOP_TRACKS("top_tracks", "TOP"),
    LEGION_HISTORY("legion_history", "HISTORY"),
}

/** One browse-list row. [uri] is null when the source's own payload carries no play target (see runBrowse). */
internal data class BrowseRow(val title: String, val subtitle: String?, val uri: String?)

private const val BROWSE_LIMIT = 8
private const val QUEUE_LIMIT = 5
