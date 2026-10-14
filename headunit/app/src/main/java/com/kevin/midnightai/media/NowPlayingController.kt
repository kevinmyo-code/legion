package com.kevin.midnightai.media

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
import com.kevin.midnightai.service.MediaNotificationListener
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
    // When [position] was sampled, on the SystemClock.elapsedRealtime() clock (the session's
    // PlaybackState.lastPositionUpdateTime), and how fast it advances. Lets the UI run the
    // seeker forward between callbacks without polling the session. 0 = unknown.
    val positionUpdatedAt: Long = 0L,
    val playbackSpeed: Float = 1f,
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

    // Every active session we hold a callback on (main thread only). Which one the HUD shows
    // is decided by [pickBestSession] on each change, not fixed at list-change time.
    private var tracked: List<Pair<MediaController, MediaController.Callback>> = emptyList()
    // Art file currently shown (written on IO); deleted when the next track's art lands.
    @Volatile private var lastArtFile: File? = null
    private var initialized = false
    private var appCtx: Context? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // All MediaSession callbacks are routed through the main looper. Without an
    // explicit Handler the framework binds them to the calling/binder thread,
    // which on some head units has no Looper - registerCallback then throws
    // "Can't create handler inside thread that has not called Looper.prepare()".
    private val mainHandler = Handler(Looper.getMainLooper())

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
    }

    /**
     * Called when the SESSION LIST changes: swap our callbacks onto every session in the new
     * list, then re-pick. We listen to all of them, not just the picked one, because the list
     * listener does not fire when another session's metadata/state changes - and after a
     * Bluetooth reconnect the BT session routinely shows up first with no metadata and only
     * delivers it a beat later. Listening to the picked session alone left that update unheard
     * (stale pick, "PLAY FROM YOUR PHONE" until the app was relaunched).
     *
     * Wrapped in try/catch: this re-enters on every cross-package session change and a throw
     * here would otherwise take down the app.
     */
    private fun pickController(controllers: List<MediaController>?) {
        try {
            tracked.forEach { (c, cb) ->
                try { c.unregisterCallback(cb) } catch (_: Exception) {}
            }
            tracked = emptyList()

            val fresh = controllers.orEmpty().mapNotNull { controller ->
                try {
                    val callback = object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) = repick()
                        override fun onPlaybackStateChanged(state: PlaybackState?) = repick()
                        override fun onSessionDestroyed() {
                            // Drop the dead session so a later pick can't land on it.
                            tracked = tracked.filter { it.first !== controller }
                            repick()
                        }
                    }
                    controller.registerCallback(callback, mainHandler)
                    controller to callback
                } catch (e: Exception) {
                    null
                }
            }
            tracked = fresh
            repick()
        } catch (e: Exception) {
            // Never let a media-session hiccup crash the app.
        }
    }

    /** Re-rank the tracked sessions and publish the winner (null state when none/no metadata). */
    private fun repick() {
        try {
            // One throwing session (head units do this) is dropped, not allowed to abort the
            // whole pick; sessions/candidates stay index-aligned so the winner maps back.
            val sessions = ArrayList<MediaController>()
            val candidates = ArrayList<SessionCandidate>()
            for ((c, _) in tracked) {
                try {
                    candidates.add(
                        SessionCandidate(
                            state = c.playbackState?.state ?: PlaybackState.STATE_NONE,
                            hasTitle = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null,
                            packageName = c.packageName ?: "",
                        ),
                    )
                    sessions.add(c)
                } catch (_: Exception) {
                }
            }
            val ownPackage = appCtx?.packageName
            val source = MusicSource.current.value
            val idx = pickBestSession(candidates) { pkg ->
                when (source) {
                    Source.SPOTIFY -> pkg == "com.spotify.music"
                    Source.MIXTAPE -> pkg == ownPackage
                    Source.PHONE -> pkg != "com.spotify.music" && pkg != ownPackage
                }
            }
            if (idx == null) {
                _state.value = null
                return
            }
            updateState(sessions[idx])
        } catch (e: Exception) {
            // Stale/destroyed controller; the next callback or list change re-picks.
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

            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown title"
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            // Every callback from any session re-runs this. For a bitmap-art track the art
            // already written for THIS title+artist must survive that chatter, or the cassette
            // art blanks and reloads each time: keep the old file:// URI and skip the rewrite.
            val prev = _state.value
            val keptArtUri = prev?.takeIf {
                artUri == null && it.title == title && it.artist == artist &&
                    it.albumArtUri?.startsWith("file://") == true
            }?.albumArtUri

            _state.value = NowPlayingInfo(
                title = title,
                artist = artist,
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                isPlaying = rawState == PlaybackState.STATE_PLAYING,
                isActive = isActive,
                position = playbackState?.position ?: 0L,
                positionUpdatedAt = playbackState?.lastPositionUpdateTime ?: 0L,
                playbackSpeed = playbackState?.playbackSpeed ?: 1f,
                duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                albumArtUri = artUri ?: keptArtUri,
                playbackStateRaw = rawState,
                artSource = artSource,
            )

            // Most apps (Spotify, YT Music) embed art as a Bitmap in METADATA_KEY_ALBUM_ART
            // rather than a content URI. If there's no URI, write the bitmap to the cache
            // dir on IO and update the state with a file:// URI so the composable can load it.
            if (artUri == null && keptArtUri == null) {
                val bmp = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (bmp != null) {
                    val ctx = appCtx ?: return
                    ioScope.launch {
                        // Per-track file name: a slow write for track A can no longer land
                        // under track B's state through one shared file.
                        val file = File(ctx.cacheDir, "album_art_${"$title|$artist".hashCode().toUInt()}.jpg")
                        try {
                            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                            val current = _state.value
                            if (current != null && current.title == title && current.artist == artist &&
                                current.albumArtUri == null
                            ) {
                                _state.value = current.copy(albumArtUri = "file://${file.absolutePath}")
                                val previous = lastArtFile
                                lastArtFile = file
                                if (previous != null && previous != file) previous.delete()
                            } else {
                                // Track moved on while we wrote; this file is for nobody.
                                if (file != lastArtFile) file.delete()
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
