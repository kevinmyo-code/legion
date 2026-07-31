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
import com.kevin.legion.service.MediaNotificationListener
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

    // Wrapped in try/catch: this re-enters on every cross-package session change
    // (track change, play/pause) and a throw here would otherwise take down the app.
    private fun pickController(controllers: List<MediaController>?) {
        try {
            val controller = controllers?.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers?.firstOrNull()

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

            _state.value = NowPlayingInfo(
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
