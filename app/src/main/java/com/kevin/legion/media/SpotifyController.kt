package com.kevin.legion.media

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.CompanionProfile
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * and we stay on the phone-BT / mixtape path with zero regression.
 *
 * While connected this flips [MusicSource] to SPOTIFY so [MusicRouter] sends
 * transport here; on disconnect it reverts to PHONE. Everything is defensively
 * guarded - a Spotify failure must never crash the launcher.
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
    const val REDIRECT_URI = "com.kevin.legion://spotify-callback"

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
                        finishAttempt(deferred, null)
                    }
                },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "App Remote connect threw synchronously: ${e.message}")
            finishAttempt(deferred, null)
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
    private fun finishAttempt(deferred: CompletableDeferred<Boolean>, connected: SpotifyAppRemote?) {
        remote = connected
        if (connected != null) {
            MusicSource.set(Source.SPOTIFY)
            Log.i(TAG, "App Remote connected")
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
     * in flight rather than starting a second one, but does not wait on it. On
     * success flips [MusicSource] to SPOTIFY; on failure leaves the source
     * untouched (stays PHONE/MIXTAPE).
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

    /** Tears down the connection and reverts routing to phone BT. Safe to call repeatedly. */
    fun disconnect() {
        remote?.let { runCatching { SpotifyAppRemote.disconnect(it) } }
        remote = null
        if (MusicSource.current.value == Source.SPOTIFY) MusicSource.set(Source.PHONE)
    }

    fun play(): Boolean = withPlayer { it.playerApi.resume() }

    fun pause(): Boolean = withPlayer { it.playerApi.pause() }

    fun next(): Boolean = withPlayer { it.playerApi.skipNext() }

    fun previous(): Boolean = withPlayer { it.playerApi.skipPrevious() }

    /**
     * Starts playback of a Spotify URI **in place** - the Spotify app stays in the
     * background and Cruise keeps the screen. This is the half App Remote can do on
     * its own; finding the URI for a spoken name needs [SpotifyWebApi.searchTrackUri]
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

    /** Bound on the App Remote play round trip - long enough for a slow head unit, short enough not to stall a voice turn. */
    private const val PLAY_TIMEOUT_SEC = 8L

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
