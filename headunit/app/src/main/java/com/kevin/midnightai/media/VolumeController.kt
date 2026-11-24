package com.kevin.midnightai.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
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

    /** STREAM_MUSIC is muted (API 23+; always false below). */
    fun isMuted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am(context).isStreamMute(AudioManager.STREAM_MUSIC)

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

    /** Where the stream landed, and what to tell the driver if it did not move ([VolumeFeedback]). */
    data class Nudge(val percent: Int, val words: String?)

    fun raise(context: Context, steps: Int = 1, by: String = "app"): Nudge =
        nudge(context, up = true, steps = steps, by = by, nudger = AndroidVolumeNudger(context))

    fun lower(context: Context, steps: Int = 1, by: String = "app"): Nudge =
        nudge(context, up = false, steps = steps, by = by, nudger = AndroidVolumeNudger(context))

    /** The same press with the Android calls faked, so a test can assert WHICH call it made. */
    @VisibleForTesting
    internal fun nudgeWith(context: Context, up: Boolean, nudger: VolumeNudger, by: String = "test"): Nudge =
        nudge(context, up = up, steps = 1, by = by, nudger = nudger)

    /**
     * One press of one [VolumePath] from the Volume check panel: a real notch, logged like any
     * other press, so Kevin can hear each way in turn and then pick the one that works.
     */
    fun probe(context: Context, path: VolumePath, up: Boolean): String {
        val am = am(context)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val refused = AndroidVolumeNudger(context).send(path, up)
        noteOwnWrite(am, "panel", VolumeFeedback.writeWhat(up, path, refused), before)
        return VolumeFeedback.probeWords(path, refused)
    }

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

    /**
     * Runs one [Reapply] strategy on the current index; recorded as our own write so VolumeSync
     * ignores its echo. Returns the resulting percentage, or null when the stream is muted: then
     * nothing is sent, because a raise/lower step would unmute it (review L3, 2026-11-20).
     */
    fun reapply(context: Context, strategy: Reapply): Int? {
        val am = am(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am.isStreamMute(AudioManager.STREAM_MUSIC)) return null
        val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
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
            // At the top a raise does nothing and the lower would leave it one notch down: go the
            // other way round there, so it ends where it started.
            Reapply.RAISE_THEN_LOWER -> if (index >= max) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, show)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, show)
            } else {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, show)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, show)
            }
        }
        noteOwnWrite(am, "panel", "reapply ${strategy.name}", index)
        return percent(am)
    }

    /**
     * Raw index, max, mute AND whether this output's volume is fixed, for the Volume check panel.
     * The fixed-output line is the one the panel was missing: on such an output every stream write
     * is a no-op, so all five [Reapply] strategies would read as "nothing happened" with no reason
     * given (see [VolumeFeedback]).
     */
    fun readout(context: Context): String {
        val am = am(context)
        val muted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am.isStreamMute(AudioManager.STREAM_MUSIC)
        return VolumeFeedback.readout(
            index = am.getStreamVolume(AudioManager.STREAM_MUSIC),
            max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            muted = muted,
            volumeFixed = isVolumeFixed(am),
        )
    }

    /** True when this unit's output cannot have its volume changed through AudioManager at all. */
    private fun isVolumeFixed(am: AudioManager): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && runCatching { am.isVolumeFixed }.getOrDefault(false)

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

    /**
     * One press, down the [VolumePath] in use. Until 2026-11-23 this always called
     * `adjustStreamVolume(STREAM_MUSIC, ..., FLAG_SHOW_UI)`, which is the call Kevin watched move
     * the system's slider without moving the sound ([VolumePath]); the default is now the call the
     * framework itself makes for a hardware volume key, and his pick in the Volume check panel wins
     * over that.
     *
     * The fallback can only see an index, never the amp ([VolumePathLogic.shouldFallBack]): it
     * exists so a ROM that refuses the newer call still changes the volume, and it proves nothing
     * about loudness.
     */
    private fun nudge(context: Context, up: Boolean, steps: Int, by: String, nudger: VolumeNudger): Nudge {
        val am = am(context)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumeFixed = isVolumeFixed(am)
        val notches = steps.coerceIn(1, 10)
        var path = VolumePathStore.path(context)
        var refused = send(am, nudger, path, up, notches, by, before)
        var after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val fallback = VolumePathLogic.fallbackFor(path)
        if (fallback != null && VolumePathLogic.shouldFallBack(
                path = path,
                chosenByDriver = VolumePathStore.chosenByDriver(context),
                threw = refused != null,
                moved = after != before,
                atEnd = if (up) max > 0 && before >= max else before <= 0,
                volumeFixed = volumeFixed,
            )
        ) {
            path = fallback
            refused = send(am, nudger, path, up, notches, by, after)
            after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        // A press that moved nothing is reported in words instead of looking like it worked
        // (2026-11-23). Said once per press, by whoever asked: the screen toasts it, voice speaks it.
        val words = VolumeFeedback.pressWords(
            before = before,
            after = after,
            max = max,
            up = up,
            volumeFixed = volumeFixed,
            readsBack = path.readsBackAtOnce,
            refused = refused,
        )
        return Nudge(percent(am), words)
    }

    /** [notches] presses of one path, as one drive-log line naming the path and any refusal. */
    @Suppress("LongParameterList")
    private fun send(
        am: AudioManager,
        nudger: VolumeNudger,
        path: VolumePath,
        up: Boolean,
        notches: Int,
        by: String,
        before: Int,
    ): String? {
        var refused: String? = null
        repeat(notches) { nudger.send(path, up)?.let { reason -> refused = reason } }
        noteOwnWrite(am, by, VolumeFeedback.writeWhat(up, path, refused), before)
        return refused
    }

    private fun percent(am: AudioManager): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    private fun am(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
