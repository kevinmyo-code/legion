package com.kevin.legion.quiet

import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log

/**
 * The slice of [AudioManager] that [QuietVolumes] needs, so a test can stand a fake in its place.
 * Writes are `setStreamVolume(stream, index, 0)`: flags 0 means no volume panel pops up.
 */
interface QuietAudio {
    fun volume(stream: Int): Int
    fun maxVolume(stream: Int): Int

    /**
     * True when Android reports the stream muted. A muted stream reads back as volume 0 whatever
     * its stored level is (observed on the A25 with Quiet on: `cmd media_session volume --get
     * --stream 3` said 0 while `dumpsys audio` held index 4), so a read taken while this is true
     * says nothing about what the user has set.
     */
    fun isMuted(stream: Int): Boolean
    fun setVolume(stream: Int, index: Int)
    var ringerMode: Int
}

class AndroidQuietAudio(private val manager: AudioManager) : QuietAudio {
    override fun volume(stream: Int): Int = manager.getStreamVolume(stream)
    override fun maxVolume(stream: Int): Int = manager.getStreamMaxVolume(stream)
    override fun isMuted(stream: Int): Boolean = manager.isStreamMute(stream)
    override fun setVolume(stream: Int, index: Int) = manager.setStreamVolume(stream, index, 0)
    override var ringerMode: Int
        get() = manager.ringerMode
        set(value) {
            manager.ringerMode = value
        }
}

/** What Quiet did to media volume: the level it found, and the level it left. */
data class QuietMedia(val before: Int, val applied: Int)

/** Survives a process death, because Quiet can stay on across many of them. */
interface QuietMediaMemory {
    fun load(): QuietMedia?
    fun save(media: QuietMedia)
    fun clear()
}

class PrefsQuietMediaMemory(private val prefs: SharedPreferences) : QuietMediaMemory {
    override fun load(): QuietMedia? =
        if (prefs.contains(KEY_BEFORE) && prefs.contains(KEY_APPLIED)) {
            QuietMedia(prefs.getInt(KEY_BEFORE, 0), prefs.getInt(KEY_APPLIED, 0))
        } else {
            null
        }

    override fun save(media: QuietMedia) {
        prefs.edit().putInt(KEY_BEFORE, media.before).putInt(KEY_APPLIED, media.applied).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_BEFORE).remove(KEY_APPLIED).apply()
    }

    private companion object {
        const val KEY_BEFORE = "media_before"
        const val KEY_APPLIED = "media_applied"
    }
}

/**
 * Quiet's two volume rules (Kevin, 2026-10-11): *"quiet mode > still same but i want media volume
 * to be half. not quiet mode > still same but i want system volume muted (the key presses etc. the
 * wrench icon volume)."*
 *
 * **Media.** Quiet on sets `STREAM_MUSIC` to half of its range and remembers the level it found.
 * Quiet off puts that level back, but only if media still reads the half we set: a different
 * number means the user moved it while Quiet was on, and a volume the user chose is not ours to
 * overwrite. Restoring at all was our default, not Kevin's words.
 *
 * **System** (`STREAM_SYSTEM`, Samsung's "wrench" slider: key presses, touch, lock and charging
 * sounds). Always muted, Quiet on or off, and never raised: the old Quiet remembered it and put it
 * BACK on "off", which is exactly the bug. It is applied on Quiet transitions and on app and
 * service start. Nothing polls, so a slider the user raises by hand stays raised until the next
 * of those.
 *
 * Ring and notification volumes are not touched here; [QuietMode] still owns those.
 *
 * Every write goes through [attempt]: a `SecurityException` (Android throws it when a volume
 * change would toggle Do Not Disturb and LEGION lacks policy access) is logged and swallowed, and a
 * failure on one stream never stops the other. Nothing here may crash a tap or a cold start.
 */
class QuietVolumes(
    private val audio: QuietAudio,
    private val memory: QuietMediaMemory,
    private val log: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) },
) {

    /** Quiet is going on. A second call while on changes nothing about media. */
    fun onQuietOn() {
        if (memory.load() == null) halveMedia()
        muteSystem()
    }

    /** Quiet is going off. */
    fun onQuietOff() {
        val media = memory.load()
        if (media != null) {
            restoreMedia(media)
            memory.clear()
        }
        muteSystem()
    }

    /**
     * Put `STREAM_SYSTEM` at 0. Skips the write when it already reads 0 and is not muted, because
     * on a phone where the UI-sounds stream is the system stream a write of 0 can drag the ringer
     * to vibrate (reasoned from AudioService, not seen on the A25), and a no-op at every app start
     * should not wake that path. If the ringer did move, put it back.
     */
    fun muteSystem() {
        val stream = AudioManager.STREAM_SYSTEM
        if (audio.volume(stream) == 0 && !audio.isMuted(stream)) return
        val ringerBefore = audio.ringerMode
        if (!attempt("mute system volume") { audio.setVolume(stream, 0) }) return
        if (audio.ringerMode != ringerBefore) {
            log("Muting system volume moved the ringer; putting it back", null)
            attempt("restore ringer mode") { audio.ringerMode = ringerBefore }
        }
    }

    private fun halveMedia() {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.maxVolume(stream)
        if (max <= 0) return
        if (audio.isMuted(stream)) {
            // Muted by a Do Not Disturb mode or by hand: the read would be 0 whatever the real
            // level is, so writing half would destroy a level we cannot even remember.
            log("Media is muted, so its level cannot be read; leaving it alone", null)
        } else {
            writeHalf(stream, max)
        }
    }

    private fun writeHalf(stream: Int, max: Int) {
        val before = audio.volume(stream)
        if (attempt("set media to half") { audio.setVolume(stream, QuietState.halfOf(max)) }) {
            // Read back rather than assume: a phone may cap the write (safe-volume limits on
            // headphones), and the "did the user move it" check must compare against what stuck.
            memory.save(QuietMedia(before = before, applied = audio.volume(stream)))
        }
    }

    private fun restoreMedia(media: QuietMedia) {
        val stream = AudioManager.STREAM_MUSIC
        if (audio.isMuted(stream)) {
            // Same unreadable-while-muted rule as halveMedia. The usual cause is Quiet's own Do Not
            // Disturb mode, still lifting a moment after the rule went off.
            log("Media is muted, so whether the user moved it cannot be read; leaving it alone", null)
            return
        }
        val now = audio.volume(stream)
        if (now != media.applied) {
            log("Media is $now (we left ${media.applied}); not restoring over it", null)
            return
        }
        val target = media.before.coerceIn(0, audio.maxVolume(stream))
        attempt("restore media volume") { audio.setVolume(stream, target) }
    }

    private fun attempt(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: SecurityException) {
        log("Quiet could not $what: ${e.message}", e)
        false
    }

    private companion object {
        const val TAG = "QuietVolumes"
    }
}
