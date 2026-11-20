package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * One audio-first turn's gate (Kevin, 2026-11-19: "audio first earlier"; switch
 * [VoiceEngineSettings.audioFirst], built OFF). Round 1 hears his audio ([AudioFirstRound]) while the
 * transcript request runs beside it; this gate is where the transcript's verdict lands, and what
 * waits on it:
 * - **writes** ([awaitForWrite]): no tool that changes data or device state runs until the
 *   transcript has landed and passed every check that runs on it today (stop words, [SttGuard], the
 *   crisis check, the language rule). If it never lands, or fails a check: no write, ever
 *   ([NOT_HEARD]). Reads and plain replies may go early.
 * - **the first audio** ([holdSpeech]): held for the verdict up to [GRACE_MS], so the checks that
 *   must see text before anything is said (a stop, a crisis, foreign words) almost always do. Past
 *   the grace a reply or a read's answer may start; a later failing verdict still cuts it off. A
 *   failing verdict inside the grace never lets audio start (the turn is ended by its caller).
 * - **round 2** ([awaitForRound]): his words go back to the model as text; never the audio twice.
 *
 * Grace, from the 11-19 drive (`stt_cloud_ms`, n=149: median 1,387 ms; 40% over 1.5 s, 19% over
 * 2 s, 9% over 3 s): the audio round's first audio is about 1.1 s (round 1) + 0.8 s (voice) after his
 * last word, so with 500 ms of grace the transcript is in before the first audio for every request
 * up to about 2.4 s, all but roughly one turn in seven (reasoned from those numbers).
 */
internal class AudioFirstGate(private val graceMs: Long = GRACE_MS) {
    private val verdict = CompletableDeferred<String?>()

    /** The transcript landed and passed every check: [text] is his words. */
    fun pass(text: String) {
        verdict.complete(text)
    }

    /** The transcript never landed, or failed a check: nothing waiting on it may act. */
    fun fail() {
        verdict.complete(null)
    }

    val settled: Boolean get() = verdict.isCompleted

    /** His words once checked, or null: never landed or not believed (then no write runs). */
    suspend fun awaitForWrite(): String? = verdict.await()

    /** Round 2 and later: his words as text (null: [NOT_HEARD_NOTE] goes in their place). */
    suspend fun awaitForRound(): String? = verdict.await()

    /**
     * Before the turn's first audio. True when the grace ran out first (a grace hit: the reply
     * starts without the verdict). A failing verdict before then never returns: the caller cancels.
     */
    suspend fun holdSpeech(): Boolean {
        val got = withTimeoutOrNull(graceMs) { verdict.await() ?: FAILED }
        if (got === FAILED) awaitCancellation()
        return got == null
    }

    companion object {
        const val GRACE_MS = 500L
        private const val FAILED = "\u0000failed"

        /** What a write says when his words never came through: nothing was changed. */
        val NOT_HEARD: JSONObject
            get() = JSONObject().put("success", false).put("not_done", true).put(
                "message",
                "Not done: his words did not come through clearly, so nothing was changed. Ask him to say it again.",
            )

        /** In history and round 2 in place of words that never came through (never logged as his words). */
        const val NOT_HEARD_NOTE = "(His words did not come through; nothing was changed.)"

        /** The audio round applies: the switch on, the cloud voice, no words carried in, his audio kept. */
        fun applies(switchOn: Boolean, cloudVoice: Boolean, carryBlank: Boolean, audioKept: Boolean): Boolean =
            switchOn && cloudVoice && carryBlank && audioKept

        /** Next to no voice: never sent to the model ([MissedLine.SILENT_BELOW_MS], local measure). */
        fun silent(voicedMs: Long): Boolean = voicedMs < MissedLine.SILENT_BELOW_MS
    }
}
