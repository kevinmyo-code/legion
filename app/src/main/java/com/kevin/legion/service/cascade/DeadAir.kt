package com.kevin.legion.service.cascade

/**
 * No dead air between his end of speech and the answer's first audio (2026-11-21; report
 * voice-front-back-split-1120 section 10 item 1). Before this, a wait line ([WaitFillers]) stopped for
 * the whole turn as soon as the model's first TEXT was in, though that sentence's audio was still a
 * voice request away (about 0.8 s on the cloud voice, 1 s or more on the laptop's), and a tool round
 * after it could sit silent. Tonight's phone log: "Mm." at 0.6 s, then about 3.9 s of nothing.
 *
 * The rule, keyed on the answer's AUDIO:
 * - Its first audio in hand ([State.answerAudioReady]): no wait line starts. The answer wins.
 * - No answer clip asked of the voice yet ([State.answerAskedAtMs] null, a tool round, text the
 *   speaker held back): a wait line may start (the timing is [WaitFillers]'s, 1.8 s then 2.5 s, cap 6).
 * - A clip asked, audio not in yet: a line starts only if its own length ends it by the expected first
 *   audio plus [MAX_HOLD_MS]. Expected = asked + [expectedFirstAudioMs]. Once the audio is more than
 *   [STALE_MS] past that, the estimate is wrong (a slow or failing voice): lines may start again.
 * - A line playing when the answer's audio is in ([cutPlaying]): it finishes if at most [FINISH_MAX_MS]
 *   of it is left, else it is cut (its feed stops; at most [PacedClip.LEAD_MS] more of it plays).
 *   So the answer waits at most about 300 ms behind a wait line, never a whole line.
 * - Never: a closing conversation, a stopped or replaced turn, a turn that ends the conversation
 *   (goodbye), the greeting still held. A short code line already playing is answer audio (above).
 *
 * Pure: numbers and flags in, a verdict out.
 */
internal object DeadAir {
    /** Cloud voice: request to first audio, the drive's measure (report: 0.78 s). */
    const val CLOUD_FIRST_AUDIO_MS = 800L

    /** Laptop voice: 0.7 s plain, 1.0 to 1.6 s streamed (report laptop-voice-fillers-1120). */
    const val LAPTOP_FIRST_AUDIO_MS = 1_000L

    /** This session's own measure is used inside these bounds. */
    const val MIN_FIRST_AUDIO_MS = 300L
    const val MAX_FIRST_AUDIO_MS = 2_500L

    /** A line started after the answer was asked for may end at most this long after its expected audio. */
    const val MAX_HOLD_MS = 300L

    /** A playing line with at most this much left finishes; more, and the answer's audio cuts it. */
    const val FINISH_MAX_MS = 300L

    /**
     * Answer audio this long past its expected time: the estimate is wrong, lines may start again (the
     * cut rule still bounds what one costs the answer). With the cloud's 0.8 s estimate, a clip asked
     * 1.6 s after his end of speech whose audio is slow gets a line by 3.1 s, not after a silence.
     */
    const val STALE_MS = 700L

    enum class Block(val token: String) {
        ANSWER_AUDIO("answer_audio"), CLOSING("closing"), STOPPED("stopped"), GOODBYE("goodbye"),
        GREETING("greeting"), NO_FIT("no_fit"),
    }

    /** One turn's wait, at [nowMs]. */
    data class State(
        val nowMs: Long,
        val answerAudioReady: Boolean,
        val answerAskedAtMs: Long?,
        val expectedFirstAudioMs: Long,
        val closing: Boolean = false,
        val stopped: Boolean = false,
        val goodbye: Boolean = false,
        val greetingHeld: Boolean = false,
    )

    /** Why a wait line of [clipMs] may not start now, or null: it may. */
    fun waitLine(s: State, clipMs: Long): Block? = when {
        s.closing -> Block.CLOSING
        s.stopped -> Block.STOPPED
        s.goodbye -> Block.GOODBYE
        s.greetingHeld -> Block.GREETING
        s.answerAudioReady -> Block.ANSWER_AUDIO
        !fits(s, clipMs) -> Block.NO_FIT
        else -> null
    }

    /**
     * NO_FIT ends nothing: the next poll asks again (the expected audio may go stale). Only these end
     * the turn's wait lines for good.
     */
    fun endsWait(block: Block): Boolean = block != Block.NO_FIT

    private fun fits(s: State, clipMs: Long): Boolean {
        val expected = s.answerAskedAtMs?.let { it + s.expectedFirstAudioMs } ?: return true
        return s.nowMs > expected + STALE_MS || s.nowMs + clipMs <= expected + MAX_HOLD_MS
    }

    /** A playing wait line with [remainingMs] left, the answer's audio in hand: cut it now. */
    fun cutPlaying(answerAudioReady: Boolean, remainingMs: Long): Boolean =
        answerAudioReady && remainingMs > FINISH_MAX_MS

    /** The expected request-to-first-audio: this session's last measure, else the voice's default. */
    fun expectedFirstAudioMs(cloud: Boolean, measuredMs: Long?): Long =
        measuredMs?.coerceIn(MIN_FIRST_AUDIO_MS, MAX_FIRST_AUDIO_MS)
            ?: if (cloud) CLOUD_FIRST_AUDIO_MS else LAPTOP_FIRST_AUDIO_MS
}
