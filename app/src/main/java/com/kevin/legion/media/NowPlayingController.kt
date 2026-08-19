package com.kevin.legion.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MusicPlayHistoryEntry
import com.kevin.legion.service.MediaNotificationListener
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/** What's playing in the active media session, for the system prompt and the Cruise HUD. */
data class NowPlayingInfo(
    val title: String,
    val artist: String,
    val album: String,
    // Strict: the session reports STATE_PLAYING. Keep for anything that needs to be
    // accurate about actual playback.
    val isPlaying: Boolean,
    // Lenient: "music is up" — true unless the session explicitly reports paused/
    // stopped/error. Drives the Cruise screen's cosmetic animations, because Bluetooth
    // AVRCP often delivers metadata without ever reporting STATE_PLAYING.
    val isActive: Boolean = isPlaying,
    val position: Long,
    val duration: Long,
    // URI string only — never a raw Bitmap — so the StateFlow stays allocation-free.
    // The composable loads and scales the image locally at render size.
    val albumArtUri: String? = null,
    // Diagnostics surfaced on the Cruise screen behind the debug toggle (ADB logcat
    // is blocked on the head unit, so we read the real AVRCP state on-screen).
    val playbackStateRaw: Int = 0,
    val artSource: String = "none", // "uri" | "bitmap" | "none"
)

/**
 * Mirrors whatever's playing in the active media session via Android's MediaSession
 * framework — phone over BT (AVRCP), Spotify on the head unit, any audio app.
 * No SDK or polling required; the OS delivers events via the notification listener.
 *
 * Reading other apps' sessions requires the user to grant notification access once
 * (system gate for [MediaSessionManager.getActiveSessions]).
 */
object NowPlayingController {
    private val _state = MutableStateFlow<NowPlayingInfo?>(null)
    val state: StateFlow<NowPlayingInfo?> = _state.asStateFlow()

    private var activeController: MediaController? = null
    private var activeCallback: MediaController.Callback? = null
    private var initialized = false
    private var appCtx: Context? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // All MediaSession callbacks are routed through the main looper. Without an
    // explicit Handler the framework binds them to the calling/binder thread,
    // which on some head units has no Looper - registerCallback then throws
    // "Can't create handler inside thread that has not called Looper.prepare()".
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- LEGION's own listening history (ticket 05, 2026-08-18) ------------------------------

    /** The last (title, artist, album) actually written to [MusicPlayHistoryEntry], and when. */
    internal data class LoggedTrack(val title: String, val artist: String, val album: String, val loggedAt: Long)
    @Volatile private var lastLogged: LoggedTrack? = null

    /**
     * How long a repeat observation of the SAME (title, artist, album) is suppressed for.
     * [updateState] re-fires on every `onPlaybackStateChanged` callback - play, pause, seek -
     * not only on a genuine track change, so without this window, pausing and resuming the same
     * song a few seconds apart would write two rows for one listen. Two minutes comfortably
     * covers a stoplight-length pause without covering a genuine repeat-listen of a short song,
     * which is still allowed to log again once the window has passed - see [shouldLogHistoryEntry].
     */
    internal const val PLAY_HISTORY_DEDUP_WINDOW_MS = 120_000L

    /**
     * Spotify's own package, duplicated here rather than imported from [com.kevin.legion.media.SpotifyController]
     * (which keeps its own copy private) - matches [MusicController]'s same duplication, both
     * places small enough that a shared constant isn't worth a new coupling.
     */
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    /**
     * How long after [markLegionInitiatedPlay] a newly observed track is attributed to LEGION
     * itself rather than to the driver starting something unprompted. Generous enough to cover
     * App Remote's own round trip plus the MediaSession callback landing after it, tight enough
     * that an unrelated track change minutes later is never mis-attributed.
     */
    private const val LEGION_INITIATED_ATTRIBUTION_WINDOW_MS = 10_000L
    @Volatile private var legionInitiatedAt: Long = 0L

    /**
     * Marks that LEGION ITSELF just started playback (a successful `play_music` search-and-play).
     * The next track [updateState] observes within [LEGION_INITIATED_ATTRIBUTION_WINDOW_MS] is
     * logged with [MusicPlayHistoryEntry.startedByLegion] true instead of false. Called from
     * [com.kevin.legion.service.LiveToolbox]'s `play_music` handler, nowhere else - `control_music`
     * transport commands (play/pause/next/previous) resume or skip within whatever is ALREADY
     * playing rather than starting a specific new track, so they do not mark this.
     */
    fun markLegionInitiatedPlay() {
        legionInitiatedAt = System.currentTimeMillis()
    }

    /**
     * Pure decision of whether [candidate] is worth writing as a new history row, given the
     * [last] row actually logged. Kept Android-free and internal so it is a plain JVM unit test:
     * a DIFFERENT (title, artist, album) always logs; the SAME one only logs again once
     * [dedupWindowMs] has passed since it was last logged - see [PLAY_HISTORY_DEDUP_WINDOW_MS]'s
     * own doc for why the window exists at all.
     */
    internal fun shouldLogHistoryEntry(
        last: LoggedTrack?,
        candidate: LoggedTrack,
        dedupWindowMs: Long = PLAY_HISTORY_DEDUP_WINDOW_MS,
    ): Boolean {
        if (last == null) return true
        if (last.title != candidate.title || last.artist != candidate.artist || last.album != candidate.album) {
            return true
        }
        return candidate.loggedAt - last.loggedAt >= dedupWindowMs
    }

    /**
     * Resolves the Spotify URI to store for [candidate], if App Remote's own player-state
     * [track] genuinely matches the MediaSession observation that produced it - ticket 09
     * (`.scratch/spotify-voice/issues/09-history-uri.md`) spending ticket 02's
     * [SpotifyController.playerState] wiring. Pure and internal for the same reason
     * [shouldLogHistoryEntry] is: a plain JVM unit test, no Android or App Remote connection
     * needed (both [Track] and its nested [com.spotify.protocol.types.Artist] are plain POJOs
     * with no Android dependency - confirmed by decompiling the bundled `classes.jar`).
     *
     * Two checks, both load-bearing:
     * - [sourcePackage] must be Spotify's own package. MediaSession metadata from a NON-Spotify
     *   player (phone AVRCP, another app) must never pick up whatever URI App Remote happens to
     *   be holding, even if title and artist happen to coincide.
     * - [track]'s own name/artist must equal [candidate]'s. MediaSession's callback and App
     *   Remote's push are two independent event streams that can observe a track change
     *   microseconds apart, so [track] can legitimately be one step stale relative to
     *   [candidate] at the instant this runs - the equality check is what stops a stale [track]
     *   from being attached to the wrong row.
     *
     * Ticket 09 is explicit that a wrong URI is worse than no URI (the same reasoning as its
     * ban on backfilling old null rows by searching titles) - on ANY mismatch this returns
     * null, same as "unknown", never a best guess.
     */
    internal fun resolveSpotifyUri(sourcePackage: String?, candidate: LoggedTrack, track: Track?): String? {
        if (sourcePackage != SPOTIFY_PACKAGE) return null
        if (track == null) return null
        if (track.name != candidate.title) return null
        if (track.artist?.name != candidate.artist) return null
        return track.uri
    }

    /**
     * Writes a [MusicPlayHistoryEntry] for [info] if [shouldLogHistoryEntry] says this is a new
     * observation, not a re-fire on the same track. Best-effort and fire-and-forget on
     * [ioScope]: a history-write failure must never take down playback observation, which is why
     * this is wrapped the same defensive way every other branch in [updateState] is.
     *
     * [info] can be null (session gone) or carry the metadata sentinel `"Unknown title"`
     * [NowPlayingInfo.title] falls back to when the MediaSession reported no title at all -
     * neither is a real track, so neither is logged.
     *
     * [sourcePackage] is the package of the [android.media.session.MediaController] that
     * produced [info] ([pickController]'s selection) - the sole input [resolveSpotifyUri] needs
     * to tell a Spotify-sourced observation from any other player's. Non-Spotify audio always
     * writes [MusicPlayHistoryEntry.spotifyUri] null here, correctly - there is no URI to have.
     */
    private fun maybeLogHistory(info: NowPlayingInfo?, sourcePackage: String?) {
        if (info == null) return
        val title = info.title
        if (title.isBlank() || title == "Unknown title") return

        val now = System.currentTimeMillis()
        val candidate = LoggedTrack(title = title, artist = info.artist, album = info.album, loggedAt = now)
        if (!shouldLogHistoryEntry(lastLogged, candidate)) return
        lastLogged = candidate

        val ctx = appCtx ?: return
        // See markLegionInitiatedPlay's own doc: within the attribution window, this observed
        // change is attributed to a LEGION-initiated play rather than the driver's own.
        val startedByLegion = now - legionInitiatedAt <= LEGION_INITIATED_ATTRIBUTION_WINDOW_MS
        // Read HERE, not inside the launched coroutine below: App Remote's playerState can move
        // on between now and when that coroutine actually runs, and resolveSpotifyUri's equality
        // check is what this instant's candidate was compared against - reading it again later
        // would compare against a DIFFERENT moment than the one that produced startedByLegion.
        val spotifyUri = resolveSpotifyUri(sourcePackage, candidate, SpotifyController.playerState.value?.track)
        ioScope.launch {
            try {
                CarDatabase.getDatabase(ctx).musicPlayHistoryDao().insert(
                    MusicPlayHistoryEntry(
                        title = title,
                        artist = info.artist,
                        album = info.album,
                        spotifyUri = spotifyUri,
                        startedAt = now,
                        startedByLegion = startedByLegion,
                    ),
                )
            } catch (e: Exception) {
                // Never let a history write take down playback observation.
            }
        }
    }

    /**
     * The pure decision behind [pickController]'s session selection, kept Android-free so the
     * anti-loop rule is a plain JVM unit test rather than something only provable on a device.
     * [candidates] is (packageName, isPlaying) for every session [MediaSessionManager] currently
     * reports; [ownPackage] is always excluded FIRST, before either the "prefer STATE_PLAYING" or
     * the "fall back to the first one" rule runs - see [pickController]'s own comment for exactly
     * why that ordering is load-bearing rather than incidental. Returns null when nothing (other
     * than possibly LEGION itself) is publishing a session.
     */
    internal fun choosePackage(candidates: List<Pair<String, Boolean>>, ownPackage: String?): String? {
        val eligible = candidates.filter { (pkg, _) -> pkg != ownPackage }
        return eligible.firstOrNull { (_, isPlaying) -> isPlaying }?.first
            ?: eligible.firstOrNull()?.first
    }

    fun hasAccess(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    /** Safe to call repeatedly - retries until notification access is granted. */
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        appCtx = appContext
        val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listenerComponent = ComponentName(appContext, MediaNotificationListener::class.java)

        try {
            manager.addOnActiveSessionsChangedListener({ controllers ->
                pickController(controllers)
            }, listenerComponent, mainHandler)
            pickController(manager.getActiveSessions(listenerComponent))
            initialized = true
        } catch (e: SecurityException) {
            // Notification access not granted yet.
        }

        // ticket 07 (.scratch/spotify-voice/issues/07-now-playing-truth.md): App Remote's
        // subscribeToPlayerState (ticket 02) is Spotify's OWN truth - pushed the instant Spotify's
        // player changes, no polling, no quota - so it is listened to DIRECTLY here rather than
        // only being read opportunistically inside updateState. This matters because the two
        // event streams (MediaSession's callback and App Remote's push) are independent and can
        // land microseconds apart; without this collector, "what's playing" would only refresh
        // when MediaSession's OWN callback happened to fire next, which is exactly the "whatever
        // the player app chose to publish" staleness this ticket exists to close. Gated on Spotify
        // actually being the CURRENTLY SELECTED source ([isSpotifyActive]) so a push arriving while
        // some other app is playing (or before any session has been chosen at all) never overwrites
        // [_state] with Spotify data nobody asked to hear about.
        //
        // Guarded by its OWN flag, not [initialized]: [init] is "safe to call repeatedly - retries
        // until notification access is granted" per this function's own doc, so a caller may invoke
        // it several times before [initialized] ever flips true. Gating this collector on
        // [initialized] instead would either delay it indefinitely behind a permission this
        // collector doesn't need, or (worse) launch a fresh duplicate collector on every retry.
        if (!spotifyCollectorStarted) {
            spotifyCollectorStarted = true
            ioScope.launch {
                SpotifyController.playerState.collect { onSpotifyPlayerStateChanged(it) }
            }
        }
    }

    @Volatile private var spotifyCollectorStarted = false

    /** True when the [MediaSessionManager] session currently selected as the source is Spotify's own. */
    private fun isSpotifyActive(): Boolean = activeController?.packageName == SPOTIFY_PACKAGE

    /** See the doc on the collector in [init] this backs. */
    private fun onSpotifyPlayerStateChanged(state: PlayerState?) {
        if (state == null || !isSpotifyActive()) return
        _state.value = infoFromPlayerState(state, _state.value)
    }

    /**
     * Builds [NowPlayingInfo] straight from App Remote's [PlayerState] (ticket 07) rather than
     * MediaSession metadata, for every field App Remote actually reports. [previous] supplies the
     * album-art fields: App Remote's own [com.spotify.protocol.types.ImageUri] is a `spotify:image:...`
     * URI that needs a further `ImagesApi` round trip to resolve to bytes, which this ticket does
     * not ask for - carrying the last art MediaSession found forward is strictly better than
     * dropping it, and wrong only in the rare case the track changed between the two callbacks,
     * which self-corrects the next time MediaSession's own metadata update lands.
     */
    internal fun infoFromPlayerState(state: PlayerState, previous: NowPlayingInfo?): NowPlayingInfo {
        val track = state.track
        val isPlaying = !state.isPaused
        return NowPlayingInfo(
            title = track?.name?.takeIf { it.isNotBlank() } ?: "Unknown title",
            artist = track?.artist?.name.orEmpty(),
            album = track?.album?.name.orEmpty(),
            isPlaying = isPlaying,
            isActive = isPlaying,
            position = state.playbackPosition,
            duration = track?.duration ?: 0L,
            albumArtUri = previous?.albumArtUri,
            playbackStateRaw = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            artSource = previous?.artSource ?: "none",
        )
    }

    // Wrapped in try/catch: this re-enters on every cross-package session change
    // (track change, play/pause) and a throw here would otherwise take down the app.
    private fun pickController(controllers: List<MediaController>?) {
        try {
            // LOAD-BEARING, not defensive: com.kevin.legion.car.LegionMediaLibraryService now
            // publishes its OWN active MediaSession (the Android Auto now-playing card), and that
            // session shows up in getActiveSessions() exactly like Spotify's or the phone's AVRCP
            // bridge does. Without this filter, the very first NowPlayingController tick after
            // LEGION's session goes active would read ITS OWN mirrored metadata back as "what's
            // playing", which is the metadata LEGION just mirrored FROM this same controller one
            // event earlier - a closed loop with no external anchor. Concretely: the proxy's
            // isPlaying flips true because NowPlayingController said so, that flip republishes the
            // proxy's own session state, pickController sees a session reporting STATE_PLAYING and
            // (wrongly) prefers it as the "real" source, and the row it displays is forever a
            // reflection of itself - never correcting even when the actual source (Spotify) pauses.
            // It would also start writing phantom MusicPlayHistoryEntry rows for a "track" that is
            // really just LEGION quoting LEGION. Excluding it at the SOURCE - before either the
            // STATE_PLAYING preference or the fallback-to-first pick runs - is the only place this
            // can be closed for good; filtering later (e.g. in the UI) would still let the loop run
            // and still log bogus history.
            val ownPackage = appCtx?.packageName
            val selectedPackage = choosePackage(
                controllers?.map { it.packageName to (it.playbackState?.state == PlaybackState.STATE_PLAYING) }
                    ?: emptyList(),
                ownPackage,
            )
            val controller = controllers?.firstOrNull { it.packageName == selectedPackage }

            if (controller?.sessionToken == activeController?.sessionToken) {
                controller?.let { updateState(it) }
                return
            }

            activeCallback?.let { cb -> activeController?.unregisterCallback(cb) }

            activeController = controller
            if (controller == null) {
                activeCallback = null
                _state.value = null
                return
            }

            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = updateState(controller)
                override fun onPlaybackStateChanged(state: PlaybackState?) = updateState(controller)
                override fun onSessionDestroyed() {
                    _state.value = null
                }
            }
            controller.registerCallback(callback, mainHandler)
            activeCallback = callback
            updateState(controller)
        } catch (e: Exception) {
            // Never let a media-session hiccup crash the app.
        }
    }

    private fun updateState(controller: MediaController) {
        try {
            val metadata = controller.metadata
            if (metadata == null) {
                _state.value = null
                return
            }
            val playbackState = controller.playbackState
            val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)

            val rawState = playbackState?.state ?: PlaybackState.STATE_NONE
            // Lenient "active": only an EXPLICIT pause/stop/error stops the cosmetic
            // animations. Bluetooth AVRCP frequently reports STATE_NONE while music
            // is actually playing, so anything that isn't a clear stop counts as up.
            val isActive = rawState != PlaybackState.STATE_PAUSED &&
                rawState != PlaybackState.STATE_STOPPED &&
                rawState != PlaybackState.STATE_ERROR
            val hasBitmapArt = artUri == null && (
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ||
                    metadata.getBitmap(MediaMetadata.METADATA_KEY_ART) != null
                )
            val artSource = when {
                artUri != null -> "uri"
                hasBitmapArt -> "bitmap"
                else -> "none"
            }

            // ticket 07: when THIS controller is Spotify's own AND App Remote already holds a
            // pushed PlayerState, that state is Spotify's OWN truth and wins over whatever
            // MediaSession's metadata happens to say - see [infoFromPlayerState]'s own doc for why
            // art is still taken from metadata rather than App Remote. Every non-Spotify session
            // (and a Spotify session before App Remote's first push lands) is completely unchanged
            // from before this ticket: built from MediaSession metadata exactly as always.
            val spotifyState = if (controller.packageName == SPOTIFY_PACKAGE) SpotifyController.playerState.value else null
            _state.value = if (spotifyState != null) {
                infoFromPlayerState(spotifyState, _state.value).copy(albumArtUri = artUri, artSource = artSource)
            } else {
                NowPlayingInfo(
                    title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown title",
                    artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                    album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                    isPlaying = rawState == PlaybackState.STATE_PLAYING,
                    isActive = isActive,
                    position = playbackState?.position ?: 0L,
                    duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    albumArtUri = artUri,
                    playbackStateRaw = rawState,
                    artSource = artSource,
                )
            }
            maybeLogHistory(_state.value, controller.packageName)

            // Most apps (Spotify, YT Music) embed art as a Bitmap in METADATA_KEY_ALBUM_ART
            // rather than a content URI. If there's no URI, write the bitmap to the cache
            // dir on IO and update the state with a file:// URI so the composable can load it.
            if (artUri == null) {
                val bmp = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (bmp != null) {
                    val ctx = appCtx ?: return
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ioScope.launch {
                        try {
                            val file = File(ctx.cacheDir, "album_art_current.jpg")
                            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                            val current = _state.value ?: return@launch
                            if (current.title == title && current.albumArtUri == null) {
                                _state.value = current.copy(albumArtUri = "file://${file.absolutePath}")
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore - stale/destroyed controller; state will refresh on next change.
        }
    }
}
