package com.kevin.legion.media

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.CompanionProfile
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Empty
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Repeat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
 * and control_music's plain MediaSession transport keeps working with zero regression.
 *
 * No source-routing layer exists anymore (MusicRouter/MusicSource were retired with
 * the mixtape stack in the 2026-07-31 pivot) - callers check [isConnected] directly.
 * Everything is defensively guarded - a Spotify failure must never crash the app.
 *
 * WIRED 2026-08-12: [com.kevin.legion.ui.SpotifyScreen] (`settings/spotify`) captures
 * the client ID and drives both grants, the manifest declares the redirect scheme and
 * the `<queries>` entry App Remote needs to see the Spotify package at all, and
 * [com.kevin.legion.ui.MainActivity]'s `onResume` calls [connectSilently]. Before that
 * date every one of those was absent and this whole object was unreachable in practice.
 * Policy caveat: the BYO-own-dev-app pattern's compliance with Spotify's developer terms
 * is GRAY and under review (.scratch/spotify-byo/); risk accepted by Kevin 2026-07-21.
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
    const val REDIRECT_URI = "com.kevin.legion://spotify-callback"

    /**
     * The Spotify app's package. App Remote is an app-to-app binding, so this
     * being absent means nothing in [SpotifyController] can work, no matter
     * what is saved or authorized.
     */
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    /**
     * Whether the Spotify app is installed on this device.
     *
     * **Requires the `<queries>` entry in the manifest.** On API 30+ package
     * visibility is filtered by default, so without that declaration this
     * returns false even when Spotify IS installed - and, worse, the App Remote
     * bind itself fails for the same reason. Added to the manifest 2026-08-12
     * alongside the Setup screen; the SDK's own docs require it and this app
     * had never declared it.
     */
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SPOTIFY_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

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

    val isConnected: Boolean get() = remote?.isConnected == true

    /**
     * The exception the most recent failed connect attempt actually threw, so a caller building
     * a spoken failure (ticket 02) can name what it was - installed-but-not-installed-enough,
     * not-logged-in, not-authorized, offline - instead of one generic line. Cleared on the next
     * successful connect so a stale reason never survives past the failure that produced it.
     */
    @Volatile private var lastConnectFailure: Throwable? = null

    // --- subscribeToPlayerState (ticket 02, map decision 4 target: tickets 07 and 09) --------
    //
    // Push, not polled - the SDK delivers a new PlayerState every time Spotify's own player
    // changes, no quota consumed. This ticket only wires the subscription up and exposes the
    // state; nothing downstream reads it yet (07/09 do). [NowPlayingController]'s own reporting
    // is deliberately UNCHANGED here - see this ticket's scope item 4.
    @Volatile private var playerStateSubscription: Subscription<PlayerState>? = null
    private val _playerState = MutableStateFlow<PlayerState?>(null)
    val playerState: StateFlow<PlayerState?> = _playerState.asStateFlow()

    /** (Re)subscribes to App Remote's own player-state stream on a freshly connected [r]. */
    private fun subscribePlayerState(r: SpotifyAppRemote) {
        playerStateSubscription?.cancel()
        // Two statements, not a chain: setEventCallback returns Subscription<T> but
        // setErrorCallback (declared on the shared PendingResultBase, not overridden on
        // Subscription) returns the WIDER PendingResult<T> - chaining would silently widen the
        // type here and this field would no longer be assignable as a Subscription<PlayerState>.
        val subscription = r.playerApi.subscribeToPlayerState()
            .setEventCallback { state -> _playerState.value = state }
        subscription.setErrorCallback { e -> Log.w(TAG, "player state subscription error: ${e.message}") }
        playerStateSubscription = subscription
    }

    /**
     * Starts (or joins) the one connect attempt in flight. Every public entry
     * point below funnels through here so there is exactly one
     * `SpotifyAppRemote.connect()` call active at a time, regardless of how many
     * callers ask concurrently. The FIRST caller's [showAuthView] choice wins for
     * that attempt - a later caller cannot upgrade a silent attempt into one that
     * pops the consent sheet, or downgrade an explicit one into a silent retry;
     * it only waits for the answer.
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
    private fun startConnect(context: Context, showAuthView: Boolean): CompletableDeferred<Boolean> {
        inFlight?.let { return it }

        val deferred = CompletableDeferred<Boolean>()
        inFlight = deferred

        val clientId = CompanionProfile.spotifyClientId(context)
        if (clientId.isBlank()) {
            inFlight = null
            deferred.complete(false)
            return deferred
        }

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
        try {
            SpotifyAppRemote.connect(
                context.applicationContext,
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
        return deferred
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
        remote = connected
        if (connected != null) {
            Log.i(TAG, "App Remote connected")
            lastConnectFailure = null
            subscribePlayerState(connected)
        } else {
            lastConnectFailure = failure
        }
        inFlight = null
        deferred.complete(connected != null)
    }

    /**
     * Connects App Remote using the driver's BYO client ID, popping Spotify's
     * consent sheet if a grant doesn't already exist. Only call this from a
     * context where the driver just explicitly asked to connect (the Setup
     * screen's CONNECT button, or the OAuth redirect landing) - anywhere else,
     * use [connectSilently]. Fire-and-forget: joins whatever attempt is already
     * in flight rather than starting a second one, but does not wait on it.
     */
    fun connect(context: Context) {
        if (isConnected) return
        startConnect(context, showAuthView = true)
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
        val clientId = CompanionProfile.spotifyClientId(context)
        if (clientId.isBlank()) return false
        return startConnect(context, showAuthView = false).await()
    }

    /** Tears down the connection. Safe to call repeatedly. */
    fun disconnect() {
        playerStateSubscription?.cancel()
        playerStateSubscription = null
        _playerState.value = null
        remote?.let { runCatching { SpotifyAppRemote.disconnect(it) } }
        remote = null
    }

    fun play(): Boolean = withPlayer { it.playerApi.resume() }

    fun pause(): Boolean = withPlayer { it.playerApi.pause() }

    fun next(): Boolean = withPlayer { it.playerApi.skipNext() }

    fun previous(): Boolean = withPlayer { it.playerApi.skipPrevious() }

    // --- The one play path (ticket 02, map decisions 4 and 7) --------------------------------

    /**
     * Every distinct way [playUri] can end, so a caller can both decide success/failure
     * ([succeeded]) and build the exact spoken line ([message]) without re-deriving either from
     * a boolean or a raw exception. Named after the four SDK exceptions the research called out
     * as the four distinct spoken failures a driver needs (never one generic string), plus the
     * two outcomes that are ours, not the SDK's: never having Spotify at all, and a play() call
     * that reached Spotify but was refused (region lock, a stale/bad URI, no active device even
     * after the switch attempt).
     */
    sealed interface PlayOutcome {
        /** Started for real - [playUri] awaited the actual `CallResult`, this is not optimistic. */
        data class Started(
            // Non-null only when [UserApi.getCapabilities] came back and said this account
            // can't play on demand (map decision 7) - told in words, never silently degraded.
            val premiumWarning: String? = null,
            // What search actually picked (ticket 07, e.g. "Discovery, Daft Punk"), carried
            // through from [playUri]'s own [pickedLabel] param. Null falls back to [description]
            // in [message] below - the knownUri replay path where the driver's own words already
            // name exactly what's about to play.
            val pickedLabel: String? = null,
        ) : PlayOutcome

        /** [isInstalled] said no, or App Remote's own connect attempt threw [CouldNotFindSpotifyApp]. */
        data object NotInstalled : PlayOutcome

        /** App Remote connected to Spotify, but nobody is signed into it. */
        data object NotLoggedIn : PlayOutcome

        /** Spotify is up and signed in, but refused this app/account ([UserNotAuthorizedException]). */
        data object NotAuthorized : PlayOutcome

        /** Spotify is in offline mode. */
        data object Offline : PlayOutcome

        /** Connect failed for a reason the SDK didn't give one of the four names above. */
        data class ConnectFailed(val detail: String?) : PlayOutcome

        /** Connected fine; the `play()` call itself came back unsuccessful or timed out. */
        data object PlayRejected : PlayOutcome
    }

    /** Only [PlayOutcome.Started] represents sound actually starting. */
    internal fun succeeded(outcome: PlayOutcome): Boolean = outcome is PlayOutcome.Started

    /**
     * The pure outcome -> spoken-line mapping, kept free of [Context] and the SDK's async
     * plumbing (same shape as [com.kevin.legion.location.NavigationController.message]) so it is
     * a plain JVM unit test. [description] is whatever the driver asked for in their own words
     * (a song/artist/playlist name) - every failure names it, so "nothing happened" always comes
     * with a "to what".
     */
    internal fun message(outcome: PlayOutcome, description: String): String = when (outcome) {
        is PlayOutcome.Started ->
            "Playing \"${outcome.pickedLabel ?: description}\" on Spotify." +
                (outcome.premiumWarning?.let { " $it" } ?: "")
        PlayOutcome.NotInstalled ->
            "Spotify isn't installed on this phone, so there's nothing to play \"$description\" through."
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
    }

    /**
     * Maps the [Throwable] a failed connect attempt actually threw to a [PlayOutcome]. Pure and
     * `internal` for the same reason as [message]: the four exception classes matched here
     * ([CouldNotFindSpotifyApp], [NotLoggedInException], [UserNotAuthorizedException],
     * [OfflineModeException]) are plain `java.lang.Exception` subclasses bundled in the App
     * Remote aar with no Android framework dependency of their own (confirmed by javap against
     * the bundled classes.jar), so this is constructible and testable on a plain JVM.
     */
    internal fun outcomeForConnectFailure(error: Throwable?): PlayOutcome = when (error) {
        is CouldNotFindSpotifyApp -> PlayOutcome.NotInstalled
        is NotLoggedInException -> PlayOutcome.NotLoggedIn
        is UserNotAuthorizedException -> PlayOutcome.NotAuthorized
        is OfflineModeException -> PlayOutcome.Offline
        else -> PlayOutcome.ConnectFailed(error?.javaClass?.simpleName)
    }

    /**
     * THE one play path (ticket 02, map decision 4), used by every tool on this map that starts
     * a named track: [isInstalled] -> [ensureConnected] -> `ConnectApi.connectSwitchToLocalDevice()`
     * -> `PlayerApi.play(uri)`. Finding [uri] for a spoken name is [SpotifyWebApi.searchTrackUri]'s
     * job, not this one - App Remote has no search, so the split is: Web API resolves a name into
     * a URI, App Remote makes sound come out.
     *
     * The switch-to-local-device step is new and is what solves the cold case: asking for music
     * with Spotify closed. `SpotifyAppRemote.connect()` alone *creates* an active device (it
     * starts Spotify's own background service even with nothing playing), but that created device
     * is not necessarily the one this call should target - if a Connect speaker or another phone
     * was last playing, `connect()` on its own would leave that speaker as the active device.
     * `connectSwitchToLocalDevice()` explicitly pulls playback onto THIS phone before `play()`
     * lands, so a voice play command never quietly starts audio somewhere else in the house. It
     * is attempted best-effort: a failure here is logged but does not fail the whole play, since
     * `play()` can still succeed if this phone was already the active device.
     *
     * Unlike the transport commands ([play], [pause], [next], [previous]) this **waits for the
     * real result** (2026-07-29, carried forward). `playerApi.play()` returns a
     * `CallResult<Empty>` that the old code discarded, so it reported success the instant the
     * call was DISPATCHED - every genuinely async failure (region-locked track, no active
     * device, Premium required, a stale URI) reported success over silence. Awaiting turns that
     * into an answer [succeeded]/[message] can actually branch on.
     *
     * Blocking awaits throughout, so this runs off the main thread ([Dispatchers.IO]); each is
     * bounded because App Remote can leave a call outstanding indefinitely when the Spotify app
     * is wedged, and a voice command must not hang tool dispatch waiting on it.
     */
    suspend fun playUri(context: Context, uri: String, pickedLabel: String? = null): PlayOutcome = withContext(Dispatchers.IO) {
        if (!isInstalled(context)) return@withContext PlayOutcome.NotInstalled

        if (!ensureConnected(context)) {
            return@withContext outcomeForConnectFailure(lastConnectFailure)
        }

        // ensureConnected() returning true means finishAttempt already set `remote`, but it is
        // re-read (not captured) rather than trusted from the boolean, in case a concurrent
        // disconnect landed between that return and this line - a null here is reported as a
        // connect failure with no further detail rather than crashing on a null remote.
        val r = remote ?: return@withContext PlayOutcome.ConnectFailed("lost connection")

        switchToLocalDeviceBestEffort(r)

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

    /** Best-effort pull of playback onto this phone. See [playUri]'s doc for why it isn't fatal. */
    private fun switchToLocalDeviceBestEffort(r: SpotifyAppRemote) {
        try {
            val result = r.connectApi.connectSwitchToLocalDevice().await(SWITCH_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!result.isSuccessful) Log.w(TAG, "connectSwitchToLocalDevice failed: ${result.errorMessage}")
        } catch (e: Exception) {
            Log.w(TAG, "connectSwitchToLocalDevice threw: ${e.message}")
        }
    }

    /**
     * Premium detection (ticket 02, map decision 7) via `UserApi.getCapabilities().canPlayOnDemand`
     * - the only route left, since `user.product` was removed from the Web API for dev-mode apps
     * in Feb 2026. **Not a gate** - this UX is deliberately Premium-only (map decision 7) - it
     * exists purely to tell a non-Premium account so in words instead of silently shuffling its
     * playback. Best-effort: any failure or timeout here returns null (unknown) rather than
     * blocking or failing a play that otherwise already succeeded.
     */
    private fun premiumWarningIfNeeded(r: SpotifyAppRemote): String? = try {
        val result = r.userApi.getCapabilities().await(CAPABILITIES_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (result.isSuccessful && result.data?.canPlayOnDemand == false) PREMIUM_WARNING else null
    } catch (e: Exception) {
        Log.w(TAG, "getCapabilities threw: ${e.message}")
        null
    }

    /** Told, never gated on (map decision 7) - see [premiumWarningIfNeeded]. */
    private const val PREMIUM_WARNING =
        "Heads up - this Spotify account doesn't look like it has Premium, so on-demand play may not work as expected."

    /** Bound on the App Remote play round trip - long enough for a slow head unit, short enough not to stall a voice turn. */
    private const val PLAY_TIMEOUT_SEC = 8L

    /** Bound on the best-effort switch-to-local-device call - shorter than [PLAY_TIMEOUT_SEC] since a failure here isn't fatal. */
    private const val SWITCH_TIMEOUT_SEC = 5L

    /** Bound on the best-effort Premium-capability check - short, since a timeout here must not delay reporting a play as started. */
    private const val CAPABILITIES_TIMEOUT_SEC = 3L

    // --- Queue (ticket 04, .scratch/spotify-voice/issues/04-queue.md) ------------------------

    /**
     * Every distinct way [queueUri] can end - same shape as [PlayOutcome] and for the same
     * reason (map decision, honesty per outcome, never one generic string), but kept as its OWN
     * sealed type rather than reusing [PlayOutcome]: "queued" and "playing" are different verbs
     * to the driver even though [queueUri] and [playUri] share every connect-failure mode, and a
     * shared type would tempt [message] into blurring that distinction.
     */
    sealed interface QueueOutcome {
        /** `PlayerApi.queue(uri)` was awaited and came back successful. */
        data object Queued : QueueOutcome

        /** [isInstalled] said no, or App Remote's own connect attempt threw [CouldNotFindSpotifyApp]. */
        data object NotInstalled : QueueOutcome

        /** App Remote connected to Spotify, but nobody is signed into it. */
        data object NotLoggedIn : QueueOutcome

        /** Spotify is up and signed in, but refused this app/account ([UserNotAuthorizedException]). */
        data object NotAuthorized : QueueOutcome

        /** Spotify is in offline mode. */
        data object Offline : QueueOutcome

        /** Connect failed for a reason the SDK didn't give one of the four names above. */
        data class ConnectFailed(val detail: String?) : QueueOutcome

        /** Connected fine; the `queue()` call itself came back unsuccessful or timed out. */
        data object QueueRejected : QueueOutcome
    }

    /** Only [QueueOutcome.Queued] represents the track actually landing in Spotify's queue. */
    internal fun succeeded(outcome: QueueOutcome): Boolean = outcome is QueueOutcome.Queued

    /**
     * The pure outcome -> spoken-line mapping for [QueueOutcome], same shape as [message] for
     * [PlayOutcome]. **"Play X next" and "add X to the queue" are the same operation to
     * Spotify - there is no insert-at-position** (ticket 04 scope item 2), so [Queued]'s line
     * says exactly that rather than implying an ordering the API does not offer.
     */
    internal fun message(outcome: QueueOutcome, description: String): String = when (outcome) {
        QueueOutcome.Queued -> "Queued \"$description\" to play next - Spotify only offers " +
            "next-up, not a specific position in the queue."
        QueueOutcome.NotInstalled ->
            "Spotify isn't installed on this phone, so there's nothing to queue \"$description\" on."
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

    /** Same mapping as [outcomeForConnectFailure], into [QueueOutcome] instead of [PlayOutcome]. */
    internal fun queueOutcomeForConnectFailure(error: Throwable?): QueueOutcome = when (error) {
        is CouldNotFindSpotifyApp -> QueueOutcome.NotInstalled
        is NotLoggedInException -> QueueOutcome.NotLoggedIn
        is UserNotAuthorizedException -> QueueOutcome.NotAuthorized
        is OfflineModeException -> QueueOutcome.Offline
        else -> QueueOutcome.ConnectFailed(error?.javaClass?.simpleName)
    }

    /**
     * Adds [uri] to Spotify's own up-next queue via `PlayerApi.queue(uri)`, awaited so the
     * outcome reflects what actually landed (same discipline as [playUri]). Unlike [playUri],
     * this does NOT call `connectSwitchToLocalDevice()` first: queuing does not need to force
     * playback onto this phone, only App Remote's own connected session, which [ensureConnected]
     * already guarantees.
     */
    suspend fun queueUri(context: Context, uri: String): QueueOutcome = withContext(Dispatchers.IO) {
        if (!isInstalled(context)) return@withContext QueueOutcome.NotInstalled

        if (!ensureConnected(context)) {
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
        if (!queued) return@withContext QueueOutcome.QueueRejected

        QueueOutcome.Queued
    }

    // --- Library writes: like/unlike, follow/unfollow (ticket 05, .scratch/spotify-voice/issues/05-library-writes.md) --

    /**
     * Which library write `control_music` asked for. Kept as its own enum (rather than reusing
     * [LiveToolbox.MusicAction][com.kevin.legion.service.LiveToolbox.MusicAction] here, which
     * would be a service-layer type leaking into media) so [message] below can hold all four
     * verbs' wording in one place.
     */
    enum class LibraryAction { LIKE, UNLIKE, FOLLOW_ARTIST, UNFOLLOW_ARTIST }

    /**
     * Every distinct way a library write can end. **[AlreadyInThatState] is its own outcome,
     * never folded into [Applied]** - ticket 05 rule 3, "getLibraryState before speaking":
     * `getLibraryState` is read BEFORE the write so "already liked" and "liked it" come back as
     * the two different sentences the driver can actually tell apart, rather than one of them
     * being a guess.
     */
    sealed interface LibraryWriteOutcome {
        /** The add/remove call was awaited and came back successful, and the state genuinely changed. */
        data object Applied : LibraryWriteOutcome

        /** [getLibraryState] said the target was already in the requested state; nothing was written. */
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

    /**
     * The pure outcome -> spoken-line mapping for a [LibraryAction] + [LibraryWriteOutcome] pair,
     * same shape as [message] for [PlayOutcome]/[QueueOutcome]. Every branch of [action] gets its
     * own wording for every outcome - "liked" and "followed" are not interchangeable words, and
     * neither are "already liked" and "liked it".
     */
    internal fun message(outcome: LibraryWriteOutcome, action: LibraryAction): String {
        val (subject, verb, alreadyVerb, notVerb) = when (action) {
            LibraryAction.LIKE -> Quad("this track", "Liked it.", "Already liked - it's already in your Liked Songs.", "It wasn't liked, so nothing changed.")
            LibraryAction.UNLIKE -> Quad("this track", "Unliked it.", "It wasn't liked in the first place, so nothing changed.", "Removed it from your Liked Songs.")
            LibraryAction.FOLLOW_ARTIST -> Quad("this artist", "Following them now.", "Already following them.", "Wasn't following them, so nothing changed.")
            LibraryAction.UNFOLLOW_ARTIST -> Quad("this artist", "Unfollowed them.", "Wasn't following them in the first place, so nothing changed.", "Stopped following them.")
        }
        return when (outcome) {
            LibraryWriteOutcome.Applied -> verb
            LibraryWriteOutcome.AlreadyInThatState -> alreadyVerb
            LibraryWriteOutcome.NothingPlaying ->
                "Nothing's playing right now, so there's no $subject to act on."
            LibraryWriteOutcome.NotConnected ->
                "Spotify isn't connected - connect your Spotify account in Setup, or pick " +
                    "something on your phone yourself and I'll control play/pause/skip from here."
            LibraryWriteOutcome.WriteRejected ->
                "Spotify wouldn't apply that - $notVerb"
        }
    }

    /** Tiny local 4-tuple so [message] above doesn't need a data class per field it destructures. */
    private data class Quad(val subject: String, val verb: String, val alreadyVerb: String, val notVerb: String)

    /**
     * The actual add/remove sequence shared by [like]/[unlike]/[followArtist]/[unfollowArtist]:
     * connect, read [UserApi.getLibraryState] on [uri] first (ticket 05 rule 3), skip the write
     * entirely when the state already matches (so "like this" twice never double-writes), then
     * `addToLibrary`/`removeFromLibrary`.
     */
    private suspend fun libraryWrite(context: Context, uri: String?, add: Boolean): LibraryWriteOutcome =
        withContext(Dispatchers.IO) {
            if (uri.isNullOrBlank()) return@withContext LibraryWriteOutcome.NothingPlaying
            if (!ensureConnected(context)) return@withContext LibraryWriteOutcome.NotConnected
            val r = remote ?: return@withContext LibraryWriteOutcome.NotConnected

            // Best-effort: a failed/timed-out read does not block the write below, it just means
            // this call cannot short-circuit an already-correct state and instead attempts the
            // write anyway (still correct, just an extra no-op round trip to Spotify).
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

    /**
     * Likes the CURRENTLY PLAYING track only - never an album, never the whole queue (ticket 05
     * scope item 4). Reads the track uri from [playerState] (ticket 02's push subscription), not
     * a fresh explicit read, per the ticket's own wording ("read from subscribeToPlayerState").
     */
    suspend fun like(context: Context): LibraryWriteOutcome = libraryWrite(context, playerState.value?.track?.uri, add = true)

    /** Unlikes the currently playing track. See [like]. */
    suspend fun unlike(context: Context): LibraryWriteOutcome = libraryWrite(context, playerState.value?.track?.uri, add = false)

    /**
     * Follows the CURRENT track's artist, expressed as saving `spotify:artist:...` to the
     * library - the old `PUT /me/following` endpoint is deprecated (research finding, 2026-08-19),
     * and [UserApi] never exposed a follow method of its own, only the generic library calls.
     */
    suspend fun followArtist(context: Context): LibraryWriteOutcome =
        libraryWrite(context, playerState.value?.track?.artist?.uri, add = true)

    /** Unfollows the current track's artist. See [followArtist]. */
    suspend fun unfollowArtist(context: Context): LibraryWriteOutcome =
        libraryWrite(context, playerState.value?.track?.artist?.uri, add = false)

    // --- Shuffle, repeat, seek (ticket 06, .scratch/spotify-voice/issues/06-shuffle-repeat-seek.md) --

    /**
     * The state a shuffle/repeat/restart write ACTUALLY resulted in, read from a fresh
     * `getPlayerState()` call taken AFTER the write - never the state that was requested (ticket
     * 06 rule 4). App Remote is deliberately used for all three rather than the Web API: it
     * offers `toggleShuffle`/`toggleRepeat` (the Web API only has set, which needs its own read
     * first to compute the new state), and the Web API's own docs warn "the order of execution
     * is not guaranteed when you use this API with other Player API endpoints" - exactly the
     * compound-command race a live voice turn risks.
     */
    data class TransportWriteResult(val isShuffling: Boolean, val repeatMode: Int)

    /**
     * Shared connect -> write -> re-read sequence for every shuffle/repeat/restart action below.
     * [write] is the one SDK call a specific action makes; null means the write itself could not
     * be confirmed (not connected, the write failed, or the re-read failed) - callers fall back
     * to [transportWriteFailureMessage].
     */
    private suspend fun applyTransportWrite(
        context: Context,
        write: (SpotifyAppRemote) -> CallResult<Empty>,
    ): TransportWriteResult? = withContext(Dispatchers.IO) {
        if (!ensureConnected(context)) return@withContext null
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

    /** Sets shuffle explicitly. See [toggleShuffle] for the bare "shuffle" wire value. */
    suspend fun setShuffle(context: Context, on: Boolean): TransportWriteResult? =
        applyTransportWrite(context) { it.playerApi.setShuffle(on) }

    /** Bare "shuffle" (ticket 06 scope item 1) - flips whatever it currently is. */
    suspend fun toggleShuffle(context: Context): TransportWriteResult? =
        applyTransportWrite(context) { it.playerApi.toggleShuffle() }

    /** "Repeat off" - the whole session stops repeating. */
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

    /** The pure `state -> spoken line` mapping for shuffle. Reads the RESULT, never the request. */
    internal fun shuffleMessage(state: TransportWriteResult): String =
        if (state.isShuffling) "Shuffle's on." else "Shuffle's off."

    /** The pure `state -> spoken line` mapping for repeat. Reads the RESULT, never the request. */
    internal fun repeatMessage(state: TransportWriteResult): String = when (state.repeatMode) {
        Repeat.OFF -> "Repeat's off."
        Repeat.ONE -> "Repeating this track."
        Repeat.ALL -> "Repeating the whole thing."
        else -> "Repeat's set."
    }

    /** Shared "couldn't do it at all" line for any of the shuffle/repeat/seek/restart writes above. */
    internal fun transportWriteFailureMessage(): String =
        "Spotify isn't connected - connect your Spotify account in Setup, or pick something on " +
            "your phone yourself and I'll control play/pause/skip from here."

    /**
     * Every distinct way a seek can end. **[TrackChanged] is its own outcome, never folded into
     * [Landed]** - seeking forward past the end of a track hands off to the NEXT song (Spotify's
     * own documented behaviour), and the driver must be told that happened rather than hearing
     * "jumped forward 30 seconds" when the track actually changed underneath them (ticket 06
     * scope item 3).
     */
    sealed interface SeekOutcome {
        /** The seek landed inside the SAME track. [positionMs] is read from state after the seek, null if the confirm-read itself failed even though the seek call succeeded. */
        data class Landed(val positionMs: Long?) : SeekOutcome

        /** The seek crossed the end of the track and Spotify moved on to the next one. */
        data object TrackChanged : SeekOutcome

        /** Could not reach/connect to Spotify at all. */
        data object NotConnected : SeekOutcome

        /** Connected fine; the seek call itself came back unsuccessful or timed out. */
        data object SeekRejected : SeekOutcome
    }

    /**
     * Relative seek via `seekToRelativePosition` (positive [deltaMs] forward, negative back) -
     * chosen over an absolute `seekTo` so no position read is needed FIRST (ticket 06 scope item
     * 3). Detects [SeekOutcome.TrackChanged] by comparing the track uri before and after the
     * call: if the seek call itself succeeded but the track underneath changed, the seek crossed
     * the end.
     */
    suspend fun seekRelative(context: Context, deltaMs: Long): SeekOutcome = withContext(Dispatchers.IO) {
        if (!ensureConnected(context)) return@withContext SeekOutcome.NotConnected
        val r = remote ?: return@withContext SeekOutcome.NotConnected
        val beforeUri = playerState.value?.track?.uri

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

    private inline fun withPlayer(action: (SpotifyAppRemote) -> Unit): Boolean {
        val r = remote ?: return false
        return try {
            action(r)
            true
        } catch (e: Exception) {
            Log.w(TAG, "transport command failed: ${e.message}")
            false
        }
    }
}
