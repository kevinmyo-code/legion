package com.kevin.midnightai.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.media.MediaBrowserService
import android.util.Log
import com.kevin.midnightai.MidnightEvents
import com.kevin.midnightai.service.ConversationState
import com.kevin.midnightai.media.SpotifyConnectLogic.Command
import com.kevin.midnightai.media.SpotifyConnectLogic.Failure
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Spotify playback over the Web API's Spotify Connect endpoints - the fallback for when App
 * Remote is not connected. Decisions live in [SpotifyConnectLogic]; this is the I/O.
 *
 * Play: list devices, pick this unit's Spotify ([SpotifyConnectLogic.pickDevice]); with no
 * device at all (the Spotify app is not running) wake it without UI first, and only as the
 * last resort, for a tile tap with Midnight in front and no voice turn running
 * ([SpotifyConnectLogic.uiLaunchRefusal]), open the app, then bring Midnight back and play.
 * Transport and the now-playing poll ride the same token and remembered device.
 */
object SpotifyConnect {
    private const val TAG = "SpotifyConnect"
    private const val PLAYER = "https://api.spotify.com/v1/me/player"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** The device the last Web API play went to; transport targets it. */
    @Volatile private var lastDeviceId: String? = null

    /** Name of that device, for diagnostics. */
    @Volatile var lastDeviceName: String? = null
        private set

    /** Last Web API play failure, for the Settings line. Cleared on success. */
    @Volatile var lastFailure: Failure? = null
        private set

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.w(TAG, "background work failed: ${e.message}") },
    )

    // Transport commands run one at a time, in tap order: a pause must not overtake a play.
    private val commandLock = Mutex()

    /** How a Web API play ended. */
    sealed interface PlayResult {
        data class Started(val deviceName: String) : PlayResult
        data object Superseded : PlayResult
        data class Failed(val failure: Failure) : PlayResult
    }

    private sealed interface Auth {
        data class Ok(val token: String) : Auth
        data class No(val failure: Failure) : Auth
    }

    private suspend fun auth(context: Context): Auth {
        val granted = SpotifyWebApi.grantedScope(context)
        return when (val t = SpotifyWebApi.tokenOutcome(context)) {
            is SpotifyWebApi.TokenOutcome.Token -> {
                // A blank stored scope is "unknown" for old grants: try anyway, Spotify will say.
                val missing = if (granted.isBlank()) emptyList() else SpotifyConnectLogic.missingPlaybackScopes(granted)
                if (missing.isNotEmpty()) Auth.No(Failure.MissingScopes(missing)) else Auth.Ok(t.value)
            }
            SpotifyWebApi.TokenOutcome.NeverAuthorized -> Auth.No(Failure.NeverAuthorized)
            SpotifyWebApi.TokenOutcome.Rejected -> Auth.No(Failure.TokenRejected)
            SpotifyWebApi.TokenOutcome.Unreachable -> Auth.No(Failure.Unreachable)
        }
    }

    /** One request as (status, body); null when Spotify could not be reached. */
    private fun call(method: String, url: String, token: String, body: String? = null): Pair<Int, String>? = try {
        val rb = when {
            body != null -> body.toRequestBody(JSON)
            method == "GET" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").method(method, rb).build()
        http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "$method $url failed: ${e.message}")
        null
    }

    private fun devices(token: String): Pair<List<SpotifyConnectLogic.Device>, Failure?> {
        val (code, body) = call("GET", "$PLAYER/devices", token) ?: return emptyList<SpotifyConnectLogic.Device>() to Failure.Unreachable
        SpotifyConnectLogic.failureForResponse(code, body)?.let { return emptyList<SpotifyConnectLogic.Device>() to it }
        val list = runCatching { SpotifyConnectLogic.parseDevices(org.json.JSONObject(body)) }.getOrDefault(emptyList())
        return list to null
    }

    /** This unit's names as Spotify is likely to report them (Android device name, model). */
    private fun localNames(context: Context): List<String> = listOfNotNull(
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                null
            }
        }.getOrNull(),
        Build.MODEL,
        Build.DEVICE,
    )

    /** Package of an installed, launchable Spotify app, preferring the standard build. */
    internal fun spotifyLaunchPackage(context: Context): String? =
        SpotifyAppProbe.CANDIDATE_PACKAGES.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    /**
     * Plays [uri] on this unit's Spotify through the Web API. [shuffle] turns shuffle on (best
     * effort, before and after the play so it holds whether or not the device was active).
     * [stillWanted] is asked before the play is sent.
     */
    suspend fun play(
        context: Context,
        uri: String,
        shuffle: Boolean,
        stillWanted: () -> Boolean,
        caller: SpotifyConnectLogic.PlayCaller,
    ): PlayResult = withContext(Dispatchers.IO) {
        try {
            playInner(context, uri, shuffle, stillWanted, caller)
        } finally {
            releaseWakeBrowser()
        }
    }

    private suspend fun playInner(
        context: Context,
        uri: String,
        shuffle: Boolean,
        stillWanted: () -> Boolean,
        caller: SpotifyConnectLogic.PlayCaller,
    ): PlayResult {
        val token = when (val a = auth(context)) {
            is Auth.Ok -> a.token
            is Auth.No -> return fail(a.failure)
        }

        val (list, failure) = devices(token)
        failure?.let { return fail(it) }
        val names = localNames(context)
        var device = SpotifyConnectLogic.pickDevice(list, names)
        if (device == null) {
            // Nothing to target: the Spotify app is not running. 2026-10-13 car report: this used
            // to open the Spotify app straight away, which put it in front of the launcher. Now:
            //  1. wake it WITHOUT UI (App Remote's service bind + Spotify's media browser, what
            //     Android Auto and Assistant use) and wait for it to register;
            //  2. only if that finds nothing, and only for a tile tap while Midnight is in front
            //     and no voice turn is running, open the app and come straight back.
            val pkg = spotifyLaunchPackage(context) ?: return fail(Failure.NoDevice(spotifyInstalled = false))
            val browserFound = withContext(Dispatchers.Main) { wakeWithoutUi(context, pkg) }
            val woke = waitForDevice(token, names, SpotifyConnectLogic.WAKE_WAIT_MS)
            woke.second?.let { return fail(it) }
            device = woke.first
            MidnightEvents.spotifyUiLessWake(caller.tag, browserFound, deviceAppeared = device != null)
            if (device == null) {
                val refusal = SpotifyConnectLogic.uiLaunchRefusal(
                    caller,
                    midnightInFront = SpotifyController.activityInFront,
                    voiceBusy = ConversationState.isBusy,
                )
                if (refusal != null) {
                    MidnightEvents.spotifyAppLaunchRefused(caller.tag, refusal)
                    return fail(Failure.NoDevice(spotifyInstalled = true))
                }
                val opened = openSpotify(context, pkg, caller)
                val launched = waitForDevice(token, names, SpotifyConnectLogic.DEVICE_WAIT_MS)
                if (opened) returnToMidnight(context, caller)
                launched.second?.let { return fail(it) }
                device = launched.first
            }
        }
        val target = device ?: return fail(Failure.NoDevice(spotifyInstalled = true))
        return playOn(token, target, uri, shuffle, stillWanted, context)
    }

    /**
     * Polls the device list for up to [waitMs] until [SpotifyConnectLogic.pickDevice] finds one.
     * (device, null) on success or timeout (device null); (null, failure) on a hard failure.
     */
    private suspend fun waitForDevice(
        token: String,
        names: List<String>,
        waitMs: Long,
    ): Pair<SpotifyConnectLogic.Device?, Failure?> {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            delay(SpotifyConnectLogic.DEVICE_POLL_MS)
            val (l, f) = devices(token)
            if (f != null && f !is Failure.NoDevice) return null to f
            SpotifyConnectLogic.pickDevice(l, names)?.let { return it to null }
        }
        return null to null
    }

    private suspend fun playOn(
        token: String,
        target: SpotifyConnectLogic.Device,
        uri: String,
        shuffle: Boolean,
        stillWanted: () -> Boolean,
        context: Context,
    ): PlayResult {
        if (!stillWanted()) return PlayResult.Superseded

        if (shuffle) setShuffle(token, target.id)
        var result = call("PUT", "$PLAYER/play?device_id=${target.id}", token, SpotifyConnectLogic.playBody(uri).toString())
            ?: return fail(Failure.Unreachable)
        var playFailure = SpotifyConnectLogic.failureForResponse(result.first, result.second)
        if (playFailure is Failure.NoDevice) {
            // A device that just registered can 404 for a moment. One retry.
            delay(1_500)
            if (!stillWanted()) return PlayResult.Superseded
            result = call("PUT", "$PLAYER/play?device_id=${target.id}", token, SpotifyConnectLogic.playBody(uri).toString())
                ?: return fail(Failure.Unreachable)
            playFailure = SpotifyConnectLogic.failureForResponse(result.first, result.second)
        }
        playFailure?.let {
            Log.w(TAG, "play($uri) on ${target.name} failed ${result.first}: ${result.second.take(200)}")
            return fail(it)
        }
        if (shuffle) setShuffle(token, target.id)
        lastDeviceId = target.id
        lastDeviceName = target.name
        lastFailure = null
        startNowPlayingPoll(context)
        return PlayResult.Started(target.name)
    }

    private fun fail(f: Failure): PlayResult.Failed {
        lastFailure = f
        return PlayResult.Failed(f)
    }

    private fun setShuffle(token: String, deviceId: String) {
        call("PUT", "$PLAYER/shuffle?state=true&device_id=$deviceId", token)?.let { (code, body) ->
            if (code !in 200..299) Log.w(TAG, "shuffle failed $code: ${body.take(200)}")
        }
    }

    // Spotify's media browser, held only while a play waits for the app to register.
    @Volatile private var wakeBrowser: MediaBrowser? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Starts the Spotify app's process with no activity: App Remote's silent connect (a service
     * bind - `SpotifyServiceBinder` in the 0.8.0 aar only binds/starts services, never an
     * activity) and a connection to Spotify's MediaBrowserService (what Android Auto and
     * Assistant use to start Spotify headless). Either one brings the player up so it can
     * register as a Connect device. Main thread (MediaBrowser needs a Looper). Returns whether
     * a media browser service was found.
     */
    private fun wakeWithoutUi(context: Context, pkg: String): Boolean {
        runCatching { SpotifyController.connectSilently(context) }
            .onFailure { Log.w(TAG, "silent App Remote wake failed: ${it.message}") }
        return runCatching {
            val svc = context.packageManager
                .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
                .firstOrNull()?.serviceInfo ?: return@runCatching false
            releaseWakeBrowser()
            val browser = MediaBrowser(
                context.applicationContext,
                ComponentName(svc.packageName, svc.name),
                object : MediaBrowser.ConnectionCallback() {},
                null,
            )
            wakeBrowser = browser
            browser.connect()
            true
        }.getOrElse {
            Log.w(TAG, "media browser wake failed: ${it.message}")
            false
        }
    }

    private fun releaseWakeBrowser() {
        val b = wakeBrowser ?: return
        wakeBrowser = null
        // On the main looper the browser was created on.
        mainHandler.post { runCatching { b.disconnect() } }
    }

    /**
     * Opens the Spotify app so it registers as a Connect device: the last resort, after the
     * UI-less wake, and only where [SpotifyConnectLogic.uiLaunchRefusal] allows. True when it
     * was launched.
     */
    private suspend fun openSpotify(
        context: Context,
        pkg: String,
        caller: SpotifyConnectLogic.PlayCaller,
    ): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            MidnightEvents.spotifyAppLaunched(caller.tag, "no Connect device after UI-less wake")
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "could not open $pkg: ${it.message}")
            false
        }
    }

    /**
     * Brings Midnight back over the Spotify app it opened. Spotify's own activity start is
     * asynchronous and can land after our first reorder, so this checks and tries once more;
     * each attempt is a breadcrumb with whether Midnight actually got back in front.
     */
    private suspend fun returnToMidnight(context: Context, caller: SpotifyConnectLogic.PlayCaller) {
        for (attempt in 1..RETURN_ATTEMPTS) {
            withContext(Dispatchers.Main) {
                runCatching {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                        it.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION,
                        )
                        context.startActivity(it)
                    }
                }.onFailure { Log.w(TAG, "could not return to Midnight: ${it.message}") }
            }
            delay(RETURN_CHECK_MS)
            val inFront = SpotifyController.activityInFront
            MidnightEvents.midnightReturn(caller.tag, attempt, inFront)
            if (inFront) return
        }
    }

    /**
     * Process start / App Remote drop: if this account is already playing on this unit, show
     * Spotify as the source again and keep the playing tile marked through the Web API poll, so
     * Now Playing does not sit empty while App Remote is down. Never starts or launches anything.
     */
    fun recoverNowPlaying(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (SpotifyController.isConnected) return@launch
            val token = (auth(app) as? Auth.Ok)?.token ?: return@launch
            val (code, body) = call("GET", PLAYER, token) ?: return@launch
            val (playing, device) = SpotifyConnectLogic.parsePlayer(code, body) ?: return@launch
            if (!SpotifyConnectLogic.playingOnThisUnit(playing, device)) return@launch
            device?.id?.takeIf { it.isNotBlank() }?.let { lastDeviceId = it; lastDeviceName = device.name }
            if (MusicSource.current.value == Source.PHONE) MusicSource.set(Source.SPOTIFY)
            startNowPlayingPoll(app)
        }
    }

    /** Attempts at bringing Midnight back after opening Spotify, and the wait before checking. */
    private const val RETURN_ATTEMPTS = 2
    private const val RETURN_CHECK_MS = 1_500L

    /**
     * Fire-and-forget transport over the Web API, in tap order. Targets the device the last Web
     * API play went to, else whatever is active.
     */
    fun command(context: Context, cmd: Command) {
        val app = context.applicationContext
        scope.launch {
            commandLock.withLock {
                val token = (auth(app) as? Auth.Ok)?.token ?: return@withLock
                val (method, path) = SpotifyConnectLogic.commandRequest(cmd)
                val url = PLAYER + path + (lastDeviceId?.let { "?device_id=$it" } ?: "")
                call(method, url, token)?.let { (code, body) ->
                    if (code !in 200..299) Log.w(TAG, "$cmd failed $code: ${body.take(200)}")
                }
            }
            startNowPlayingPoll(app)
        }
    }

    @Volatile private var pollJob: Job? = null

    /**
     * Polls `currently-playing` every [SpotifyConnectLogic.NOW_PLAYING_POLL_MS] while Spotify owns
     * the speakers and App Remote is down, feeding [SpotifyController.playingContext] so the
     * playing tile is still marked. Stops on its own once App Remote connects or the source moves.
     */
    fun startNowPlayingPoll(context: Context) {
        if (pollJob?.isActive == true) return
        val app = context.applicationContext
        pollJob = scope.launch {
            while (isActive && MusicSource.current.value == Source.SPOTIFY && !SpotifyController.isConnected) {
                val token = (auth(app) as? Auth.Ok)?.token
                if (token != null) {
                    call("GET", "$PLAYER/currently-playing", token)?.let { (code, body) ->
                        SpotifyConnectLogic.parseCurrentlyPlaying(code, body)?.let { now ->
                            SpotifyController.setWebPlayingContext(now.contextUri, now.trackName)
                        }
                    }
                }
                delay(SpotifyConnectLogic.NOW_PLAYING_POLL_MS)
            }
        }
    }
}
