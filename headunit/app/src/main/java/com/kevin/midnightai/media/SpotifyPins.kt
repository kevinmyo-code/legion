package com.kevin.midnightai.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure pin-list rules, no Android types so they unit-test on the plain JVM. A pin is a full
 * [SpotifyPlaylist] (not just an id) so the Cruise row can render it offline and after Spotify
 * stops returning it.
 */
object SpotifyPinLogic {
    /** Cruise's pinned grid is 2 x 5 (2026-10-13 split deck), so ten pins fill it exactly. */
    const val MAX_PINS = 10

    sealed class PinResult {
        data class Ok(val pins: List<SpotifyPlaylist>) : PinResult()
        /** A pin past [MAX_PINS] was refused; the list is unchanged. */
        object Full : PinResult()
    }

    /** Appends [p] (pin order = tap order). Re-pinning an already pinned id is a no-op, not a duplicate. */
    fun pin(pins: List<SpotifyPlaylist>, p: SpotifyPlaylist): PinResult = when {
        pins.any { it.id == p.id } -> PinResult.Ok(pins)
        pins.size >= MAX_PINS -> PinResult.Full
        else -> PinResult.Ok(pins + p)
    }

    fun unpin(pins: List<SpotifyPlaylist>, id: String): List<SpotifyPlaylist> = pins.filterNot { it.id == id }

    /** Moves [id] by [delta] places (-1 = earlier), clamped to the ends. Unknown id = unchanged. */
    fun move(pins: List<SpotifyPlaylist>, id: String, delta: Int): List<SpotifyPlaylist> {
        val from = pins.indexOfFirst { it.id == id }
        if (from < 0) return pins
        val to = (from + delta).coerceIn(0, pins.lastIndex)
        if (to == from) return pins
        return pins.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * What Cruise shows. Pins win, in pin order, each swapped for the library's fresher copy if
     * Spotify still returns it, otherwise kept as saved: a deleted/unfollowed playlist stays
     * visible and its tap reports the failure rather than silently vanishing. No pins = the
     * first [SpotifyPlaylistLogic.MAX_PLAYLISTS] of the library, so day one is not an empty grid.
     */
    fun display(pins: List<SpotifyPlaylist>, library: List<SpotifyPlaylist>): List<SpotifyPlaylist> =
        if (pins.isEmpty()) library.take(SpotifyPlaylistLogic.MAX_PLAYLISTS) else refreshed(pins, library)

    /** [pins] with metadata updated from [library] where the id matches; order and membership untouched. */
    fun refreshed(pins: List<SpotifyPlaylist>, library: List<SpotifyPlaylist>): List<SpotifyPlaylist> {
        val byId = library.associateBy { it.id }
        return pins.map { byId[it.id] ?: it }
    }
}

/**
 * The driver's pinned playlists, persisted in SharedPreferences (`spotify_pins`). Local to this
 * device on purpose: it is not in the Drive sync, and the car it is bolted into is the one whose
 * dash it arranges.
 */
object SpotifyPins {
    private const val PREFS = "spotify_pins"
    private const val KEY = "pinned"

    private val _pinned = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val pinned: StateFlow<List<SpotifyPlaylist>> = _pinned.asStateFlow()
    @Volatile private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _pinned.value = SpotifyPlaylistLogic.fromJson(prefs(context).getString(KEY, "[]").orEmpty())
    }

    @Synchronized
    private fun commit(context: Context, list: List<SpotifyPlaylist>) {
        _pinned.value = list
        prefs(context).edit().putString(KEY, SpotifyPlaylistLogic.toJson(list)).apply()
        SpotifyPlaylists.onPinsChanged(context)
    }

    /** False (and no change) when [SpotifyPinLogic.MAX_PINS] are already pinned. */
    fun pin(context: Context, p: SpotifyPlaylist): Boolean {
        load(context)
        return when (val r = SpotifyPinLogic.pin(_pinned.value, p)) {
            is SpotifyPinLogic.PinResult.Ok -> { commit(context, r.pins); true }
            SpotifyPinLogic.PinResult.Full -> false
        }
    }

    fun unpin(context: Context, id: String) {
        load(context)
        commit(context, SpotifyPinLogic.unpin(_pinned.value, id))
    }

    fun move(context: Context, id: String, delta: Int) {
        load(context)
        commit(context, SpotifyPinLogic.move(_pinned.value, id, delta))
    }

    /** Updates saved pins' name/uri/cover from a fresh library fetch; never adds or removes a pin. */
    internal fun refreshMetadata(context: Context, library: List<SpotifyPlaylist>) {
        load(context)
        val updated = SpotifyPinLogic.refreshed(_pinned.value, library)
        if (updated != _pinned.value) {
            _pinned.value = updated
            prefs(context).edit().putString(KEY, SpotifyPlaylistLogic.toJson(updated)).apply()
        }
    }
}
