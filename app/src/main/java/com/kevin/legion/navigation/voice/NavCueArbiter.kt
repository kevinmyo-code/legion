package com.kevin.legion.navigation.voice

import com.kevin.legion.navigation.audit.CueOutcome

/**
 * Everything [NavCueArbiter] touches outside itself, so the decision logic is a plain JVM class a
 * test drives with a fake (mapbox-nav ticket 05 and 11). The real one is `NavCueSpeaker`.
 *
 * All calls are on the main thread.
 */
interface CueEnvironment {
    /** Turn cues muted (tool or tile). Silences cues only, never the assistant. */
    val muted: Boolean

    /** Whether a speech engine is ready. False means a cue can be shown but not said. */
    val canSpeak: Boolean

    /**
     * Takes the assistant's voice and the microphone for the length of a cue: the assistant's
     * playback pauses (if it is mid-reply) and nothing the mic hears is forwarded as the user, so the
     * cue is never transcribed. Always paired with exactly one [release].
     */
    fun hold()

    /** Gives both back: the assistant's paused reply resumes, the mic is forwarded again. */
    fun release()

    /**
     * Starts saying [text]. Returns false when the engine refused it (nothing is speaking then, and
     * [NavCueArbiter.onSpeechDone] will NOT be called for it). Otherwise [NavCueArbiter.onSpeechDone]
     * is called exactly once, when it finished or was stopped.
     */
    fun speak(text: String): Boolean

    /** Stops whatever is being said. Its [NavCueArbiter.onSpeechDone] may still arrive and is ignored. */
    fun stopSpeaking()

    /** A cue that was not said, and why. The banner still shows the turn; this logs and tells the screen. */
    fun unspoken(text: String, why: String)

    /** A cue dropped on purpose ([why], e.g. "muted"), reported with no text so a phone run can see it. */
    fun dropped(why: String) = Unit
}

/**
 * Decides what happens to each spoken turn cue (ticket 05): **a cue wins**. The assistant's
 * playback pauses for it and resumes after, and the mic is gated for its duration so it is never
 * transcribed as the user. Pure: no Android, no clock.
 *
 * - **Muted:** the cue is dropped (the user asked for silence; not an error, not shown, but
 *   [CueEnvironment.dropped] hears of it so it can be logged).
 * - **No speech engine:** the cue is not said anywhere and [CueEnvironment.unspoken] hears about it.
 * - **A cue while another is speaking:** it waits, and the assistant stays held across both so it
 *   never resumes between them. **Only the newest waiting cue is kept**: "turn left in 300 metres"
 *   still queued when the turn itself arrives is already stale, and saying it late is worse than not.
 * - **A cue while the user is speaking:** said at once; the mic is gated, so the part of the
 *   sentence spoken over it is lost. A late cue is worse than a repeated sentence.
 *
 * One [hold] and one [release] bracket a run of cues, however many are in it.
 *
 * Every cue's fate goes to [outcome] (NAV_AUDIT counts, never the text): spoken, muted, no engine,
 * refused by the engine, superseded by a newer cue while waiting, or cancelled by a mute or trip end.
 */
class NavCueArbiter(
    private val env: CueEnvironment,
    private val outcome: (CueOutcome) -> Unit = {},
) {
    private var holding = false
    private var speaking = false
    private var waiting: String? = null

    /** True while a cue is being said or is waiting. */
    val busy: Boolean get() = holding

    fun onCue(text: String) {
        when {
            text.isBlank() -> Unit
            // Said in words so a phone run can see a cue was dropped on purpose; never the text (a street name).
            env.muted -> {
                env.dropped("muted")
                outcome(CueOutcome.MUTED)
            }
            !env.canSpeak -> {
                env.unspoken(text, "no speech engine")
                outcome(CueOutcome.NO_ENGINE)
            }
            speaking -> {
                if (waiting != null) outcome(CueOutcome.SUPERSEDED)
                waiting = text
            }
            else -> begin(text)
        }
    }

    /** The engine finished (or was stopped on) the cue it was saying. */
    fun onSpeechDone() {
        if (!speaking) return
        speaking = false
        val next = waiting
        waiting = null
        if (next != null && env.muted) outcome(CueOutcome.MUTED)
        if (next != null && !env.muted) begin(next) else finish()
    }

    /** Drops everything: a mute, a trip that ended. Ends the hold; the engine's late done is ignored. */
    fun cancel() {
        if (!holding) return
        if (waiting != null) outcome(CueOutcome.CANCELLED)
        waiting = null
        // A cue cut off mid-sentence was already counted spoken; only a never-started one is cancelled.
        if (speaking) env.stopSpeaking()
        speaking = false
        finish()
    }

    private fun begin(text: String) {
        if (!holding) {
            holding = true
            env.hold()
        }
        if (env.speak(text)) {
            speaking = true
            outcome(CueOutcome.SPOKEN)
        } else {
            outcome(CueOutcome.REFUSED)
            env.unspoken(text, "the speech engine refused it")
            finish()
        }
    }

    private fun finish() {
        if (!holding) return
        holding = false
        env.release()
    }
}
