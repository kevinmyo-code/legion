package com.kevin.midnightai.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * Adjusts the head unit's media (STREAM_MUSIC) volume directly - a deterministic,
 * on-device action that needs no cloud round-trip once the intent is known. This
 * is the same stream Spotify plays on, so "louder / quieter / mute" affect the
 * music the driver hears, NOT Zero's own assistant voice (that rides
 * USAGE_ASSISTANT and is ducked separately; see
 * [com.kevin.midnightai.service.GeminiLiveSession]).
 *
 * Every call returns the resulting volume as a 0-100 percentage so the caller can
 * tell the driver where it landed.
 */
object VolumeController {

    /** Separates our own writes from hardware-key changes; see [VolumeSync]. */
    internal val tracker = ExternalVolumeTracker()

    /** Current media volume as a 0-100 percentage, for a live on-screen meter. */
    fun current(context: Context): Int = percent(am(context))

    /** Raw STREAM_MUSIC index, for [VolumeSync]. */
    internal fun index(context: Context): Int = am(context).getStreamVolume(AudioManager.STREAM_MUSIC)

    /**
     * Re-applies the CURRENT index after an external change (hardware / steering keys).
     *
     * The guess (2026-10-13 field report): the head unit's keys move the index but the
     * amp only follows the app's own `FLAG_SHOW_UI` AudioManager calls. Re-setting the
     * same index is a no-op inside AudioService (it skips unchanged indexes), so this
     * steps one notch away and then sets the index back with the same call [setPercent]
     * uses - the stream ends exactly where the key left it, but through the path that
     * is known to reach the speakers. Skipped while muted so it never unmutes.
     */
    internal fun reassert(context: Context) {
        val am = am(context)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am.isStreamMute(AudioManager.STREAM_MUSIC)) return
        val target = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val away = if (target > 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, away, AudioManager.FLAG_SHOW_UI)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        noteOwnWrite(am)
    }

    private fun noteOwnWrite(am: AudioManager) =
        tracker.onOwnWrite(am.getStreamVolume(AudioManager.STREAM_MUSIC), SystemClock.uptimeMillis())

    fun raise(context: Context, steps: Int = 1): Int = nudge(context, AudioManager.ADJUST_RAISE, steps)

    fun lower(context: Context, steps: Int = 1): Int = nudge(context, AudioManager.ADJUST_LOWER, steps)

    /** Jumps to [percent] (0-100) of max media volume. */
    fun setPercent(context: Context, percent: Int): Int {
        val am = am(context)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) / 100f * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        noteOwnWrite(am)
        return percent(am)
    }

    /** Mutes or unmutes media playback; returns the resulting percentage. */
    fun mute(context: Context, muted: Boolean): Int {
        val am = am(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                AudioManager.FLAG_SHOW_UI,
            )
        } else if (muted) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        }
        noteOwnWrite(am)
        return percent(am)
    }

    private fun nudge(context: Context, direction: Int, steps: Int): Int {
        val am = am(context)
        // FLAG_SHOW_UI is required on many head units / OEM ROMs for a programmatic
        // STREAM_MUSIC change to actually take effect (without it the call is a
        // silent no-op — the on-screen meter never moved). It also surfaces the
        // system volume panel as confirmation.
        repeat(steps.coerceIn(1, 10)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
        noteOwnWrite(am)
        return percent(am)
    }

    private fun percent(am: AudioManager): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    private fun am(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
