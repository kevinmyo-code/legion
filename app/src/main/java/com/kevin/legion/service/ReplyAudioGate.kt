package com.kevin.legion.service

/**
 * The two decisions that stand between a model audio chunk and the speaker, split out of
 * `GeminiLiveSession` so a typed reply (web-assistant ticket 09: shown, never spoken) and a
 * navigation turn cue ([CueHoldState]: the cue wins, the reply waits) can be walked together on the
 * JVM. Integration of both branches, 2026-10-05.
 *
 * The two never share a flag, and that is the property: a cue's release resumes a PAUSED track and
 * reopens the mic gate; it never touches the typed-reply mute, and a muted typed reply never reaches
 * the track, so there is nothing for a cue's end to resume. A typed reply stays silent until the
 * turn completes, the person speaks, or push-to-talk begins - never because a cue ended.
 */
object ReplyAudioGate {
    /**
     * True when a chunk must be thrown away rather than queued: its turn was superseded by a flush
     * (barge-in, interrupt, crisis), or it belongs to a typed turn whose reply is shown, not spoken.
     * Deliberately not a function of the cue: a held cue only delays PLAYING a queued chunk.
     */
    fun drops(superseded: Boolean, typedTurnInFlight: Boolean): Boolean = superseded || typedTurnInFlight

    /** True when a queued chunk may start the track now; false while a cue holds playback paused. */
    fun mayStartPlayback(cue: CueHoldState): Boolean = !cue.playbackPaused
}
