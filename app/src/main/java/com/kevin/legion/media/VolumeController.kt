package com.kevin.legion.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlin.math.roundToInt

/**
 * Adjusts the head unit's media (STREAM_MUSIC) volume directly - a deterministic,
 * on-device action that needs no cloud round-trip once the intent is known. This
 * is the same stream Spotify plays on, so "louder / quieter / mute" affect the
 * music the driver hears, NOT Zero's own assistant voice (that rides
 * USAGE_ASSISTANT and is ducked separately; see
 * [com.kevin.legion.service.GeminiLiveSession]).
 *
 * Every call returns the resulting volume as a 0-100 percentage so the caller can
 * tell the driver where it landed.
 */
object VolumeController {

    /** Current media volume as a 0-100 percentage, for a live on-screen meter. */
    fun current(context: Context): Int = percent(am(context))

    fun raise(context: Context, steps: Int = 1): Int = nudge(context, AudioManager.ADJUST_RAISE, steps)

    fun lower(context: Context, steps: Int = 1): Int = nudge(context, AudioManager.ADJUST_LOWER, steps)

    /** Jumps to [percent] (0-100) of max media volume. */
    fun setPercent(context: Context, percent: Int): Int {
        val am = am(context)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) / 100f * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
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
