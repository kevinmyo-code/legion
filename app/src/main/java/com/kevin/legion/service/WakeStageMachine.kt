package com.kevin.legion.service

/**
 * The decision logic of the two-stage wake detector (ticket 18), with no audio, no native library
 * and no Android in it so it can be unit tested: the engine feeds it one fact per chunk and acts
 * on what it returns.
 *
 * - **Stage 0** is the VAD verdict passed to [onChunk]. While it says silence, [onChunk] never
 *   calls `spot`, so the keyword spotter does no work at all - that is the whole battery argument.
 * - **Stage 1** is `spot`, called only during speech. True means a keyword hit.
 * - **Stage 2** is the caller re-checking the buffered audio with Vosk and reporting back through
 *   [onConfirm]. A hit that stage 2 rejects is logged and never becomes [Verdict.OPEN], so it
 *   never opens Gemini.
 */
class WakeStageMachine(private val log: (String) -> Unit) {

    sealed interface Step {
        /** Silence: nothing ran past the VAD. */
        data object Idle : Step

        /** Speech with no hit. [entered] is true only on the chunk where the VAD flipped on. */
        data class Speech(val entered: Boolean) : Step

        /** Stage 1 fired: the caller must run stage 2 and call [onConfirm]. */
        data object Candidate : Step
    }

    enum class Verdict { OPEN, REJECT }

    var inSpeech: Boolean = false
        private set

    fun onChunk(speech: Boolean, spot: () -> Boolean): Step {
        if (!speech && inSpeech) {
            inSpeech = false
            log("VAD off")
        }
        val entered = speech && !inSpeech
        if (entered) {
            inSpeech = true
            log("VAD on")
        }
        // Silence short-circuits before `spot`: the keyword spotter does no work.
        val hit = speech && spot()
        if (hit) log("KWS hit")
        return when {
            !speech -> Step.Idle
            hit -> Step.Candidate
            else -> Step.Speech(entered)
        }
    }

    fun onConfirm(accepted: Boolean): Verdict =
        if (accepted) {
            log("confirm accept - opening")
            Verdict.OPEN
        } else {
            log("confirm reject - not opening")
            Verdict.REJECT
        }
}
