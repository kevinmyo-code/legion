package com.kevin.midnightai.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.kevin.midnightai.service.MediaNotificationListener

/**
 * The one impure step of a volume press: tell Android to move the media volume one notch, a chosen
 * [VolumePath]. Behind an interface so a press can be tested for the CALL it makes rather than for
 * an index that every path moves (which is the whole bug: see [VolumePath]).
 */
internal interface VolumeNudger {
    /** Null when the call was made, else a short reason for the probe line. Never throws. */
    fun send(path: VolumePath, up: Boolean): String?
}

/** The real calls. One `when` branch per [VolumePath] and nothing else, so the paths stay readable. */
internal class AndroidVolumeNudger(context: Context) : VolumeNudger {
    private val app = context.applicationContext

    @Suppress("DEPRECATION")
    override fun send(path: VolumePath, up: Boolean): String? = runCatching {
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val show = AudioManager.FLAG_SHOW_UI
        when (path) {
            VolumePath.SUGGESTED_STREAM -> {
                am.adjustSuggestedStreamVolume(direction, AudioManager.STREAM_MUSIC, show)
                null
            }
            VolumePath.STREAM -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, show)
                null
            }
            VolumePath.MASTER -> {
                am.adjustVolume(direction, show)
                null
            }
            VolumePath.MEDIA_SESSION -> sessionAdjust(direction, show)
            VolumePath.MEDIA_KEY -> {
                val code = if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                null
            }
        }
    }.getOrElse { it.javaClass.simpleName }

    /**
     * The player holding the speakers, by its own volume provider. Needs the notification access
     * [MediaNotificationListener] exists for; without it there is no session list at all, which is
     * a different answer from "no player", so both are said.
     */
    private fun sessionAdjust(direction: Int, flags: Int): String? {
        val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = runCatching {
            manager.getActiveSessions(ComponentName(app, MediaNotificationListener::class.java))
        }.getOrElse { return "no notification access" }
        val target = sessions.firstOrNull { playing(it) } ?: sessions.firstOrNull() ?: return "no player"
        target.adjustVolume(direction, flags)
        return null
    }

    private fun playing(controller: MediaController): Boolean =
        controller.playbackState?.state == PlaybackState.STATE_PLAYING
}
