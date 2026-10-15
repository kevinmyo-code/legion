package com.kevin.midnightai.media

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kevin.midnightai.MidnightEvents
import com.kevin.midnightai.ai.CompanionProfile
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.android.appremote.api.error.SpotifyRemoteServiceException
import com.spotify.android.appremote.api.error.UnsupportedFeatureVersionException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Empty
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Repeat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Optional power-user Spotify integration over the App Remote SDK (BYO client ID,
 * 2026-07-21 reopen of the CLAUDE.md sec 8 freeze - see memory/library/decisions.md).
 *
 * BYO shape: the driver registers THEIR OWN Spotify developer app and pastes only
 * its client ID (via Setup -> [CompanionProfile.saveSpotifyClientId]); the redirect
 * URI is app-fixed ([REDIRECT_URI]) and must be entered verbatim in the driver's
 * Spotify dashboard AND declared in the manifest. Nothing ships a shared Kevin
 * client ID, so there is no Development-Mode user cap to trip.
 *
 * App Remote auth is app-to-app: the connection handshake goes through the
 * installed Spotify app, not a browser redirect, so no Activity-result flow is
 * needed here - [connect] works from any context. It DOES require the Spotify app
 * to be installed and the user logged in with Premium; [onFailure] fires otherwise
 * and we stay on the phone-BT / mixtape path with zero regression.
 *
 * A connect flips [MusicSource] to SPOTIFY only when nothing else owns the speakers
 * ([shouldClaimSourceOnConnect]; was: on every connect, until 2026-10-15); an explicit switch
 * and a play that really started claim it too. A deliberate disconnect reverts to PHONE.
 * Everything is defensively guarded - a Spotify failure must never crash the launcher.
 *
 * App Remote is the ONLY playback and transport path on head-unit Spotify (Kevin, 2026-10-15).
 * The Web API stays for reads (search, playlists); it never plays, and nothing launches the
 * Spotify app.
 *
 * NOT YET WIRED: the Setup UI that captures the client ID and calls [connect], and
 * the manifest redirect scheme. This controller is the transport/lifecycle seam;
 * the trigger + UI are the next slice. Policy caveat: the BYO-own-dev-app pattern's
 * compliance with Spotify's developer terms is GRAY and under review
 * (.scratch/spotify-byo/); risk accepted by Kevin 2026-07-21.
 */
object SpotifyController {
    private const val TAG = "SpotifyController"

    /**
     * App-fixed OAuth redirect URI. Must match, byte for byte: (a) the
     * `<data android:scheme=.../>` in the manifest, and (b) the Redirect URI the
     * driver registers in their own Spotify dashboard. Not per-user - a manifest
     * intent-filter scheme is static, so the redirect cannot vary by driver even
     * though the client ID does.
     */
    const val REDIRECT_URI = "com.kevin.midnightai://spotify-callback"

    @Volatile private var remote: SpotifyAppRemote? = null

    // Shared in-flight handle for connect/connectSilently/ensureConnected.
    // Concurrent callers do NOT each fire their own SpotifyAppRemote.connect():
    // whichever caller arrives first creates this and starts the real connect;
    // every other caller during that ~1-2s window gets handed the SAME deferred
    // and shares its real result instead of racing a second connect attempt (two
    // callbacks landing out of order used to be able to silently stomp `remote`)
    // or reporting a false failure while the first attempt was still resolving
    // (a fast "false" here previously fell through to the misleading "turn on
    // Music app on this head unit" branch even though a connection was seconds
    // from succeeding - see the coordinator's fix note for the exact repro: an
    // ordinary open-launcher-then-talk sequence collides with connectSilently's
    // onResume window). Nulled out in BOTH callback branches (onConnected and
    // onFailure), and in the synchronous-throw path, via [finishAttempt] - which
    // takes the same monitor [startConnect] holds. Attaching to an ALREADY
    // COMPLETED deferred is harmless (await returns immediately with the real
    // result), so there is no reason to race the null-out ahead of the state it
    // describes; doing that only widens the window where a second caller sees
    // "nothing in flight" while a callback is still mid-execution and fires a
    // duplicate connect.
    @Volatile private var inFlight: CompletableDeferred<Boolean>? = null

    // The in-flight attempt's showAuthView flag, client ID and app context, so a failed silent
    // attempt can be retried WITH the consent sheet (see [finishAttempt]); the retry re-reads the
    // client ID off-thread and compares it to this one. Guarded by this object's monitor.
    private var inFlightShowsAuth = false
    private var inFlightClientId: String? = null
    private var inFlightContext: Context? = null

    // Handed to an explicit connect that joined a silent attempt. Guarded by this object's monitor.
    private var authRetry: CompletableDeferred<Boolean>? = null

    val isConnected: Boolean get() = remote?.isConnected == true

    // What the last connect attempt did, so Settings can recompute when playback connects or fails
    // without waiting for a resume. A silent drop (Spotify killed) never calls back, so readers
    // pair it with [isConnected] via [effectiveLink].
    private val _link = MutableStateFlow(AppRemoteLink.DISCONNECTED)
    val link: StateFlow<AppRemoteLink> = _link.asStateFlow()

    /** [link] corrected for a connection that dropped without a callback. */
    fun effectiveLink(): AppRemoteLink =
        if (_link.value == AppRemoteLink.CONNECTED && !isConnected) AppRemoteLink.DISCONNECTED else _link.value

    /**
     * What the most recent FAILED connect attempt actually threw, so a spoken failure can name
     * it (not installed / not signed in / not authorized / offline) instead of one generic line.
     * Cleared on the next successful connect so a stale reason never outlives its failure.
     */
    @Volatile private var lastConnectFailure: Throwable? = null

    /** Read-only view of [lastConnectFailure] for callers that speak or report the reason. */
    val lastFailure: Throwable? get() = lastConnectFailure

    // When [lastConnectFailure] was recorded (elapsedRealtime), so a play can skip re-trying an
    // App Remote connect that failed seconds ago and fail fast with the recorded reason.
    @Volatile private var lastFailureAtMs: Long = 0L

    /** Milliseconds since the last failed connect, or null when none is on record. */
    private fun msSinceLastFailure(): Long? =
        if (lastConnectFailure == null) null else android.os.SystemClock.elapsedRealtime() - lastFailureAtMs

    /**
     * The exact failure text for Settings: exception class plus Spotify's own message, plus a
     * plain-words hint where the failure is one we recognise. Null when nothing failed.
     */
    internal fun failureDetail(failure: Throwable?): String? {
        failure ?: return null
        val msg = failure.message?.replace('\n', ' ')?.trim()?.take(160)?.takeIf { it.isNotBlank() }
        val base = failure.javaClass.simpleName + (msg?.let { ": $it" } ?: "")
        val m = msg.orEmpty().lowercase()
        val hint = when {
            failure is CouldNotFindSpotifyApp ->
                "no App Remote-capable Spotify app (needs Play-signed com.spotify.music)"
            m.contains("invalid_client") || m.contains("redirect") ->
                "Spotify rejected the client ID, redirect URI or SHA-1 for this build"
            m.contains("authentication_service_unavailable") ->
                "Spotify's auth service unreachable from the Spotify app"
            failure is UserNotAuthorizedException ->
                "Spotify app's own consent not given - it shows in the Spotify app, not the browser"
            failure is TimeoutException ->
                "the Spotify app never answered - open Spotify once, then CONNECT"
            failure is UnsupportedFeatureVersionException ->
                "Spotify app too old for App Remote - update it"
            failure is SpotifyConnectionTerminatedException || failure is SpotifyDisconnectedException ->
                "Spotify app closed the connection (killed or restarted)"
            failure is SpotifyRemoteServiceException ->
                "could not bind the Spotify app's service"
            else -> null
        }
        return if (hint == null) base else "$base ($hint)"
    }

    /** Whether our activity is in front; the play path uses it to notice Spotify's UI stealing the screen. */
    val activityInFront: Boolean get() = activityResumed

    /** The last connect failed because the Spotify app is not installed on this unit. */
    fun lastFailureIsNotInstalled(): Boolean = outcomeForConnectFailure(lastConnectFailure) == PlayOutcome.NotInstalled

    // App Remote's player-state stream: pushed by Spotify the instant its player changes, no
    // polling, no quota. The voice actions that act on "the current track/artist" (like, follow,
    // seek, more-from-artist, artist_albums) read it. NowPlayingController is deliberately NOT
    // wired to it here - Cruise's now-playing display is unchanged by the voice-tool port.
    @Volatile private var playerStateSubscription: Subscription<PlayerState>? = null
    private val _playerState = MutableStateFlow<PlayerState?>(null)
    val playerState: StateFlow<PlayerState?> = _playerState.asStateFlow()

    /** (Re)subscribes to App Remote's player-state stream on a freshly connected [r]. */
    private fun subscribePlayerState(r: SpotifyAppRemote) {
        playerStateSubscription?.cancel()
        // Two statements, not a chain: setEventCallback returns Subscription<T> but
        // setErrorCallback (declared on the shared PendingResultBase) returns the WIDER
        // PendingResult<T>, so chaining would stop this being assignable as a Subscription.
        val subscription = r.playerApi.subscribeToPlayerState()
            .setEventCallback { state -> _playerState.value = state }
        subscription.setErrorCallback { e -> Log.w(TAG, "player state subscription error: ${e.message}") }
        playerStateSubscription = subscription
    }

    /**
     * What Spotify is playing FROM (a playlist, album, artist radio...), pushed by App Remote's
     * player-context stream. Cruise's pinned grid marks the tile whose URI matches, so a playlist
     * started by voice, from the Spotify app, or by autoplay is marked correctly too. Strings
     * only; null while disconnected or before the first push.
     */
    data class PlayingContext(val uri: String, val title: String)

    @Volatile private var playerContextSubscription: Subscription<PlayerContext>? = null
    private val _playingContext = MutableStateFlow<PlayingContext?>(null)
    val playingContext: StateFlow<PlayingContext?> = _playingContext.asStateFlow()

    /** (Re)subscribes to App Remote's player-context stream on a freshly connected [r]. */
    private fun subscribePlayerContext(r: SpotifyAppRemote) {
        playerContextSubscription?.cancel()
        val subscription = r.playerApi.subscribeToPlayerContext()
            .setEventCallback { c ->
                _playingContext.value = c?.uri?.takeIf { it.isNotBlank() }?.let { PlayingContext(it, c.title.orEmpty()) }
            }
        subscription.setErrorCallback { e -> Log.w(TAG, "player context subscription error: ${e.message}") }
        playerContextSubscription = subscription
    }

    private fun dropPlayerContext() {
        runCatching { playerContextSubscription?.cancel() }
        playerContextSubscription = null
        _playingContext.value = null
    }

    /**
     * Whether [uri] (a track) is in the driver's Liked Songs, read from App Remote. Null when
     * not connected or the read fails/times out - the caller shows "unknown" as not liked.
     * Never connects: a heart on the screen must not start a connect attempt.
     */
    suspend fun isSaved(uri: String): Boolean? = withContext(Dispatchers.IO) {
        val r = remote ?: return@withContext null
        try {
            r.userApi.getLibraryState(uri).await(CAPABILITIES_TIMEOUT_SEC, TimeUnit.SECONDS)
                .takeIf { it.isSuccessful }?.data?.isAdded
        } catch (e: Exception) {
            Log.w(TAG, "getLibraryState($uri) threw: ${e.message}")
            null
        }
    }

    /** The (uri, name) of the artist behind whatever is playing. */
    data class CurrentArtist(val uri: String, val name: String)

    /**
     * Pure `PlayerState? -> CurrentArtist?` decision behind [currentArtist]. Null whenever there
     * is nothing genuinely to answer with (no state, or a blank uri/name): "more from this artist"
     * must fail honestly on null rather than invent an artist from a stale guess.
     */
    internal fun currentArtistFrom(state: PlayerState?): CurrentArtist? {
        val artist = state?.track?.artist ?: return null
        if (artist.uri.isNullOrBlank() || artist.name.isNullOrBlank()) return null
        return CurrentArtist(uri = artist.uri, name = artist.name)
    }

    /** The artist behind whatever App Remote's pushed [playerState] currently holds. */
    fun currentArtist(): CurrentArtist? = currentArtistFrom(playerState.value)

    /**
     * Starts (or joins) the one connect attempt in flight. Every public entry
     * point below funnels through here so there is exactly one
     * `SpotifyAppRemote.connect()` call active at a time, regardless of how many
     * callers ask concurrently. The FIRST caller's [showAuthView] choice wins for
     * that attempt. One exception: an explicit (showAuthView=true) request that
     * lands on a SILENT attempt does not just inherit its answer. It gets its own
     * deferred ([authRetry]); if the silent attempt connects, that completes true,
     * and if it fails for want of consent while our activity is in front
     * ([shouldRetryWithConsent]), [finishAttempt] starts a fresh attempt WITH the
     * consent sheet into it (any other failure settles it false). Without this, onResume's silent attempt (fired as the driver
     * returns from the browser consent) swallowed the redirect's explicit connect,
     * so App Remote's own consent was never requested. A silent request never
     * downgrades an explicit attempt; it only waits for the answer.
     *
     * [CompletableDeferred] (no parent [kotlinx.coroutines.Job]) rather than
     * `suspendCancellableCoroutine`: a `Deferred` with no parent has no
     * parent-child relationship to any caller's coroutine, so a caller that gets
     * cancelled while awaiting only cancels ITS OWN suspension point - it cannot
     * cancel this shared deferred out from under a different waiter (verified by
     * reading `CompletableDeferredImpl`/`JobSupport` in the resolved
     * kotlinx-coroutines-core source: `initParentJob(parent)` with `parent =
     * null` attaches nothing, so nothing but an explicit `cancel()`/`complete()`
     * call on this object - which only the two callbacks below ever make - can
     * resolve it).
     */
    // @Synchronized because the read-then-assign of [inFlight] below is a
    // check-then-act, not an atomic compare-and-set: @Volatile alone guarantees
    // visibility, not mutual exclusion, so two callers arriving within a few
    // nanoseconds could both see null and both start an SDK connect - the exact
    // duplicate this function exists to prevent. That is not hypothetical here:
    // MainActivity.onResume calls connectSilently on the main thread while
    // ensureConnected is called from the voice tool-dispatch coroutine on
    // another, so the two entry points genuinely race across threads. The lock
    // is uncontended in every normal case and this is called at most a few times
    // a session, so there is nothing to optimise away.
    @Synchronized
    private fun startConnect(
        context: Context,
        showAuthView: Boolean,
        knownClientId: String? = null,
        into: CompletableDeferred<Boolean>? = null,
    ): CompletableDeferred<Boolean> {
        inFlight?.let { current ->
            if (!showAuthView || inFlightShowsAuth) return current
            // Explicit request joining a silent attempt: retry with the consent sheet if it fails.
            authRetry?.let { return it }
            val retry = CompletableDeferred<Boolean>()
            authRetry = retry
            return retry
        }

        val deferred = into ?: CompletableDeferred()
        inFlight = deferred
        inFlightShowsAuth = showAuthView

        val clientId = knownClientId ?: CompanionProfile.spotifyClientId(context)
        if (clientId.isBlank()) {
            inFlight = null
            inFlightShowsAuth = false
            MidnightEvents.spotifyClientIdSet(false)
            deferred.complete(false)
            return deferred
        }
        MidnightEvents.spotifyClientIdSet(true)
        inFlightClientId = clientId
        inFlightContext = context.applicationContext
        lastAppContext = context.applicationContext
        _link.value = AppRemoteLink.CONNECTING

        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(showAuthView)
            .build()

        // try/catch because a throw here reaches NEITHER callback. Without it the
        // deferred is never completed and `inFlight` is never cleared, so every
        // caller awaiting it hangs forever AND every future connect attempt for
        // the rest of the process is handed the same dead deferred - Spotify
        // silently gone for the session with nothing logged. Not exotic on a
        // cheap head unit: a stale or partially-installed Spotify package is
        // exactly the sort of thing that makes an SDK entry point throw rather
        // than report through its listener.
        //
        // The SDK call itself runs on the MAIN looper (F1, 2026-10-15): its ConnectionTask creates a
        // Handler on the calling thread, so from an IO thread it threw at once and no reconnect off
        // the main thread ever worked. The try/catch moved inside the hop with it; [finishAttempt]
        // is synchronized and re-entrant, so settling the attempt from main is safe.
        val appContext = context.applicationContext
        MainThreadHop.onMain {
            try {
                SpotifyAppRemote.connect(
                    appContext,
                    params,
                    object : Connector.ConnectionListener {
                        override fun onConnected(appRemote: SpotifyAppRemote) {
                            finishAttempt(deferred, appRemote)
                        }

                        override fun onFailure(error: Throwable) {
                            Log.w(TAG, "App Remote connect failed: ${error.message}")
                            finishAttempt(deferred, null, error)
                        }
                    },
                )
            } catch (e: Throwable) {
                Log.w(TAG, "App Remote connect threw synchronously: ${e.message}")
                finishAttempt(deferred, null, e)
            }
        }
        // An explicit attempt waits on the driver answering Spotify's consent sheet, which they
        // may simply never do. Bound it so [inFlight] cannot stick and wedge every later connect.
        // A silent attempt is bounded too: a Spotify app that never answers the bind (not
        // running, background start blocked) left it pending forever, and every later CONNECT
        // and tile tap joined that dead attempt with nothing ever reported.
        if (!deferred.isCompleted) {
            val bound = if (showAuthView) EXPLICIT_ATTEMPT_TIMEOUT_MS else SILENT_ATTEMPT_TIMEOUT_MS
            background.launch {
                delay(bound)
                expireAttempt(deferred, bound)
            }
        }
        return deferred
    }

    /** Fails [deferred] if it is still the attempt in flight; see [EXPLICIT_ATTEMPT_TIMEOUT_MS]. */
    @Synchronized
    private fun expireAttempt(deferred: CompletableDeferred<Boolean>, boundMs: Long) {
        if (inFlight !== deferred || deferred.isCompleted) return
        Log.w(TAG, "App Remote attempt timed out")
        finishAttempt(deferred, null, TimeoutException("no answer from the Spotify app in ${boundMs / 1000}s"))
    }

    /**
     * Settles one connect attempt and releases the in-flight slot, holding the
     * same monitor [startConnect] uses.
     *
     * The lock matters: the SDK callbacks run on their own thread, so clearing
     * [inFlight] outside it could let a concurrent [startConnect] observe "no
     * attempt in flight" while this one is still mid-execution and start a
     * duplicate connect - the exact duplication the in-flight slot exists to
     * prevent. [CompletableDeferred.complete] is a no-op when already completed,
     * which also keeps this safe if the SDK ever invokes both callbacks.
     */
    @Synchronized
    private fun finishAttempt(
        deferred: CompletableDeferred<Boolean>,
        connected: SpotifyAppRemote?,
        failure: Throwable? = null,
    ) {
        if (inFlight !== deferred && deferred.isCompleted) {
            // A callback for an attempt already settled (timed out by [expireAttempt]). Adopt a
            // late success - Spotify did connect - but never touch the slot or a newer attempt.
            if (connected != null && !isConnected) {
                adopt(connected)
                liveAttempt = deferred
                _link.value = AppRemoteLink.CONNECTED
                MidnightEvents.spotifyConnectFinished(true, true, null)
                return
            }
            // App Remote reports a LIVE connection dropping (Spotify killed, restarted, closed
            // the link) through the same listener's onFailure, long after the attempt settled.
            // This used to be ignored: `_link` said CONNECTED over a dead link, nothing was
            // logged, and nothing reconnected until the next onResume.
            if (connected == null && deferred === liveAttempt) onConnectionLost(failure)
            return
        }
        if (connected != null) {
            adopt(connected)
            liveAttempt = deferred
        } else {
            remote = null
            lastConnectFailure = failure
            lastFailureAtMs = android.os.SystemClock.elapsedRealtime()
            runCatching { playerStateSubscription?.cancel() }
            playerStateSubscription = null
            _playerState.value = null
            dropPlayerContext()
        }
        MidnightEvents.spotifyConnectFinished(connected != null, inFlightShowsAuth, failure)
        val retry = authRetry
        val retryClientId = inFlightClientId
        val retryContext = inFlightContext
        authRetry = null
        inFlight = null
        inFlightShowsAuth = false
        inFlightClientId = null
        inFlightContext = null
        _link.value = if (connected != null) AppRemoteLink.CONNECTED else AppRemoteLink.DISCONNECTED
        deferred.complete(connected != null)

        if (retry == null) return
        if (connected != null || retryClientId == null || retryContext == null ||
            !shouldRetryWithConsent(failure, activityResumed)
        ) {
            retry.complete(connected != null)
            return
        }
        // The silent attempt failed for want of consent and the driver explicitly asked to
        // connect: try again with Spotify's consent sheet. Off this thread and the monitor, because
        // the client ID is re-read through the Keystore - a CLEAR (or a different ID saved) while
        // the silent attempt ran cancels the retry instead of popping consent for a stale ID.
        background.launch {
            val idNow = runCatching { CompanionProfile.spotifyClientId(retryContext) }.getOrDefault("")
            if (idNow.isBlank() || idNow != retryClientId) {
                retry.complete(false)
                return@launch
            }
            Log.i(TAG, "silent attempt not authorized; retrying with the consent sheet")
            val d = startConnect(retryContext, showAuthView = true, knownClientId = idNow, into = retry)
            // Another attempt slipped in first: settle [retry] with ITS answer.
            if (d !== retry) retry.complete(runCatching { d.await() }.getOrDefault(false))
        }
    }

    /**
     * Pure: whether a failed silent attempt should be retried with the consent sheet. Only a
     * missing grant ([UserNotAuthorizedException]) is fixed by consent - re-prompting for a
     * missing app, a logged-out app or offline mode only pops a sheet that cannot help - and only
     * while our activity is in front, since the sheet is shown over whatever is.
     */
    internal fun shouldRetryWithConsent(failure: Throwable?, resumed: Boolean): Boolean =
        resumed && outcomeForConnectFailure(failure) == PlayOutcome.NotAuthorized

    // The attempt whose connection is live now, so its listener's later onFailure (the link
    // dropping) is recognised as a loss rather than a stale callback. Guarded by this monitor.
    private var liveAttempt: CompletableDeferred<Boolean>? = null

    // App context from the last connect, for the reconnect after a drop.
    @Volatile private var lastAppContext: Context? = null

    // One pending silent reconnect after a drop at a time.
    @Volatile private var reconnectPending = false

    /** A live App Remote link dropped on its own. Caller holds the monitor. */
    private fun onConnectionLost(failure: Throwable?) {
        liveAttempt = null
        remote = null
        lastConnectFailure = failure
        lastFailureAtMs = android.os.SystemClock.elapsedRealtime()
        runCatching { playerStateSubscription?.cancel() }
        playerStateSubscription = null
        _playerState.value = null
        dropPlayerContext()
        _link.value = AppRemoteLink.DISCONNECTED
        val voiceBusy = com.kevin.midnightai.service.ConversationState.isBusy
        Log.w(TAG, "App Remote link lost (voice busy=$voiceBusy): ${failure?.message}")
        MidnightEvents.spotifyAppRemoteLost(failure, voiceBusy)
        // MusicSource is left alone. Until 2026-10-15 transport then fell through to the Web API and
        // a poll kept the tile marked; both are gone, so with the link down transport fails fast
        // and says so (MusicRouter) while the reconnect below tries to bring the link back.
        val ctx = lastAppContext ?: return
        scheduleSilentReconnect(ctx)
    }

    /**
     * One silent (service-bind, never the consent sheet, never an activity) reconnect after a
     * drop, deferred past any running voice turn so nothing Spotify-side happens mid-turn.
     */
    private fun scheduleSilentReconnect(ctx: Context) {
        if (reconnectPending) return
        reconnectPending = true
        background.launch {
            try {
                // Several attempts, not one (2026-10-15 car report): a single try 5s after the drop
                // fails if Spotify is still restarting, and then NOTHING retried until the next
                // onResume, so the PHONE | SPOTIFY switch and Spotify voice stayed dead. Each
                // attempt below is the same silent, UI-less one; a connect that lands stops the loop.
                for ((attempt, wait) in SpotifyConnectLogic.RECONNECT_SCHEDULE_MS.withIndex()) {
                    delay(wait)
                    val deadline = android.os.SystemClock.elapsedRealtime() + RECONNECT_VOICE_WAIT_MS
                    while (com.kevin.midnightai.service.ConversationState.isBusy &&
                        android.os.SystemClock.elapsedRealtime() < deadline
                    ) {
                        delay(RECONNECT_POLL_MS)
                    }
                    when {
                        isConnected -> {
                            MidnightEvents.spotifyReconnect(false, "already connected")
                            return@launch
                        }
                        com.kevin.midnightai.service.ConversationState.isBusy -> {
                            MidnightEvents.spotifyReconnect(false, "voice turn still running; onResume retries")
                            return@launch
                        }
                        else -> {
                            MidnightEvents.spotifyReconnect(true, "after drop, attempt ${attempt + 1}")
                            connectSilently(ctx)
                        }
                    }
                }
            } finally {
                reconnectPending = false
            }
        }
    }

    // Set by MainActivity.onResume/onPause; gates the consent retry above.
    @Volatile private var activityResumed = false

    fun setActivityResumed(resumed: Boolean) {
        activityResumed = resumed
    }

    /**
     * Pure: bring Midnight back only if it WAS in front before the play and is not now. A launcher
     * that was already behind another app (Maps, a call) is not dragged forward by a voice play.
     */
    internal fun shouldReturnToFront(wasInFront: Boolean, nowInFront: Boolean): Boolean = wasInFront && !nowInFront

    /**
     * After a voice or tile play that really started: if Spotify's UI came to the foreground
     * (Midnight was in front before, is not now), bring Midnight's activity back. 2026-10-15 cold
     * start report: the song played but Spotify stayed in front and the driver had to tap HOME.
     * App Remote's connect can start the Spotify app, and nothing here launches it on purpose; this
     * only undoes the side effect. Spotify's own activity start is asynchronous and can land after
     * our first reorder, so it checks once more; each act is a breadcrumb with whether Midnight is
     * really in front afterwards. Fire-and-forget.
     */
    fun returnToFrontIfStolen(context: Context, midnightWasInFront: Boolean) {
        if (!midnightWasInFront) return
        val app = context.applicationContext
        background.launch {
            for (attempt in 1..RETURN_ATTEMPTS) {
                delay(RETURN_CHECK_MS)
                if (!shouldReturnToFront(midnightWasInFront, activityResumed)) return@launch
                withContext(Dispatchers.Main) {
                    runCatching {
                        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                            it.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
                            )
                            app.startActivity(it)
                        }
                    }.onFailure { Log.w(TAG, "could not return to Midnight: ${it.message}") }
                }
                delay(RETURN_CHECK_MS)
                MidnightEvents.midnightReturn("app_remote_play", attempt, activityResumed)
                if (activityResumed) return@launch
            }
        }
    }

    private const val RETURN_ATTEMPTS = 2
    private const val RETURN_CHECK_MS = 1_500L

    // Retry and timeout work, off the SDK callback thread. Its own handler: nothing here may take
    // the process down.
    private val background = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.w(TAG, "background connect work failed: ${e.message}") },
    )

    /** Bound on an explicit (consent-sheet) attempt; see [startConnect]. */
    private const val EXPLICIT_ATTEMPT_TIMEOUT_MS = 45_000L

    /** Bound on a silent attempt: no sheet to wait on, so a bind that has not answered by now never will. */
    private const val SILENT_ATTEMPT_TIMEOUT_MS = 20_000L

    // After a drop the silent reconnect waits per SpotifyConnectLogic.RECONNECT_SCHEDULE_MS (this
    // used to be a single RECONNECT_DELAY_MS = 5s: one try, then nothing until onResume).

    /** How long the reconnect waits out a running voice turn before giving up to onResume. */
    private const val RECONNECT_VOICE_WAIT_MS = 120_000L
    private const val RECONNECT_POLL_MS = 2_000L

    /**
     * Pure (F9, 2026-10-15): whether a fresh App Remote connection should take [MusicSource].
     * Only when the PHONE is the recorded source and is not actually playing. Before, ANY connect
     * (onResume, a background reconnect after a drop) flipped the source to SPOTIFY, so a phone
     * streaming over Bluetooth had its transport row silently re-pointed at Spotify. A mixtape
     * flips the source itself; an explicit switch ([MusicRouter.switchToSpotify]) and a play that
     * really started both claim it on their own.
     */
    internal fun shouldClaimSourceOnConnect(current: Source, phonePlaying: Boolean): Boolean =
        current == Source.PHONE && !phonePlaying

    /**
     * Applies [shouldClaimSourceOnConnect] off the connect callback: reading the phone's media
     * session is a binder call that must not run inside the connect monitor.
     */
    private fun claimSourceIfIdle() {
        val ctx = lastAppContext ?: return
        background.launch {
            // Unknown counts as playing: a session read that throws must leave the source alone, not
            // claim it from a phone that could not be inspected (the original F9 bug).
            val phonePlaying = runCatching { MusicController.phoneSessionPlaying(ctx) }.getOrDefault(true)
            if (shouldClaimSourceOnConnect(MusicSource.current.value, phonePlaying)) MusicSource.set(Source.SPOTIFY)
        }
    }

    /** Installs a freshly connected [connected] as the live connection. Caller holds the monitor. */
    private fun adopt(connected: SpotifyAppRemote) {
        remote = connected
        claimSourceIfIdle()
        Log.i(TAG, "App Remote connected")
        lastConnectFailure = null
        // A fresh connection gets a fresh state: the previous one belongs to a dead session
        // and must not answer "what is playing" for the new one. Voice actions that read it
        // fall back to an explicit getPlayerState until the first push lands.
        _playerState.value = null
        // subscribePlayerState can throw (a wedged SDK); a thrown exception here would skip
        // the deferred.complete in finishAttempt and hang every awaiting caller, so it is contained.
        try {
            subscribePlayerState(connected)
        } catch (e: Throwable) {
            Log.w(TAG, "player state subscribe threw: ${e.message}")
        }
        _playingContext.value = null
        try {
            subscribePlayerContext(connected)
        } catch (e: Throwable) {
            Log.w(TAG, "player context subscribe threw: ${e.message}")
        }
    }

    /**
     * Connects App Remote using the driver's BYO client ID, popping Spotify's
     * consent sheet if a grant doesn't already exist. Only call this from a
     * context where the driver just explicitly asked to connect (the Setup
     * screen's CONNECT button, or the OAuth redirect landing) - anywhere else,
     * use [connectSilently]. Fire-and-forget: joins whatever attempt is already
     * in flight rather than starting a second one, but does not wait on it. On
     * success flips [MusicSource] to SPOTIFY; on failure leaves the source
     * untouched (stays PHONE/MIXTAPE).
     */
    fun connect(context: Context) {
        if (isConnected) return
        startConnect(context, showAuthView = true)
    }

    /**
     * [connect], awaited: joins any in-flight attempt (retrying with the consent sheet if that
     * was a silent one and it failed) and returns whether App Remote actually connected. For the
     * OAuth redirect landing, which must report the playback result rather than assume it. The
     * client ID is read off the main thread; the SDK connect itself is posted to the main looper (F1, 2026-10-15; it ran on the caller's thread before).
     */
    suspend fun connectWithConsent(context: Context): Boolean {
        if (isConnected) return true
        val clientId = withContext(Dispatchers.IO) { CompanionProfile.spotifyClientId(context) }
        if (clientId.isBlank()) return false
        return startConnect(context, showAuthView = true, knownClientId = clientId).await()
    }

    /**
     * Silent re-attach for a grant the driver already gave - `showAuthView(false)`,
     * fire-and-forget, no consent sheet. This is the ONLY variant [MainActivity]'s
     * `onResume` may call: onResume fires on essentially every foreground return
     * (back from nav, back from a call, screen unlock, any full-screen app
     * exit), and popping Spotify's auth sheet unprompted on one of those would be
     * exactly the failure mode [ensureConnected]'s doc warns about, just on a
     * different call site. Joins an in-flight attempt rather than starting a
     * second one; does not wait on it.
     */
    fun connectSilently(context: Context) {
        if (isConnected) return
        startConnect(context, showAuthView = false)
    }

    /**
     * Connects if needed and waits for the REAL result, so a caller can act on
     * the answer instead of firing [connect] and immediately reading a
     * still-false [isConnected].
     *
     * Why this exists: App Remote drops whenever the Spotify app is killed or
     * backgrounded long enough, and nothing reconnected it. [connect] only ran
     * from the OAuth redirect and the Setup screen, so after the first drop the
     * connection stayed dead for the rest of the process. The driver saw
     * "turn on Music app on this head unit" - a message about a completely
     * unrelated toggle - because that is the branch a false [isConnected] falls
     * into.
     *
     * If a connect from `onResume` (or anywhere else) is already racing
     * `SpotifyAppRemote` when this is called, this does NOT bail out with a
     * fast, wrong "false" - it JOINS that attempt via [startConnect] and awaits
     * its actual outcome, so a voice command landing mid-reconnect gets a
     * correct answer instead of a misleading one after one wasted round trip.
     *
     * `showAuthView(false)` when this is the one starting the attempt: this is a
     * silent re-attach on a grant the driver already gave. Popping Spotify's auth
     * sheet unprompted mid-drive would be worse than failing.
     */
    suspend fun ensureConnected(context: Context): Boolean {
        if (isConnected) return true
        val clientId = withContext(Dispatchers.IO) { CompanionProfile.spotifyClientId(context) }
        if (clientId.isBlank()) return false
        return startConnect(context, showAuthView = false, knownClientId = clientId).await()
    }

    /**
     * How a bounded connect attempt for a voice action, a tile or a transport command ended. Every
     * case maps to a sentence that says what did NOT happen; none is a quiet false.
     */
    sealed interface Gate {
        data object Connected : Gate

        /** No client ID saved. */
        data object NotSetUp : Gate

        /** A client ID is saved but unreadable (Keystore decrypt failed). */
        data object Unreadable : Gate

        /** The budget ran out with an attempt still running. */
        data object TimedOut : Gate

        /** The attempt failed, or was skipped because one failed seconds ago ([failure] is that reason). */
        data class Failed(val failure: Throwable?) : Gate
    }

    /**
     * Connects if needed within [budgetMs] and says how it ended. Replaces the unbounded
     * `ensureConnected` on every Spotify-only voice action (F5: a silent attempt can run 20 s, over
     * the 10 s tool cap) and gates on the same recent-failure decision a play uses (F6): inside the
     * [SpotifyConnectLogic.RECENT_FAILURE_MS] window it fails at once with the recorded reason
     * instead of spending the budget again.
     *
     * A failed or timed-out attempt kicks [scheduleSilentReconnect], so a spoken "reconnecting" is
     * true. [scheduleSilentReconnect] is idempotent while one is pending.
     */
    suspend fun connectGate(context: Context, budgetMs: Long = ACTION_CONNECT_BUDGET_MS): Gate {
        if (isConnected) return Gate.Connected
        val clientId = when (val read = withContext(Dispatchers.IO) { CompanionProfile.spotifyClientIdRead(context) }) {
            CompanionProfile.SecretRead.Absent -> return Gate.NotSetUp
            CompanionProfile.SecretRead.Unreadable -> return Gate.Unreadable
            is CompanionProfile.SecretRead.Value -> read.value
        }
        val app = context.applicationContext
        if (!SpotifyConnectLogic.shouldTryAppRemote(false, inFlight != null, msSinceLastFailure())) {
            scheduleSilentReconnect(app)
            return Gate.Failed(lastConnectFailure)
        }
        val ok = withTimeoutOrNull(budgetMs) {
            startConnect(context, showAuthView = false, knownClientId = clientId).await()
        }
        return when (ok) {
            true -> Gate.Connected
            false -> Gate.Failed(lastConnectFailure).also { scheduleSilentReconnect(app) }
            null -> Gate.TimedOut.also { scheduleSilentReconnect(app) }
        }
    }

    /** [connectGate] reduced to a yes/no for callers that speak [notConnectedMessage] on no. */
    suspend fun ensureConnectedBounded(context: Context, budgetMs: Long = ACTION_CONNECT_BUDGET_MS): Boolean =
        connectGate(context, budgetMs) == Gate.Connected

    /** The [PlayOutcome] a non-connected [gate] stands for. */
    internal fun outcomeForGate(gate: Gate): PlayOutcome = when (gate) {
        Gate.Connected -> PlayOutcome.ConnectFailed("lost connection")
        Gate.NotSetUp -> PlayOutcome.NotSetUp
        Gate.Unreadable -> PlayOutcome.ClientIdUnreadable
        Gate.TimedOut -> PlayOutcome.StillConnecting
        is Gate.Failed -> outcomeForConnectFailure(gate.failure)
    }

    /**
     * The sentence for a [gate] that did not connect, naming [what] did not happen ("pause", "skip").
     * Says "reconnecting" only where [connectGate] really started a reconnect (a failed or timed-out
     * attempt); not-set-up and unreadable setups say what to fix instead.
     */
    internal fun gateMessage(gate: Gate, what: String): String = when (gate) {
        Gate.Connected -> "Spotify on the head unit is connected, but I couldn't $what."
        Gate.NotSetUp -> "Spotify isn't set up on this head unit, so I couldn't $what. Enter the client ID in Settings, Music, Spotify."
        Gate.Unreadable -> clientIdUnreadableMessage()
        Gate.TimedOut -> "Spotify on the head unit is still connecting, so I couldn't $what. I'm retrying the connection; ask again in a few seconds."
        is Gate.Failed -> "Spotify on the head unit isn't connected (${playbackReasonShort(gate.failure)}), so I couldn't $what. " +
            "I'm trying to reconnect it."
    }

    /** Bound on the connect attempt behind a Spotify action; the tool cap is 10 s, so this leaves room to act. */
    internal const val ACTION_CONNECT_BUDGET_MS = 4_000L

    /** Tears down the connection and reverts routing to phone BT. Safe to call repeatedly. */
    fun disconnect() {
        // A pending consent retry belongs to the setup being torn down (CLEAR): cancel it.
        synchronized(this) {
            authRetry?.complete(false)
            authRetry = null
            // Torn down on purpose: its listener's onFailure is not a "lost" link.
            liveAttempt = null
        }
        runCatching { playerStateSubscription?.cancel() }
        playerStateSubscription = null
        _playerState.value = null
        dropPlayerContext()
        remote?.let { runCatching { SpotifyAppRemote.disconnect(it) } }
        remote = null
        _link.value = AppRemoteLink.DISCONNECTED
        MidnightEvents.spotifyDisconnected()
        if (MusicSource.current.value == Source.SPOTIFY) MusicSource.set(Source.PHONE)
    }

    // --- Transport ------------------------------------------------------------------------------
    //
    // Two shapes, one SDK path. The fire-and-forget [play]/[pause]/[next]/[previous] serve the
    // Cruise taps, which run on the main thread and cannot wait; a failure there is logged (and the
    // deck's next player-state push shows what really happened). The awaited [transport] serves the
    // voice path: it waits for the CallResult (F4, 2026-10-15) so "paused" is only said after
    // Spotify said so. Before, every transport call returned true the instant it was DISPATCHED.

    fun play(): Boolean = withPlayer("resume") { it.playerApi.resume() }

    fun pause(): Boolean = withPlayer("pause") { it.playerApi.pause() }

    fun next(): Boolean = withPlayer("skipNext") { it.playerApi.skipNext() }

    fun previous(): Boolean = withPlayer("skipPrevious") { it.playerApi.skipPrevious() }

    /** The four head-unit Spotify transport commands, each with the phrase used when one fails. */
    enum class TransportCmd(val verb: String) {
        PLAY("start playback"),
        PAUSE("pause"),
        NEXT("skip to the next track"),
        PREVIOUS("go back a track"),
    }

    /** How an awaited transport command ended. */
    sealed interface TransportOutcome {
        /** Spotify's CallResult came back successful. */
        data object Done : TransportOutcome

        /** App Remote was not connected (within the bounded wait); [gate] says why. */
        data class Unavailable(val gate: Gate) : TransportOutcome

        /** Connected, but the call came back unsuccessful or timed out. */
        data object Rejected : TransportOutcome
    }

    /**
     * Sends [cmd] and waits (bounded by [TRANSPORT_TIMEOUT_SEC]) for Spotify's own answer. The
     * connect step is bounded too ([connectGate]), so a dead link answers within the tool cap.
     */
    suspend fun transport(context: Context, cmd: TransportCmd): TransportOutcome = withContext(Dispatchers.IO) {
        val gate = connectGate(context)
        if (gate != Gate.Connected) return@withContext TransportOutcome.Unavailable(gate)
        val r = remote ?: return@withContext TransportOutcome.Unavailable(Gate.Failed(lastConnectFailure))
        val call = when (cmd) {
            TransportCmd.PLAY -> r.playerApi.resume()
            TransportCmd.PAUSE -> r.playerApi.pause()
            TransportCmd.NEXT -> r.playerApi.skipNext()
            TransportCmd.PREVIOUS -> r.playerApi.skipPrevious()
        }
        val ok = try {
            val result = call.await(TRANSPORT_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!result.isSuccessful) Log.w(TAG, "transport $cmd failed: ${result.errorMessage}")
            result.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "transport $cmd threw: ${e.message}")
            false
        }
        if (ok) TransportOutcome.Done else TransportOutcome.Rejected
    }

    /** The sentence for a transport that did not land; null for [TransportOutcome.Done]. */
    internal fun transportMessage(outcome: TransportOutcome, cmd: TransportCmd): String? = when (outcome) {
        TransportOutcome.Done -> null
        is TransportOutcome.Unavailable -> gateMessage(outcome.gate, cmd.verb)
        TransportOutcome.Rejected -> "Spotify on the head unit didn't confirm that, so I can't say it did " +
            "(${cmd.verb}). Try again."
    }

    /** Bound on an awaited transport call; short, since a skip is instant when Spotify is healthy. */
    private const val TRANSPORT_TIMEOUT_SEC = 3L

    /**
     * Starts playback of a Spotify URI **in place** - the Spotify app stays in the
     * background and Cruise keeps the screen. This is the half App Remote can do on
     * its own; finding the URI for a spoken name needs [SpotifyWebApi.search]
     * first, because App Remote has no search.
     *
     * Unlike the transport commands this **waits for the real result** (2026-07-29).
     * `playerApi.play()` returns a `CallResult<Empty>` (signature confirmed by javap
     * against the bundled App Remote aar) that the old code discarded, so this
     * returned true the instant the call was DISPATCHED. Every genuinely async
     * failure - region-locked track, no active playback device, Premium required,
     * a stale URI - reported success, and the caller then told the driver
     * "Playing <song>" over silence. Awaiting turns that into an answer the caller
     * can actually branch on.
     *
     * Blocking await, so it runs off the main thread; bounded because App Remote
     * can leave a call outstanding indefinitely when the Spotify app is wedged,
     * and a voice command must not hang the tool dispatch waiting on it.
     */
    suspend fun playUri(uri: String): Boolean = withContext(Dispatchers.IO) {
        val r = remote ?: return@withContext false
        try {
            val result = r.playerApi.play(uri).await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!result.isSuccessful) Log.w(TAG, "play($uri) failed: ${result.errorMessage}")
            result.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "play($uri) threw: ${e.message}")
            false
        }
    }

    // --- The voice play path: playUri with a spoken-outcome per way it can end ------------------

    /**
     * Every distinct way [playUriOutcome] can end, so a caller can both decide success/failure
     * ([succeeded]) and build the exact spoken line ([message]) without re-deriving either from a
     * boolean or a raw exception. The four SDK connect exceptions each get their own case (never
     * one generic string), plus the two outcomes that are ours: a connect that failed for an
     * unnamed reason, and a `play()` that reached Spotify but was refused (region lock, stale
     * URI, no active device).
     */
    sealed interface PlayOutcome {
        /**
         * Started for real - [playUriOutcome] awaited the actual `CallResult`, this is not
         * optimistic. [premiumWarning] is non-null only when the account reported it cannot play
         * on demand; [pickedLabel] is what search resolved to ("Discovery, Daft Punk"), null on
         * paths where the driver's own words already name exactly what plays.
         */
        data class Started(
            val premiumWarning: String? = null,
            val pickedLabel: String? = null,
        ) : PlayOutcome

        /** App Remote's connect attempt threw [CouldNotFindSpotifyApp]. */
        data object NotInstalled : PlayOutcome

        /** App Remote connected to Spotify, but nobody is signed into it. */
        data object NotLoggedIn : PlayOutcome

        /** Spotify is up and signed in, but refused this app/account ([UserNotAuthorizedException]). */
        data object NotAuthorized : PlayOutcome

        /** Spotify is in offline mode. */
        data object Offline : PlayOutcome

        /** Connect failed for a reason the SDK did not name (or no client ID is saved). */
        data class ConnectFailed(val detail: String?) : PlayOutcome

        /** Connected fine; the `play()` call itself came back unsuccessful or timed out. */
        data object PlayRejected : PlayOutcome

        /** The driver chose something newer before `play()` was called, so this one stood down. */
        data object Superseded : PlayOutcome

        /** A connect attempt was still running when the wait ran out; the link may yet come up. */
        data object StillConnecting : PlayOutcome

        /** No Spotify client ID saved: nothing was ever set up. */
        data object NotSetUp : PlayOutcome

        /** A client ID is saved but the Keystore would not decrypt it (F11): not the same as "never set up". */
        data object ClientIdUnreadable : PlayOutcome
    }

    /** Only [PlayOutcome.Started] represents sound actually starting. */
    internal fun succeeded(outcome: PlayOutcome): Boolean = outcome is PlayOutcome.Started

    /**
     * The pure outcome -> spoken-line mapping, free of [Context] and the SDK's async plumbing so
     * it is a plain JVM unit test. [description] is whatever the driver asked for in their own
     * words - every failure names it, so "nothing happened" always comes with a "to what".
     */
    internal fun message(outcome: PlayOutcome, description: String): String = when (outcome) {
        is PlayOutcome.Started ->
            "Playing \"${outcome.pickedLabel ?: description}\" on Spotify." +
                (outcome.premiumWarning?.let { " $it" } ?: "")
        PlayOutcome.NotInstalled ->
            "Spotify isn't installed on this head unit, so there's nothing to play \"$description\" through."
        PlayOutcome.NotLoggedIn ->
            "Spotify's installed but nobody's signed in there - log into Spotify and ask again."
        PlayOutcome.NotAuthorized ->
            "Spotify won't authorize this account for App Remote - check the allowlist for it in " +
                "the Spotify developer dashboard."
        PlayOutcome.Offline ->
            "Spotify's in offline mode right now, so it can't play \"$description\" - check the " +
                "connection and try again."
        is PlayOutcome.ConnectFailed ->
            "Spotify wouldn't connect" + (outcome.detail?.let { " ($it)" } ?: "") +
                " - I couldn't play \"$description\"."
        PlayOutcome.PlayRejected ->
            "Spotify wouldn't start \"$description\" - it may not be playable on this account here."
        PlayOutcome.Superseded ->
            "Didn't start \"$description\" - something else was picked since."
        PlayOutcome.StillConnecting ->
            "Spotify on the head unit is still connecting, so I couldn't play \"$description\" yet."
        PlayOutcome.NotSetUp ->
            "Spotify isn't set up on this head unit, so I couldn't play \"$description\". " +
                "Enter the client ID in Settings, Music, Spotify."
        PlayOutcome.ClientIdUnreadable -> clientIdUnreadableMessage()
    }

    /**
     * Maps the [Throwable] a failed connect attempt actually threw to a [PlayOutcome]. Pure and
     * `internal`: the four exception classes matched are plain `java.lang.Exception` subclasses
     * bundled in the App Remote aar, constructible on a plain JVM.
     */
    internal fun outcomeForConnectFailure(error: Throwable?): PlayOutcome = when (error) {
        is CouldNotFindSpotifyApp -> PlayOutcome.NotInstalled
        is NotLoggedInException -> PlayOutcome.NotLoggedIn
        is UserNotAuthorizedException -> PlayOutcome.NotAuthorized
        is OfflineModeException -> PlayOutcome.Offline
        else -> PlayOutcome.ConnectFailed(error?.javaClass?.simpleName)
    }

    /**
     * Short reason App Remote (playback) is not connected, for a toast or a Settings line.
     * [failure] is [lastFailure]; null maps to a generic "not connected".
     */
    internal fun playbackReasonShort(failure: Throwable?): String = when (val o = outcomeForConnectFailure(failure)) {
        PlayOutcome.NotInstalled -> "Spotify app not installed"
        PlayOutcome.NotLoggedIn -> "Spotify app signed out"
        PlayOutcome.NotAuthorized -> "not approved - tap CONNECT"
        PlayOutcome.Offline -> "Spotify offline"
        is PlayOutcome.ConnectFailed -> if (o.detail == null) "not connected" else "failed (${o.detail})"
        PlayOutcome.StillConnecting -> "still connecting"
        PlayOutcome.NotSetUp -> "no client ID saved"
        PlayOutcome.ClientIdUnreadable -> "saved setup unreadable - re-enter the client ID"
        is PlayOutcome.Started, PlayOutcome.PlayRejected, PlayOutcome.Superseded -> "not connected"
    }

    /**
     * The toast after the OAuth redirect, reporting BOTH halves: the Web API grant (search,
     * playlists) succeeded to get here, and App Remote (playback) is reported as it actually
     * ended. [playbackConnected] null means the attempt had not finished when we stopped waiting.
     */
    internal fun redirectToast(playbackConnected: Boolean?, failure: Throwable?): String = when (playbackConnected) {
        true -> "Spotify connected - search and playback"
        null -> "Spotify search connected; playback: still connecting"
        false -> "Spotify search connected; playback: ${playbackReasonShort(failure)}"
    }

    /**
     * What `play_music` says when the Spotify play did not settle inside its time budget. The play
     * keeps going in the background and its real ending is spoken when it lands (LatePlayReporter),
     * so this must neither claim it started nor claim it failed, and must not ask the driver to ask
     * again: a repeat ask of the same query joins the play in flight instead of restarting it.
     */
    internal fun playStillPendingMessage(description: String): String =
        "Still lining up \"$description\" on Spotify - it hasn't confirmed playback yet. " +
            "I'll tell you how it ends."

    /**
     * What `play_music` says when App Remote is NOT connected. Pure, so every branch is a JVM test.
     * There is no longer a fall-through to any other player (the play-from-search intent and the
     * Web API fallback were removed 2026-10-15), so this always returns a sentence.
     *
     * With a client ID saved the driver set Spotify up, so the specific reason App Remote failed is
     * the only useful answer - a generic "turn on a toggle" line about an unrelated setting is how
     * a fixable failure (consent never given, Spotify logged out) stayed invisible. Without a
     * client ID the driver is pointed at where to enter one.
     *
     * [failure] is [lastFailure] for the attempt that just failed. [connectTimedOut] means the
     * caller stopped waiting before the attempt finished, so [failure] is stale and must not be
     * spoken as the reason. [clientIdUnreadable] means the saved client ID could not be decrypted,
     * which is not the same as never having set it up (see [CompanionProfile.spotifyClientIdRead]).
     */
    internal fun playMusicUnavailableMessage(
        hasClientId: Boolean,
        failure: Throwable?,
        description: String,
        connectTimedOut: Boolean = false,
        clientIdUnreadable: Boolean = false,
    ): String {
        if (clientIdUnreadable) return clientIdUnreadableMessage()
        if (!hasClientId) {
            return "Spotify isn't set up on this head unit, so I can't play \"$description\". " +
                "Enter the client ID in Settings, Music, Spotify, or pick it on your phone and I'll " +
                "control playback."
        }
        if (connectTimedOut) {
            return "Spotify on the head unit is still connecting, so I couldn't play \"$description\" yet. " +
                "Give it a few seconds and ask again."
        }
        return when (val outcome = outcomeForConnectFailure(failure)) {
            PlayOutcome.NotInstalled ->
                "Spotify isn't installed on this head unit, so I can't play \"$description\" through it. " +
                    "Install Spotify there and sign in, then ask again."
            PlayOutcome.NotLoggedIn ->
                "Spotify on the head unit isn't signed in. Open Spotify there, log in, then ask again."
            PlayOutcome.NotAuthorized ->
                "Spotify hasn't approved playback control for this app yet. Tap CONNECT in " +
                    "Settings, Music, Spotify and accept Spotify's prompt, then ask again."
            PlayOutcome.Offline ->
                "Spotify on the head unit is in offline mode, so it can't play \"$description\". " +
                    "Check the connection and try again."
            is PlayOutcome.ConnectFailed ->
                "Spotify on the head unit wouldn't connect" + (outcome.detail?.let { " ($it)" } ?: "") +
                    ". Make sure Spotify is open and signed in there, then tap CONNECT in " +
                    "Settings, Music, Spotify."
            // outcomeForConnectFailure never yields these; listed so the when stays exhaustive.
            is PlayOutcome.Started, PlayOutcome.PlayRejected, PlayOutcome.Superseded, PlayOutcome.StillConnecting,
            PlayOutcome.NotSetUp, PlayOutcome.ClientIdUnreadable ->
                "Spotify on the head unit isn't connected, so I couldn't play \"$description\"."
        }
    }

    /** The saved client ID exists but the Keystore would not decrypt it: say that, not "never set up". */
    internal fun clientIdUnreadableMessage(): String =
        "I can't read the saved Spotify setup; re-enter the client ID in Settings, Music."

    /**
     * "Name, Artist" for what a search actually resolved to, or null when it carried no name. Both
     * `play_music` and the queue action speak THIS, never the driver's own query: echoing the
     * query is how a wrong song got confirmed as the right one.
     */
    internal fun pickedLabel(name: String?, subtitle: String?): String? =
        name?.takeIf { it.isNotBlank() }?.let { n -> subtitle?.takeIf { it.isNotBlank() }?.let { "$n, $it" } ?: n }

    /**
     * Plays [uri] in place and reports HOW it ended. The Boolean [playUri] stays for callers that
     * only need yes/no; the voice path wants the reason, spoken. Sequence:
     * [connectGate] -> [beforePlay] -> best-effort `connectSwitchToLocalDevice` -> `play(uri)`, awaited.
     *
     * App Remote is the ONLY path (2026-10-15, Kevin): there is no Web API fallback and no Spotify
     * app launch. When the link is down the play fails fast with the specific reason, never a fake
     * success. The one audible side effect before the play (quieting the outgoing phone or mixtape)
     * runs in [beforePlay], AFTER the link is confirmed, so a play that cannot even connect leaves
     * the driver's music alone (F8).
     *
     * The switch step is what makes "play X" work cold: `SpotifyAppRemote.connect()` creates an
     * active device but not necessarily the one this call should target - if a Connect speaker was
     * last playing it would stay the active device. Pulling playback onto THIS head unit first
     * keeps a voice play from quietly starting audio somewhere else. Best-effort: a failure is
     * logged and `play()` still goes ahead, since it can succeed when this was already active.
     * Not exercised on this device yet.
     *
     * Blocking awaits, so off the main thread; each bounded because App Remote can leave a call
     * outstanding indefinitely when the Spotify app is wedged, and a voice turn must not hang.
     *
     * [stillWanted] is asked right before `play()`: false means the driver chose something newer
     * while connect/switch ran ([PlayRequests]), and nothing is played.
     *
     * [shuffle] (the Cruise playlist tiles): shuffle is turned on before `play()` so a pinned
     * playlist does not start at track 1 every time. A shuffle failure is logged and play still
     * goes ahead - unshuffled music beats silence.
     */
    suspend fun playUriOutcome(
        context: Context,
        uri: String,
        pickedLabel: String? = null,
        stillWanted: () -> Boolean = { true },
        shuffle: Boolean = false,
        beforePlay: suspend () -> Unit = {},
    ): PlayOutcome =
        withContext(Dispatchers.IO) {
            val gate = connectGate(context, PLAY_CONNECT_WAIT_MS)
            if (gate != Gate.Connected) return@withContext outcomeForGate(gate)
            // Re-read, not captured from the gate: a disconnect could land between the two.
            val r = remote ?: return@withContext outcomeForGate(Gate.Failed(lastConnectFailure))

            if (!stillWanted()) return@withContext PlayOutcome.Superseded
            beforePlay()
            switchToLocalDeviceBestEffort(r)
            if (!stillWanted()) return@withContext PlayOutcome.Superseded
            if (shuffle) {
                try {
                    val shuffled = r.playerApi.setShuffle(true).await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
                    if (!shuffled.isSuccessful) Log.w(TAG, "setShuffle(true) failed: ${shuffled.errorMessage}")
                } catch (e: Exception) {
                    Log.w(TAG, "setShuffle(true) threw: ${e.message}")
                }
                if (!stillWanted()) return@withContext PlayOutcome.Superseded
            }

            val started = try {
                val result = r.playerApi.play(uri).await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (!result.isSuccessful) Log.w(TAG, "play($uri) failed: ${result.errorMessage}")
                result.isSuccessful
            } catch (e: Exception) {
                Log.w(TAG, "play($uri) threw: ${e.message}")
                false
            }
            if (!started) return@withContext PlayOutcome.PlayRejected

            PlayOutcome.Started(premiumWarning = premiumWarningIfNeeded(r), pickedLabel = pickedLabel)
        }

    /** How long a play waits on a connect attempt before saying Spotify is still connecting. */
    private const val PLAY_CONNECT_WAIT_MS = 4_000L

    private fun switchToLocalDeviceBestEffort(r: SpotifyAppRemote) {
        try {
            val result = r.connectApi.connectSwitchToLocalDevice().await(SWITCH_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!result.isSuccessful) Log.w(TAG, "connectSwitchToLocalDevice failed: ${result.errorMessage}")
        } catch (e: Exception) {
            Log.w(TAG, "connectSwitchToLocalDevice threw: ${e.message}")
        }
    }

    /**
     * Told, never gated on: `UserApi.getCapabilities().canPlayOnDemand` is the route left since
     * `user.product` was removed from the Web API for dev-mode apps. Best-effort - any failure or
     * timeout returns null (unknown) rather than failing a play that already succeeded.
     */
    private fun premiumWarningIfNeeded(r: SpotifyAppRemote): String? = try {
        val result = r.userApi.getCapabilities().await(CAPABILITIES_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (result.isSuccessful && result.data?.canPlayOnDemand == false) PREMIUM_WARNING else null
    } catch (e: Exception) {
        Log.w(TAG, "getCapabilities threw: ${e.message}")
        null
    }

    private const val PREMIUM_WARNING =
        "Heads up - this Spotify account doesn't look like it has Premium, so on-demand play may not work as expected."

    /** Bound on the best-effort switch-to-local-device call - shorter, since a failure there is not fatal. */
    private const val SWITCH_TIMEOUT_SEC = 5L

    /** Bound on best-effort reads (capabilities, library state, post-write state) so none delays a spoken result. */
    private const val CAPABILITIES_TIMEOUT_SEC = 3L

    /** The spoken line when a Spotify-only action cannot reach Spotify at all. */
    internal fun notConnectedMessage(): String =
        "Spotify on the head unit isn't connected, so that did not happen. If it is set up I am " +
            "reconnecting it, so ask again in a few seconds; otherwise set it up in Settings, Music, Spotify."

    // --- Queue ----------------------------------------------------------------------------------

    /**
     * Every distinct way [queueUri] can end - its OWN sealed type rather than a reuse of
     * [PlayOutcome]: "queued" and "playing" are different verbs to the driver even though they
     * share every connect-failure mode, and a shared type would tempt [message] into blurring it.
     */
    sealed interface QueueOutcome {
        /** `PlayerApi.queue(uri)` was awaited and came back successful. */
        data object Queued : QueueOutcome
        data object NotInstalled : QueueOutcome
        data object NotLoggedIn : QueueOutcome
        data object NotAuthorized : QueueOutcome
        data object Offline : QueueOutcome
        data class ConnectFailed(val detail: String?) : QueueOutcome

        /** Connected fine; the `queue()` call itself came back unsuccessful or timed out. */
        data object QueueRejected : QueueOutcome
    }

    internal fun succeeded(outcome: QueueOutcome): Boolean = outcome is QueueOutcome.Queued

    /**
     * **"Play X next" and "add X to the queue" are the same operation to Spotify - there is no
     * insert-at-position**, so [QueueOutcome.Queued]'s line says so rather than implying an
     * ordering the API does not offer.
     */
    internal fun message(outcome: QueueOutcome, description: String): String = when (outcome) {
        QueueOutcome.Queued -> "Queued \"$description\" to play next - Spotify only offers " +
            "next-up, not a specific position in the queue."
        QueueOutcome.NotInstalled ->
            "Spotify isn't installed on this head unit, so there's nothing to queue \"$description\" on."
        QueueOutcome.NotLoggedIn ->
            "Spotify's installed but nobody's signed in there - log into Spotify and ask again."
        QueueOutcome.NotAuthorized ->
            "Spotify won't authorize this account for App Remote - check the allowlist for it in " +
                "the Spotify developer dashboard."
        QueueOutcome.Offline ->
            "Spotify's in offline mode right now, so it can't queue \"$description\" - check the " +
                "connection and try again."
        is QueueOutcome.ConnectFailed ->
            "Spotify wouldn't connect" + (outcome.detail?.let { " ($it)" } ?: "") +
                " - I couldn't queue \"$description\"."
        QueueOutcome.QueueRejected ->
            "Spotify wouldn't queue \"$description\" - it may not be playable on this account here."
    }

    /** Same mapping as [outcomeForConnectFailure], into [QueueOutcome]. */
    internal fun queueOutcomeForConnectFailure(error: Throwable?): QueueOutcome = when (error) {
        is CouldNotFindSpotifyApp -> QueueOutcome.NotInstalled
        is NotLoggedInException -> QueueOutcome.NotLoggedIn
        is UserNotAuthorizedException -> QueueOutcome.NotAuthorized
        is OfflineModeException -> QueueOutcome.Offline
        else -> QueueOutcome.ConnectFailed(error?.javaClass?.simpleName)
    }

    /**
     * Adds [uri] to Spotify's own up-next queue via `PlayerApi.queue(uri)`, awaited so the outcome
     * reflects what landed. Unlike [playUriOutcome] this does NOT switch playback to the local
     * device: queuing needs only App Remote's connected session, which [ensureConnected] guarantees.
     */
    suspend fun queueUri(context: Context, uri: String): QueueOutcome = withContext(Dispatchers.IO) {
        if (!ensureConnectedBounded(context)) {
            return@withContext queueOutcomeForConnectFailure(lastConnectFailure)
        }
        val r = remote ?: return@withContext QueueOutcome.ConnectFailed("lost connection")

        val queued = try {
            val result = r.playerApi.queue(uri).await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!result.isSuccessful) Log.w(TAG, "queue($uri) failed: ${result.errorMessage}")
            result.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "queue($uri) threw: ${e.message}")
            false
        }
        if (queued) QueueOutcome.Queued else QueueOutcome.QueueRejected
    }

    // --- Library writes: like/unlike, follow/unfollow --------------------------------------------

    /** Which library write `control_music` asked for; keeps all four verbs' wording in [message]. */
    enum class LibraryAction { LIKE, UNLIKE, FOLLOW_ARTIST, UNFOLLOW_ARTIST }

    /**
     * Every distinct way a library write can end. **[AlreadyInThatState] is its own outcome, never
     * folded into [Applied]**: the state is read BEFORE the write, so "already liked" and "liked
     * it" come back as the two different sentences the driver can tell apart.
     */
    sealed interface LibraryWriteOutcome {
        /** The add/remove call was awaited and came back successful. */
        data object Applied : LibraryWriteOutcome

        /** The pre-write state read said the target was already as requested; nothing was written. */
        data object AlreadyInThatState : LibraryWriteOutcome

        /** Nothing is currently playing (or App Remote holds no track), so there is nothing to act on. */
        data object NothingPlaying : LibraryWriteOutcome

        /** Could not reach/connect to Spotify at all. */
        data object NotConnected : LibraryWriteOutcome

        /** Connected fine; the add/remove call itself came back unsuccessful or timed out. */
        data object WriteRejected : LibraryWriteOutcome
    }

    /** [Applied] and [AlreadyInThatState] both mean the driver's requested state now holds. */
    internal fun succeeded(outcome: LibraryWriteOutcome): Boolean =
        outcome is LibraryWriteOutcome.Applied || outcome is LibraryWriteOutcome.AlreadyInThatState

    /** The pure outcome -> spoken-line mapping; every [LibraryAction] gets its own wording per outcome. */
    internal fun message(outcome: LibraryWriteOutcome, action: LibraryAction): String {
        val words = when (action) {
            LibraryAction.LIKE -> LibraryWords("this track", "Liked it.", "Already liked - it's already in your Liked Songs.", "It wasn't liked, so nothing changed.")
            LibraryAction.UNLIKE -> LibraryWords("this track", "Unliked it.", "It wasn't liked in the first place, so nothing changed.", "Removed it from your Liked Songs.")
            LibraryAction.FOLLOW_ARTIST -> LibraryWords("this artist", "Following them now.", "Already following them.", "Wasn't following them, so nothing changed.")
            LibraryAction.UNFOLLOW_ARTIST -> LibraryWords("this artist", "Unfollowed them.", "Wasn't following them in the first place, so nothing changed.", "Stopped following them.")
        }
        return when (outcome) {
            LibraryWriteOutcome.Applied -> words.applied
            LibraryWriteOutcome.AlreadyInThatState -> words.already
            LibraryWriteOutcome.NothingPlaying ->
                "Nothing's playing right now, so there's no ${words.subject} to act on."
            LibraryWriteOutcome.NotConnected -> notConnectedMessage()
            LibraryWriteOutcome.WriteRejected -> "Spotify wouldn't apply that - ${words.rejectedNote}"
        }
    }

    private data class LibraryWords(val subject: String, val applied: String, val already: String, val rejectedNote: String)

    /**
     * What is playing right now: the pushed [playerState] if there is one, else one explicit read.
     * The fallback exists because [finishAttempt] clears the state on every (re)connect, and the
     * first push lands a moment after - a voice action arriving in that window must not report
     * "nothing playing" about a track that is audibly playing.
     */
    private fun currentPlayerState(r: SpotifyAppRemote): PlayerState? =
        playerState.value ?: try {
            r.playerApi.getPlayerState().await(CAPABILITIES_TIMEOUT_SEC, TimeUnit.SECONDS)
                .takeIf { it.isSuccessful }?.data
        } catch (e: Exception) {
            Log.w(TAG, "getPlayerState threw: ${e.message}")
            null
        }

    /**
     * The add/remove sequence behind [like]/[unlike]/[followArtist]/[unfollowArtist]: connect,
     * resolve the target uri from the CURRENT player state, read `getLibraryState` first and skip
     * the write when it already matches (so "like this" twice never double-writes), then
     * `addToLibrary`/`removeFromLibrary`. [targetUri] picks the uri off a [PlayerState].
     */
    private suspend fun libraryWrite(
        context: Context,
        add: Boolean,
        targetUri: (PlayerState) -> String?,
    ): LibraryWriteOutcome = withContext(Dispatchers.IO) {
        if (!ensureConnectedBounded(context)) return@withContext LibraryWriteOutcome.NotConnected
        val r = remote ?: return@withContext LibraryWriteOutcome.NotConnected
        val uri = currentPlayerState(r)?.let(targetUri)?.takeIf { it.isNotBlank() }
            ?: return@withContext LibraryWriteOutcome.NothingPlaying

        // Best-effort: a failed/timed-out read does not block the write, it only means this call
        // cannot short-circuit an already-correct state and attempts the write anyway.
        val currentlyAdded = try {
            r.userApi.getLibraryState(uri).await(CAPABILITIES_TIMEOUT_SEC, TimeUnit.SECONDS)
                .takeIf { it.isSuccessful }?.data?.isAdded
        } catch (e: Exception) {
            Log.w(TAG, "getLibraryState($uri) threw: ${e.message}")
            null
        }
        if (currentlyAdded == add) return@withContext LibraryWriteOutcome.AlreadyInThatState

        val ok = try {
            val call = if (add) r.userApi.addToLibrary(uri) else r.userApi.removeFromLibrary(uri)
            val result = call.await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!result.isSuccessful) Log.w(TAG, "library write($uri, add=$add) failed: ${result.errorMessage}")
            result.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "library write($uri, add=$add) threw: ${e.message}")
            false
        }
        if (ok) LibraryWriteOutcome.Applied else LibraryWriteOutcome.WriteRejected
    }

    /** Likes the CURRENTLY PLAYING track only - never an album, never the queue. */
    suspend fun like(context: Context): LibraryWriteOutcome = libraryWrite(context, add = true) { it.track?.uri }

    suspend fun unlike(context: Context): LibraryWriteOutcome = libraryWrite(context, add = false) { it.track?.uri }

    /**
     * Follows the CURRENT track's artist, expressed as saving `spotify:artist:...` to the library:
     * the old `PUT /me/following` is deprecated and [UserApi] only exposes the generic library calls.
     */
    suspend fun followArtist(context: Context): LibraryWriteOutcome =
        libraryWrite(context, add = true) { it.track?.artist?.uri }

    suspend fun unfollowArtist(context: Context): LibraryWriteOutcome =
        libraryWrite(context, add = false) { it.track?.artist?.uri }

    // --- Shuffle, repeat, seek --------------------------------------------------------------------

    /**
     * The state a shuffle/repeat/restart write ACTUALLY resulted in, read from a fresh
     * `getPlayerState()` taken AFTER the write - never the state that was requested. App Remote
     * offers `toggleShuffle`/`toggleRepeat` (the Web API only has set, needing its own read first),
     * and the Web API's docs warn execution order is not guaranteed alongside other player
     * endpoints - exactly the compound-command race a live voice turn risks.
     */
    data class TransportWriteResult(val isShuffling: Boolean, val repeatMode: Int)

    /**
     * Shared connect -> write -> re-read sequence. [write] is the one SDK call an action makes;
     * null means the write could not be CONFIRMED (not connected, the write failed, or the
     * re-read failed) - callers fall back to [notConnectedMessage]-style honesty.
     */
    private suspend fun applyTransportWrite(
        context: Context,
        write: (SpotifyAppRemote) -> CallResult<Empty>,
    ): TransportWriteResult? = withContext(Dispatchers.IO) {
        if (!ensureConnectedBounded(context)) return@withContext null
        val r = remote ?: return@withContext null

        val ok = try {
            val result = write(r).await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!result.isSuccessful) Log.w(TAG, "transport write failed: ${result.errorMessage}")
            result.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "transport write threw: ${e.message}")
            false
        }
        if (!ok) return@withContext null

        val state = try {
            r.playerApi.getPlayerState().await(CAPABILITIES_TIMEOUT_SEC, TimeUnit.SECONDS)
                .takeIf { it.isSuccessful }?.data
        } catch (e: Exception) {
            Log.w(TAG, "post-write getPlayerState threw: ${e.message}")
            null
        } ?: return@withContext null

        TransportWriteResult(isShuffling = state.playbackOptions.isShuffling, repeatMode = state.playbackOptions.repeatMode)
    }

    suspend fun setShuffle(context: Context, on: Boolean): TransportWriteResult? =
        applyTransportWrite(context) { it.playerApi.setShuffle(on) }

    /** Bare "shuffle" - flips whatever it currently is. */
    suspend fun toggleShuffle(context: Context): TransportWriteResult? =
        applyTransportWrite(context) { it.playerApi.toggleShuffle() }

    suspend fun setRepeatOff(context: Context): TransportWriteResult? =
        applyTransportWrite(context) { it.playerApi.setRepeat(Repeat.OFF) }

    /** "Repeat this" - the CURRENT TRACK repeats. Not the same request as [setRepeatContext]. */
    suspend fun setRepeatTrack(context: Context): TransportWriteResult? =
        applyTransportWrite(context) { it.playerApi.setRepeat(Repeat.ONE) }

    /** "Repeat the album/playlist" - the whole context repeats, not just the current track. */
    suspend fun setRepeatContext(context: Context): TransportWriteResult? =
        applyTransportWrite(context) { it.playerApi.setRepeat(Repeat.ALL) }

    /** Jumps back to the start of the current track. */
    suspend fun restart(context: Context): TransportWriteResult? =
        applyTransportWrite(context) { it.playerApi.seekTo(0) }

    /** Reads the RESULT, never the request. */
    internal fun shuffleMessage(state: TransportWriteResult): String =
        if (state.isShuffling) "Shuffle's on." else "Shuffle's off."

    /** Reads the RESULT, never the request. */
    internal fun repeatMessage(state: TransportWriteResult): String = when (state.repeatMode) {
        Repeat.OFF -> "Repeat's off."
        Repeat.ONE -> "Repeating this track."
        Repeat.ALL -> "Repeating the whole thing."
        else -> "Repeat's set."
    }

    /**
     * Every distinct way a seek can end. **[TrackChanged] is its own outcome, never folded into
     * [Landed]**: seeking forward past the end hands off to the NEXT song, and the driver must be
     * told that happened rather than hearing "jumped forward 30 seconds".
     */
    sealed interface SeekOutcome {
        /** Landed inside the SAME track. [positionMs] is null if the confirm-read failed though the seek succeeded. */
        data class Landed(val positionMs: Long?) : SeekOutcome

        /** The seek crossed the end of the track and Spotify moved on to the next one. */
        data object TrackChanged : SeekOutcome

        data object NotConnected : SeekOutcome

        /** Connected fine; the seek call itself came back unsuccessful or timed out. */
        data object SeekRejected : SeekOutcome
    }

    /**
     * Relative seek via `seekToRelativePosition` (positive [deltaMs] forward, negative back),
     * chosen over an absolute `seekTo` so no position read is needed first. Detects
     * [SeekOutcome.TrackChanged] by comparing the track uri before and after.
     */
    suspend fun seekRelative(context: Context, deltaMs: Long): SeekOutcome = withContext(Dispatchers.IO) {
        if (!ensureConnectedBounded(context)) return@withContext SeekOutcome.NotConnected
        val r = remote ?: return@withContext SeekOutcome.NotConnected
        val beforeUri = currentPlayerState(r)?.track?.uri

        val ok = try {
            val result = r.playerApi.seekToRelativePosition(deltaMs).await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!result.isSuccessful) Log.w(TAG, "seekToRelativePosition($deltaMs) failed: ${result.errorMessage}")
            result.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "seekToRelativePosition($deltaMs) threw: ${e.message}")
            false
        }
        if (!ok) return@withContext SeekOutcome.SeekRejected

        val after = try {
            r.playerApi.getPlayerState().await(CAPABILITIES_TIMEOUT_SEC, TimeUnit.SECONDS)
                .takeIf { it.isSuccessful }?.data
        } catch (e: Exception) {
            Log.w(TAG, "post-seek getPlayerState threw: ${e.message}")
            null
        } ?: return@withContext SeekOutcome.Landed(null)

        if (beforeUri != null && after.track?.uri != beforeUri) return@withContext SeekOutcome.TrackChanged
        SeekOutcome.Landed(after.playbackPosition)
    }

    /**
     * The artist of whatever is playing, for "more from this artist". Same fallback as
     * [currentPlayerState] so a fresh reconnect does not read as "nobody is playing".
     */
    suspend fun currentArtistNow(context: Context): CurrentArtist? = withContext(Dispatchers.IO) {
        if (!ensureConnectedBounded(context)) return@withContext null
        val r = remote ?: return@withContext null
        currentArtistFrom(currentPlayerState(r))
    }

    /** Bound on the App Remote play round trip - long enough for a slow head unit, short enough not to stall a voice turn. */
    private const val PLAY_TIMEOUT_SEC = 8L

    /**
     * Fire-and-forget transport for the main-thread taps. Returns whether the call was DISPATCHED
     * (App Remote up, no synchronous throw); the CallResult's own failure is logged when it lands.
     */
    private inline fun withPlayer(label: String, action: (SpotifyAppRemote) -> CallResult<Empty>): Boolean {
        val r = remote ?: return false
        return try {
            action(r).setErrorCallback { e -> Log.w(TAG, "transport $label failed: ${e.message}") }
            true
        } catch (e: Exception) {
            Log.w(TAG, "transport command failed: ${e.message}")
            false
        }
    }
}
