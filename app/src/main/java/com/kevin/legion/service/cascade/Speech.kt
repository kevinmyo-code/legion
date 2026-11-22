package com.kevin.legion.service.cascade

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Speech-to-text for the cascade, behind an interface so a phone-local model (Moonshine through
 * sherpa-onnx, design doc "Reused vs new") can sit beside the laptop one later. Version 1 has one
 * implementation, [LaptopVoiceConnection].
 *
 * One [SttUtterance] per user turn: the mic audio goes up as it is captured ([SttUtterance.audio],
 * 16 kHz PCM16 mono), [SttUtterance.end] is the phone's own end-of-turn decision, and
 * [SttUtterance.result] waits for the text.
 */
interface SpeechToText {
    /**
     * True when the engine has its own end-of-turn signal (Kyutai STT's endpointing). Kevin,
     * 2026-11-10: "since kyutai has end of turn built in lets use that first and ditch smart turn".
     * The phone then streams until [begin]'s `onServiceEnd` fires (the final text is then already
     * waiting in [SttUtterance.result]), with only a silence cap of its own.
     */
    val endsTurns: Boolean

    /** [onServiceEnd] fires when the engine itself ended this utterance (contract 4b). */
    fun begin(onServiceEnd: () -> Unit = {}): SttUtterance
}

interface SttUtterance {
    val id: Int
    fun audio(pcm: ByteArray, offset: Int, length: Int)
    fun end()
    fun cancel()

    /** Waits at most [timeoutMs] after this call for the final text. Never throws. */
    suspend fun result(timeoutMs: Long): SttResult

    /**
     * The final text if it is already in, without waiting or asking for it; null otherwise. The laptop
     * ends a turn with its final already sent (contract 4b), so at that end of turn the words are known.
     */
    fun finalText(): String? = null
}

sealed interface SttResult {
    /**
     * [text] may be empty (nothing intelligible). [decodeMs] is the service's own figure. [voicedMs]:
     * how much of the audio stood above its own quiet floor ([SttGuard.voicedMs]); null when the
     * audio was not here to measure.
     */
    data class Text(
        val text: String,
        val lang: String?,
        val audioMs: Long?,
        val decodeMs: Long?,
        val voicedMs: Long? = null,
    ) : SttResult

    /** Nothing usable: [reason] is a short token for the log (`timeout`, `busy`, `dropped`, ...). */
    data class Failed(val reason: String) : SttResult
}

/**
 * Text-to-speech for the cascade. [speak] queues one sentence; its audio arrives on
 * [SpeechClip.frames] as PCM16 mono at [sampleRate], in order, and the channel closes when the
 * clip is done, cancelled or failed ([SpeechClip.outcome] says which).
 */
interface TextToSpeech {
    val sampleRate: Int

    /** True when [openStream] works: text can go in as the model writes it (Kyutai TTS). */
    val streamsText: Boolean

    /** One whole sentence or clause. */
    fun speak(text: String): SpeechClip

    /** One utterance fed as it is written ([StreamingSpeechClip.append]); only when [streamsText]. */
    fun openStream(): StreamingSpeechClip

    /**
     * True when [speak] would play [text] at once from audio already in hand (a kept scripted line).
     * A voice that cannot tell says true; the cloud voice answers from its kept lines.
     */
    fun inHand(text: String): Boolean = true

    /** Fetches the audio of those [lines] not yet kept, for next time; the number asked for. */
    fun warm(lines: Collection<String>): Int = 0

    /** Holds background fetching ([warm]) while [held]; a voice whose warm cannot delay speech ignores it. */
    fun holdWarm(held: Boolean) = Unit

    /**
     * Another render ([take] > 0) of a kept line, kept apart from the first ([Backchannel.TAKES]). A
     * voice that keeps one render answers with its only one.
     */
    fun speakTake(text: String, take: Int): SpeechClip = speak(text)

    fun inHandTake(text: String, take: Int): Boolean = inHand(text)

    fun warmTakes(lines: Collection<String>, take: Int): Int = if (take == 0) warm(lines) else 0

    /**
     * True when the voice performs expression tags ([TtsTags]) instead of reading them aloud: Gemini
     * TTS does; any other voice gets every tag stripped.
     */
    val performsTags: Boolean get() = false
}

interface StreamingSpeechClip : SpeechClip {
    /** Appends text exactly as written, spaces included. */
    fun append(text: String)

    /** No more text; the clip ends once it has spoken what it has. */
    fun finish()
}

interface SpeechClip {
    val id: Int
    val text: String
    val frames: ReceiveChannel<ByteArray>

    /** Stops it now (barge-in). Frames still in flight are dropped, not played. */
    fun cancel()

    /** Waits for the clip's end. Never throws. */
    suspend fun outcome(): TtsOutcome
}

sealed interface TtsOutcome {
    data class Done(val audioMs: Long?, val firstAudioMs: Long?) : TtsOutcome
    data object Cancelled : TtsOutcome
    data class Failed(val reason: String) : TtsOutcome
}
