package com.kevin.legion.service

/**
 * What the live assistant session offers a spoken navigation turn cue (mapbox-nav tickets 05 and
 * 11): "a cue wins". The session pauses its own playback if it is mid-reply and stops forwarding
 * microphone audio to the model, so the cue is neither talked over nor transcribed as the user.
 *
 * Mute never reaches here: it silences cues only, so the assistant is never held for a cue that is
 * not going to be said.
 */
interface AssistantCueHold {
    /** Pause playback (no flush: the reply resumes where it stopped) and stop forwarding mic audio. */
    fun holdForCue()

    /** Resume playback; mic audio is forwarded again once what resumed has played out. */
    fun releaseAfterCue()
}

/**
 * The one place the session in use registers itself, so the navigation cue speaker can reach it
 * without the session knowing navigation exists. At most one live session is current; a newer one
 * replaces an older, and only the current one's [unregister] clears it.
 */
object AssistantCueBridge {
    @Volatile
    var current: AssistantCueHold? = null
        private set

    fun register(hold: AssistantCueHold) {
        current = hold
    }

    fun unregister(hold: AssistantCueHold) {
        if (current === hold) current = null
    }
}

/**
 * The two flags and the generation counter behind [AssistantCueHold], split out of
 * `GeminiLiveSession` so the sequence is a plain JVM test: a hold pauses playback and gates the mic;
 * a release resumes playback but keeps the mic gated until what resumed has played out
 * ([drained]); and a newer hold arriving before that drain is never undone by the older release.
 */
class CueHoldState {
    @Volatile
    var playbackPaused = false
        private set

    /** True from a hold until the reply resumed by its release has drained. Mic audio is dropped while true. */
    @Volatile
    var micHeld = false
        private set

    private val generation = java.util.concurrent.atomic.AtomicInteger()

    fun hold() {
        generation.incrementAndGet()
        micHeld = true
        playbackPaused = true
    }

    /** Playback may resume now. Returns the token to hand to [drained] once it has played out. */
    fun release(): Int {
        playbackPaused = false
        return generation.get()
    }

    /** The resumed audio finished. Opens the mic only if no newer hold has been taken since [release]. */
    fun drained(token: Int) {
        if (generation.get() == token) micHeld = false
    }

    /** The session is gone: nothing is held. */
    fun reset() {
        playbackPaused = false
        micHeld = false
    }
}
