package com.kevin.midnightai.media

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure decisions behind the Web API (Spotify Connect) playback fallback ([SpotifyConnect]).
 * Android-free, so every branch runs in a plain JVM test.
 *
 * Why a fallback exists: App Remote needs the Spotify app on this unit to accept an AIDL bind
 * AND to have shown its own consent sheet once. On the head unit that never happened, so every
 * pinned tile and every `play_music` died at the connect step even though the Web API grant
 * (search, playlists) was fine. The Web API can start playback on any Spotify Connect device
 * the account can see, which includes the Spotify app running on this unit, so playback no
 * longer hangs on App Remote at all.
 */
object SpotifyConnectLogic {

    /** One entry of `GET /v1/me/player/devices`. */
    data class Device(
        val id: String,
        val name: String,
        val type: String,
        val isActive: Boolean,
        val isRestricted: Boolean,
    )

    /** Devices with a usable id. Spotify may send `id: null` for a device it will not let us target. */
    fun parseDevices(json: JSONObject): List<Device> {
        val arr = json.optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = if (d.isNull("id")) "" else d.optString("id")
            if (id.isBlank()) return@mapNotNull null
            Device(
                id = id,
                name = d.optString("name"),
                type = d.optString("type"),
                isActive = d.optBoolean("is_active"),
                isRestricted = d.optBoolean("is_restricted"),
            )
        }
    }

    /** Device types a phone-class Spotify client (the app on this head unit) registers as. */
    private val LOCAL_TYPES = setOf("smartphone", "automobile", "tablet")

    private fun isLocalType(d: Device) = d.type.lowercase() in LOCAL_TYPES

    /**
     * True when [deviceName] looks like one of [localNames] (this unit's model / device name).
     * Exact match, or containment either way for names of 3+ characters - Spotify reports the
     * Android device name, which vendors pad ("rk3399 Car" vs "rk3399").
     */
    internal fun nameMatches(deviceName: String, localNames: List<String>): Boolean {
        val dn = deviceName.trim().lowercase()
        if (dn.isEmpty()) return false
        return localNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { n ->
            dn == n || (n.length >= 3 && dn.contains(n)) || (dn.length >= 3 && n.contains(dn))
        }
    }

    /**
     * The device to play on, or null when there is none to target. Order:
     *  1. a phone-class device whose name matches this unit - the Spotify app right here;
     *  2. whatever is already active - the account is playing there, so the driver expects it;
     *  3. the first phone-class device - most likely this unit under a vendor name;
     *  4. the first device at all.
     * Restricted devices refuse Web API commands, so they are never picked.
     *
     * Name match beats is_active on purpose: the head-unit account could still be active on a
     * desktop at home, and starting music there from the car would be silent in the car.
     */
    fun pickDevice(devices: List<Device>, localNames: List<String>): Device? {
        val usable = devices.filter { !it.isRestricted }
        if (usable.isEmpty()) return null
        return usable.firstOrNull { isLocalType(it) && nameMatches(it.name, localNames) }
            ?: usable.firstOrNull { it.isActive }
            ?: usable.firstOrNull { isLocalType(it) }
            ?: usable.first()
    }

    /** True for URIs that `PUT /me/player/play` takes as `context_uri` (playlist, album, artist, show). */
    fun isContextUri(uri: String): Boolean {
        val parts = uri.split(':')
        // spotify:track:ID / spotify:episode:ID are single items; everything else is a context,
        // including the legacy spotify:user:NAME:playlist:ID shape.
        return !(parts.size == 3 && parts[1] in setOf("track", "episode"))
    }

    /** The `PUT /me/player/play` body for [uri]. */
    fun playBody(uri: String): JSONObject =
        if (isContextUri(uri)) JSONObject().put("context_uri", uri)
        else JSONObject().put("uris", JSONArray().put(uri))

    /** Every way a Web API play can fail, each with its own words. */
    sealed interface Failure {
        /** No Web API grant on file at all (never authorized, or no client ID). */
        data object NeverAuthorized : Failure

        /** Spotify refused the token (revoked, expired past refresh). */
        data object TokenRejected : Failure

        /** The grant lacks the playback scopes. Fixed by RE-AUTHORIZE SPOTIFY. */
        data class MissingScopes(val scopes: List<String>) : Failure

        /** 403 PREMIUM_REQUIRED: Web API playback control is Premium-only. */
        data object PremiumRequired : Failure

        /** No Spotify Connect device to play on, even after opening the Spotify app. */
        data class NoDevice(val spotifyInstalled: Boolean) : Failure

        /** Could not reach Spotify (no signal). */
        data object Unreachable : Failure

        /** Any other refusal. [detail] is Spotify's own message when it sent one. */
        data class Rejected(val code: Int, val detail: String?) : Failure
    }

    /** The scopes Web API playback needs: devices read and play/pause/skip write. */
    val PLAYBACK_SCOPES = listOf("user-read-playback-state", "user-modify-playback-state")

    /** [PLAYBACK_SCOPES] absent from [granted] (Spotify's space-delimited scope string). */
    fun missingPlaybackScopes(granted: String): List<String> {
        val have = granted.split(' ', ',').filter { it.isNotBlank() }.toSet()
        return PLAYBACK_SCOPES.filter { it !in have }
    }

    /**
     * Maps a non-2xx player-endpoint response to a [Failure]; null for success. Spotify's error
     * body is `{"error":{"status":403,"message":"...","reason":"PREMIUM_REQUIRED"}}` - `reason`
     * only on player endpoints, so the message is checked too.
     */
    fun failureForResponse(code: Int, body: String): Failure? {
        if (code in 200..299) return null
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val reason = err?.optString("reason").orEmpty()
        val message = err?.optString("message")?.takeIf { it.isNotBlank() }
        val lower = message.orEmpty().lowercase()
        return when {
            code == 401 -> Failure.TokenRejected
            reason == "PREMIUM_REQUIRED" || lower.contains("premium") -> Failure.PremiumRequired
            reason == "NO_ACTIVE_DEVICE" || code == 404 -> Failure.NoDevice(spotifyInstalled = true)
            code == 403 && lower.contains("scope") -> Failure.MissingScopes(PLAYBACK_SCOPES)
            else -> Failure.Rejected(code, message ?: reason.ifBlank { null })
        }
    }

    /** A few words for a tile, a toast or the Settings line. */
    fun shortReason(f: Failure): String = when (f) {
        Failure.NeverAuthorized -> "Spotify not authorized"
        Failure.TokenRejected -> "Spotify sign-in expired - re-authorize"
        is Failure.MissingScopes -> "re-authorize Spotify (playback permission)"
        Failure.PremiumRequired -> "Spotify Premium required"
        is Failure.NoDevice -> if (f.spotifyInstalled) "Spotify app not responding" else "Spotify app not installed"
        Failure.Unreachable -> "no connection to Spotify"
        is Failure.Rejected -> "Spotify refused (${f.code}${f.detail?.let { ": $it" } ?: ""})"
    }

    /** The spoken line for `play_music`. [description] is what the driver asked for. */
    fun spokenReason(f: Failure, description: String): String = when (f) {
        Failure.NeverAuthorized ->
            "Spotify isn't authorized here, so I couldn't play \"$description\". Connect it in Settings, Music, Spotify."
        Failure.TokenRejected ->
            "Spotify's sign-in here has expired, so I couldn't play \"$description\". Tap RE-AUTHORIZE SPOTIFY in Settings, Music, Spotify."
        is Failure.MissingScopes ->
            "Spotify hasn't given this app playback permission yet. Tap RE-AUTHORIZE SPOTIFY in Settings, Music, Spotify, then ask again."
        Failure.PremiumRequired ->
            "Spotify says this account needs Premium to start music from another app, so I couldn't play \"$description\"."
        is Failure.NoDevice ->
            if (f.spotifyInstalled) {
                "The Spotify app on the head unit didn't show up as a player, so I couldn't play \"$description\". " +
                    "Open Spotify there once, make sure it's signed in, then ask again."
            } else {
                "Spotify isn't installed on this head unit, so there's nothing to play \"$description\" on."
            }
        Failure.Unreachable ->
            "I can't reach Spotify right now, so I couldn't play \"$description\". Check the hotspot and try again."
        is Failure.Rejected ->
            "Spotify refused to play \"$description\"" + (f.detail?.let { " ($it)" } ?: "") + "."
    }

    /**
     * Waits before each silent reconnect attempt after an App Remote drop: Spotify may still be
     * restarting at 5s, so later tries are spaced out. Bounded (three) so a Spotify that is
     * really gone is not retried forever; MainActivity.onResume remains the backstop.
     */
    val RECONNECT_SCHEDULE_MS: List<Long> = listOf(5_000L, 20_000L, 60_000L)

    /** How long a recent App Remote failure skips a fresh App Remote attempt before a play. */
    const val RECENT_FAILURE_MS = 60_000L

    /**
     * Whether a play should (re)try App Remote before falling back to the Web API. Retrying an
     * attempt that failed seconds ago costs the driver several seconds of silence for an answer
     * already known, so a play goes straight to the Web API then. An attempt still in flight is
     * joined (it may be seconds from connecting). [msSinceLastFailure] null = no failure on record.
     *
     * [callerAlreadyTried] (2026-10-15 car report): the caller (`play_music`) already waited its own
     * budget on App Remote this turn and it did not connect. An attempt still "in flight" is then
     * the very one that just timed out, and joining it again stacked a second 6s wait ahead of the
     * Web API leg, so the play could not settle inside the tool budget. Only an already-live link
     * counts then.
     */
    fun shouldTryAppRemote(
        isConnected: Boolean,
        attemptInFlight: Boolean,
        msSinceLastFailure: Long?,
        callerAlreadyTried: Boolean = false,
    ): Boolean =
        isConnected || (!callerAlreadyTried &&
            (attemptInFlight || msSinceLastFailure == null || msSinceLastFailure >= RECENT_FAILURE_MS))

    /** What `GET /v1/me/player/currently-playing` said, trimmed to what Cruise uses. */
    data class CurrentlyPlaying(val isPlaying: Boolean, val contextUri: String?, val trackName: String?)

    /** 204 (nothing playing) maps to not-playing; any unreadable or failed response to null. */
    fun parseCurrentlyPlaying(code: Int, body: String): CurrentlyPlaying? {
        if (code == 204) return CurrentlyPlaying(false, null, null)
        if (code !in 200..299) return null
        return runCatching {
            val j = JSONObject(body)
            CurrentlyPlaying(
                isPlaying = j.optBoolean("is_playing"),
                contextUri = j.optJSONObject("context")?.optString("uri")?.takeIf { it.isNotBlank() },
                trackName = j.optJSONObject("item")?.optString("name")?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    /** Transport commands the Web API fallback can send. */
    enum class Command { RESUME, PAUSE, NEXT, PREVIOUS }

    /** HTTP method and path for [cmd] (relative to `https://api.spotify.com/v1/me/player`). */
    fun commandRequest(cmd: Command): Pair<String, String> = when (cmd) {
        Command.RESUME -> "PUT" to "/play"
        Command.PAUSE -> "PUT" to "/pause"
        Command.NEXT -> "POST" to "/next"
        Command.PREVIOUS -> "POST" to "/previous"
    }

    /** Who started a Web API play: decides whether the Spotify app's UI may ever be opened. */
    enum class PlayCaller(val tag: String) {
        /** A tap on a Cruise pinned-playlist tile: the driver is looking at Midnight and asked. */
        PINNED_TILE("pinned_tile"),

        /** Any voice tool (play_music, more_from_artist): runs inside a voice turn. */
        VOICE("voice"),
    }

    /**
     * Whether a play with no Spotify Connect device may open the Spotify app's activity as the
     * LAST resort, after the UI-less wake (App Remote's service bind, Spotify's media browser)
     * found nothing. Opening it puts Spotify in front of the launcher, so (2026-10-13 car
     * report) only a tile tap may, only while Midnight is in front (so it can come straight
     * back), and never while a voice turn is running - including a tile tapped mid-turn.
     * Returns null when allowed, else the reason it is not (for the breadcrumb).
     */
    fun uiLaunchRefusal(caller: PlayCaller, midnightInFront: Boolean, voiceBusy: Boolean): String? = when {
        caller != PlayCaller.PINNED_TILE -> "caller ${caller.tag} never opens Spotify"
        voiceBusy -> "voice turn running"
        !midnightInFront -> "Midnight not in front"
        else -> null
    }

    /**
     * `GET /v1/me/player` trimmed to "is it playing, and on which device". 204 = no playback
     * anywhere (not playing, no device); unreadable or failed responses are null.
     */
    fun parsePlayer(code: Int, body: String): Pair<Boolean, Device?>? {
        if (code == 204) return false to null
        if (code !in 200..299) return null
        return runCatching {
            val j = JSONObject(body)
            val d = j.optJSONObject("device")
            val device = d?.let {
                val id = if (it.isNull("id")) "" else it.optString("id")
                Device(
                    id = id,
                    name = it.optString("name"),
                    type = it.optString("type"),
                    isActive = it.optBoolean("is_active"),
                    isRestricted = it.optBoolean("is_restricted"),
                )
            }
            j.optBoolean("is_playing") to device
        }.getOrNull()
    }

    /**
     * After a process restart or an App Remote drop: is this account playing on THIS unit, so
     * Cruise should show Spotify as the source? Playing on a phone-class device. The name is not
     * required to match: vendors pad it, and the head-unit Spotify account is not the driver's
     * phone account, so a phone-class player on it is this unit. A desktop or speaker is not.
     */
    fun playingOnThisUnit(isPlaying: Boolean, device: Device?): Boolean =
        isPlaying && device != null && !device.isRestricted && isLocalType(device)

    /** How long the UI-less wake waits for the Spotify app to register as a device. */
    const val WAKE_WAIT_MS = 6_000L

    /** How long to wait for the Spotify app to register as a device after opening it. */
    const val DEVICE_WAIT_MS = 6_000L

    /** Device list poll interval while waiting. */
    const val DEVICE_POLL_MS = 1_000L

    /** Now-playing poll interval while App Remote is down and Spotify owns the speakers. */
    const val NOW_PLAYING_POLL_MS = 5_000L
}
