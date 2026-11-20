package com.kevin.legion.service.cascade

/**
 * What happens when the cloud transcript did not come back (Kevin, 2026-11-19, speed plan item 4a).
 * On the 11-19 drive every "Didn't catch that" was a network timeout over 1.6 to 10.8 s of his voice,
 * never silence; one was said into his next sentence, right after the assistant's own question.
 *
 * - [onFailure]: the turn's own audio decides, measured on the phone ([SttGuard.voicedMs]). Next to no
 *   voice is silence: nothing is said, the follow-up rules close a conversation nobody speaks in.
 *   Voiced audio is transcribed once more before anything is said. After that, the line.
 * - [hold]: the line is never said over his ongoing speech (his next turn is already coming), nor
 *   within [AFTER_REPLY_MS] of a reply's end (it would land on the assistant's own last words); the
 *   floor stays his, silently.
 *
 * Pure: no clock, no Android.
 */
internal object MissedLine {
    /** Less voice than this in the whole turn is silence, not a sentence. */
    const val SILENT_BELOW_MS = 150L

    /** A reply that ended this recently: no missed line on top of it. */
    const val AFTER_REPLY_MS = 1_500L

    enum class OnFailure { SILENT, RETRY, SAY }

    enum class Hold(val token: String) { NONE("none"), HE_IS_SPEAKING("speaking"), RIGHT_AFTER_REPLY("after_reply") }

    /** [voicedMs] null: no audio was kept to measure or send again, so the line as before. */
    fun onFailure(voicedMs: Long?, retried: Boolean): OnFailure = when {
        voicedMs == null -> OnFailure.SAY
        voicedMs < SILENT_BELOW_MS -> OnFailure.SILENT
        !retried -> OnFailure.RETRY
        else -> OnFailure.SAY
    }

    /** [msSinceReplyEnd] null: no reply has ended in this conversation yet. */
    fun hold(speakingNow: Boolean, msSinceReplyEnd: Long?): Hold = when {
        speakingNow -> Hold.HE_IS_SPEAKING
        msSinceReplyEnd != null && msSinceReplyEnd < AFTER_REPLY_MS -> Hold.RIGHT_AFTER_REPLY
        else -> Hold.NONE
    }
}
