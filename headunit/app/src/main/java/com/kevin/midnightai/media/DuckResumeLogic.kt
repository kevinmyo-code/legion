package com.kevin.midnightai.media

/**
 * Whether the post-voice-turn `play()` nudge may be sent (voice-pipeline audit B1, 2026-10-15).
 *
 * The nudge exists because some music apps pause for the transient focus loss and never resume.
 * Its premise is that nothing about the music changed during the turn except our ducking. That
 * premise was only checked at the START of the turn (was something playing?), so a turn that said
 * "pause" resumed the music the driver had just stopped, and a turn that switched to Spotify
 * resumed the phone stream over it. The decision now compares what the speakers looked like at
 * duck time with what they look like at restore time; any change means the driver or a tool
 * chose something, and a choice outranks a belt-and-braces resume.
 *
 * Pure so every branch is a JVM test. The "a tool paused it" case is not visible in either field
 * (a plain pause moves neither the source nor a play request), so the session clears its own
 * `wasPlaying` when a music tool runs; that clear is what feeds [wasPlayingAtDuck] = false here.
 */
object DuckResumeLogic {

    fun shouldNudge(
        wasPlayingAtDuck: Boolean,
        sourceAtDuck: Source,
        sourceNow: Source,
        playGenerationAtDuck: Long,
        playGenerationNow: Long,
    ): Boolean =
        wasPlayingAtDuck &&
            sourceAtDuck == sourceNow &&
            playGenerationAtDuck == playGenerationNow

    /** Why [shouldNudge] said no, for the breadcrumb; null when it said yes. */
    fun skipReason(
        wasPlayingAtDuck: Boolean,
        sourceAtDuck: Source,
        sourceNow: Source,
        playGenerationAtDuck: Long,
        playGenerationNow: Long,
    ): String? = when {
        !wasPlayingAtDuck -> "nothing was playing, or a music tool changed it this turn"
        sourceAtDuck != sourceNow -> "source changed $sourceAtDuck -> $sourceNow"
        playGenerationAtDuck != playGenerationNow -> "a newer play request or switch superseded it"
        else -> null
    }
}
