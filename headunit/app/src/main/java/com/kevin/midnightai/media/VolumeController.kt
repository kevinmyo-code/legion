package com.kevin.midnightai.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import com.kevin.midnightai.diag.InputProbe
import com.kevin.midnightai.diag.ProbeFormat
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
 * tell the driver where it landed. [by] names who asked (screen, key, voice, panel) for the
 * volume probe's drive-log line (HU-1 T2).
 */
object VolumeController {

    /** Separates our own writes from hardware-key changes; see [VolumeSync]. */
    internal val tracker = ExternalVolumeTracker()

    /** Current media volume as a 0-100 percentage, for a live on-screen meter. */
    fun current(context: Context): Int = percent(am(context))

    /** Raw STREAM_MUSIC index, for [VolumeSync]. */
    internal fun index(context: Context): Int = am(context).getStreamVolume(AudioManager.STREAM_MUSIC)

    internal fun maxIndex(context: Context): Int = am(context).getStreamMaxVolume(AudioManager.STREAM_MUSIC)

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
        noteOwnWrite(am, "sync", "reassert", target)
    }

    private fun noteOwnWrite(am: AudioManager, by: String, what: String, before: Int) {
        val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        tracker.onOwnWrite(after, SystemClock.uptimeMillis())
        if (ProbeFormat.shouldLogWrite(by)) {
            InputProbe.volume(ProbeFormat.writeLine(by, what, before, after, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
        }
    }

    fun raise(context: Context, steps: Int = 1, by: String = "app"): Int =
        nudge(context, AudioManager.ADJUST_RAISE, steps, by)

    fun lower(context: Context, steps: Int = 1, by: String = "app"): Int =
        nudge(context, AudioManager.ADJUST_LOWER, steps, by)

    /** Jumps to [percent] (0-100) of max media volume. */
    fun setPercent(context: Context, percent: Int, by: String = "app"): Int {
        val am = am(context)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) / 100f * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        noteOwnWrite(am, by, "set $percent%", before)
        return percent(am)
    }

    /**
     * The Volume check panel's re-apply buttons (HU-1 T2 probe): each re-sends the CURRENT index
     * a different way, so a drive can show which call the amp actually follows. The fix waits for
     * that drive's log. [what] is shown under the button and written to the log.
     */
    enum class Reapply(val label: String, val what: String) {
        STEP_AWAY_AND_BACK(
            "STEP AWAY + BACK",
            "adjustStreamVolume(STREAM_MUSIC, one notch away, FLAG_SHOW_UI), then setStreamVolume(STREAM_MUSIC, " +
                "same index, FLAG_SHOW_UI). Today's automatic re-assert.",
        ),
        ADJUST_SAME("ADJUST SAME", "adjustStreamVolume(STREAM_MUSIC, ADJUST_SAME, FLAG_SHOW_UI)"),
        SET_SAME_SHOW_UI("SET SAME + PANEL", "setStreamVolume(STREAM_MUSIC, same index, FLAG_SHOW_UI)"),
        SET_SAME_NO_FLAG("SET SAME, NO FLAG", "setStreamVolume(STREAM_MUSIC, same index, no flags)"),
        RAISE_THEN_LOWER(
            "UP 1 + DOWN 1",
            "adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE, FLAG_SHOW_UI), then ADJUST_LOWER. What the on-screen keys call.",
        ),
    }

    /** Runs one [Reapply] strategy on the current index; recorded as our own write so VolumeSync ignores its echo. */
    fun reapply(context: Context, strategy: Reapply): Int {
        val am = am(context)
        val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val show = AudioManager.FLAG_SHOW_UI
        when (strategy) {
            Reapply.STEP_AWAY_AND_BACK -> {
                val away = if (index > 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, away, show)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, index, show)
            }
            Reapply.ADJUST_SAME -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, show)
            Reapply.SET_SAME_SHOW_UI -> am.setStreamVolume(AudioManager.STREAM_MUSIC, index, show)
            Reapply.SET_SAME_NO_FLAG -> am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            Reapply.RAISE_THEN_LOWER -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, show)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, show)
            }
        }
        noteOwnWrite(am, "panel", "reapply ${strategy.name}", index)
        return percent(am)
    }

    /** Raw index, max and mute, for the Volume check panel. */
    fun readout(context: Context): String {
        val am = am(context)
        val muted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am.isStreamMute(AudioManager.STREAM_MUSIC)
        return "STREAM_MUSIC index ${am.getStreamVolume(AudioManager.STREAM_MUSIC)} of " +
            "${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" + (if (muted) ", muted" else "")
    }

    /** Mutes or unmutes media playback; returns the resulting percentage. */
    fun mute(context: Context, muted: Boolean, by: String = "app"): Int {
        val am = am(context)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                AudioManager.FLAG_SHOW_UI,
            )
        } else if (muted) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        }
        noteOwnWrite(am, by, if (muted) "mute" else "unmute", before)
        return percent(am)
    }

    private fun nudge(context: Context, direction: Int, steps: Int, by: String): Int {
        val am = am(context)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // FLAG_SHOW_UI is required on many head units / OEM ROMs for a programmatic
        // STREAM_MUSIC change to actually take effect (without it the call is a
        // silent no-op — the on-screen meter never moved). It also surfaces the
        // system volume panel as confirmation.
        repeat(steps.coerceIn(1, 10)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
        noteOwnWrite(am, by, if (direction == AudioManager.ADJUST_RAISE) "raise" else "lower", before)
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
