package com.kevin.midnightai.media

/**
 * What to tell the driver after a volume press that did not move the stream (Kevin, 2026-11-23:
 * "the volume button still doesnt work").
 *
 * Why this exists rather than a third attempt at the write. Two fixes already assume the same
 * premise - that `adjustStreamVolume(STREAM_MUSIC, ..., FLAG_SHOW_UI)` reaches this unit's amp:
 * [VolumeController.nudge]'s FLAG_SHOW_UI (2026-10-13) and [VolumeController.reassert] (its own doc
 * calls it "the guess"). HU-1 T2 then built the Volume check panel's five re-apply strategies and
 * said in writing that the fix waits for a drive's log. Guessing a fourth call is not the work.
 *
 * What IS missing is that the button is silent when it fails. Android documents
 * `AudioManager.isVolumeFixed` as true on a device whose output cannot have its volume changed, and
 * on such an output EVERY stream write is a no-op by design - all five probe strategies included.
 * That is this symptom exactly, and the app said nothing about it, so a dead button and a
 * fixed-volume output looked the same on screen and in the log.
 *
 * Pure, so it is JVM-tested; only the car can say which branch it takes.
 */
object VolumeFeedback {

    const val FIXED_OUTPUT =
        "This unit's audio output has a fixed volume, so Android's media volume cannot change it. " +
            "Use the head unit's own volume knob."

    const val NO_MOVEMENT =
        "The volume did not move. Settings, System, Developer tools, Volume check has the probes."

    const val REFUSED =
        "This unit refused that volume call. Settings, System, Developer tools, Volume check has the other ways."

    /**
     * [before] and [after] are the STREAM_MUSIC index either side of the press, [max] its top.
     * [up] is the direction asked for. [volumeFixed] is `AudioManager.isVolumeFixed`. [refused] is
     * what Android threw, or null when the call was made. [readsBack] is
     * [VolumePath.readsBackAtOnce]: a press handed to another process cannot be judged from the
     * index at all, so it claims nothing either way. Null = say nothing: it moved, it was handed
     * on, or it is already at the end the driver pushed towards, which is ordinary.
     */
    @Suppress("LongParameterList")
    fun pressWords(
        before: Int,
        after: Int,
        max: Int,
        up: Boolean,
        volumeFixed: Boolean,
        readsBack: Boolean = true,
        refused: String? = null,
    ): String? = when {
        refused != null -> REFUSED
        !readsBack -> null
        after != before -> null
        // Fixed BEFORE the end-of-range tests, not after: AudioService pins a fixed-volume
        // output's stream at max, so "already at the top" is exactly how this case looks and the
        // end-of-range branch would swallow the one thing this function exists to say.
        volumeFixed -> FIXED_OUTPUT
        up && max > 0 && before >= max -> null
        !up && before <= 0 -> null
        else -> NO_MOVEMENT
    }

    /**
     * One press's drive-log subject: which call carried it and whether Android took it. The path is
     * named because from 2026-11-23 a press no longer has only one way to travel ([VolumePath]).
     */
    fun writeWhat(up: Boolean, path: VolumePath, refused: String?): String =
        (if (up) "raise" else "lower") + " via ${path.name}" + (refused?.let { " refused=$it" } ?: "")

    /** What the Volume check panel says after one TRY of [path]. Pure, so the words are tested. */
    fun probeWords(path: VolumePath, refused: String?): String = when {
        refused != null -> "${path.label}: this unit refused the call ($refused)."
        !path.readsBackAtOnce ->
            "${path.label}: sent. Nothing can be read back, so only your ears say whether it landed."
        else -> "${path.label}: sent. Did the sound change?"
    }

    /** The Volume check panel's readout and its drive-log line. [max] 0 means the stream has no range. */
    fun readout(index: Int, max: Int, muted: Boolean, volumeFixed: Boolean): String =
        "STREAM_MUSIC index $index of $max" +
            (if (muted) ", muted" else "") +
            (if (volumeFixed) ", output volume is FIXED (every stream write is a no-op)" else "")
}
