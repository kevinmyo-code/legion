package com.kevin.midnightai.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.kevin.midnightai.data.local.CarDatabase
import com.kevin.midnightai.data.local.LibraryTrack
import com.kevin.midnightai.data.local.Mixtape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Plays a mixtape's local files - the only path to real queue/track-select
 * control, since AVRCP over a phone can't carry that (see MusicController's
 * doc). This owns a real platform MediaSession under the hood (Media3's
 * MediaSession wraps one), so [NowPlayingController] - which just enumerates
 * every active session - picks it up automatically. The Cruise HUD
 * and system-prompt now-playing text need no changes to show
 * mixtape plays, and mixtape plays feed the [com.kevin.midnightai.vehicle.MusicHistoryRecorder]
 * taste ledger for free.
 *
 * Built lazily on the first [play] (not at service start: ExoPlayer + MediaSession construction
 * is main-thread work that most drives never need), then kept for the process lifetime. Until
 * then [pause]/[resume]/[next]/[previous]/[stop]/[isPlaying] are safe no-ops, and
 * [activeMixtape] is null; none of them can be reached with MusicSource=MIXTAPE before a [play]
 * has built the player, since [play] is what flips the source. Released by AriaForegroundService. Like
 * [MusicController], everything here must run on the main thread (the media
 * framework needs a Looper) - callers already satisfy this (Compose click
 * handlers, or LiveToolbox's explicit Dispatchers.Main wrap).
 */
object MixtapePlayer {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    private val _activeMixtape = MutableStateFlow<Mixtape?>(null)
    val activeMixtape: StateFlow<Mixtape?> = _activeMixtape.asStateFlow()

    /** Idempotent; called by [play] on first use. Main thread (ExoPlayer/MediaSession need a Looper). */
    fun init(context: Context) {
        if (player != null) return
        val appContext = context.applicationContext
        val exo = ExoPlayer.Builder(appContext).build()
        player = exo
        session = MediaSession.Builder(appContext, exo).build()
    }

    /** Loads a tape's tracks in playlist order and starts playback from the top. */
    suspend fun play(context: Context, mixtapeId: Long) {
        val db = CarDatabase.getDatabase(context)
        val mixtape = db.mixtapeDao().getById(mixtapeId) ?: return
        val membership = db.mixtapeTrackDao().forMixtape(mixtapeId)
        val tracks = membership.mapNotNull { db.libraryTrackDao().getById(it.libraryTrackId) }
        if (tracks.isEmpty()) return

        val coverUri = mixtape.coverImagePath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.let { Uri.fromFile(it) }
        val items = tracks.map { toMediaItem(it, mixtape.name, coverUri) }

        init(context)
        val exo = player ?: return
        exo.setMediaItems(items)
        exo.prepare()
        exo.playWhenReady = true

        _activeMixtape.value = mixtape
        PlayRequests.supersede() // a slow voice Spotify play must not land over the tape
        MusicSource.set(Source.MIXTAPE)
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun next() {
        player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    }

    fun previous() {
        player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
    }

    /** Stops playback and hands transport control back to the phone/AVRCP path. */
    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        _activeMixtape.value = null
        MusicSource.set(Source.PHONE)
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    /** Releases the player and session - call once from the foreground service's onDestroy. */
    fun release() {
        session?.release()
        player?.release()
        session = null
        player = null
        _activeMixtape.value = null
    }

    private fun toMediaItem(track: LibraryTrack, mixtapeName: String, coverUri: Uri?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { null })
            .setAlbumTitle(track.album.ifBlank { mixtapeName })
            .apply { if (coverUri != null) setArtworkUri(coverUri) }
            .build()
        return MediaItem.Builder()
            .setUri(Uri.fromFile(File(track.filePath)))
            .setMediaMetadata(metadata)
            .build()
    }
}
