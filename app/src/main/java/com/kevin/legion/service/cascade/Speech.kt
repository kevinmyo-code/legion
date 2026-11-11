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
     * The phone then streams until [begin]'s `onEndpoint` fires, with only a silence cap of its own.
     */
    val endsTurns: Boolean

    /** [onEndpoint] gets the engine's end-of-turn signal for this utterance, with its probability. */
    fun begin(onEndpoint: (prob: Float) -> Unit = {}): SttUtterance
}

interface SttUtterance {
    val id: Int
    fun audio(pcm: ByteArray, offset: Int, length: Int)
    fun end()
    fun cancel()

    /** Waits at most [timeoutMs] after this call for the final text. Never throws. */
    suspend fun result(timeoutMs: Long): SttResult
}

sealed interface SttResult {
    /** [text] may be empty (nothing intelligible). [decodeMs] is the service's own figure. */
    data class Text(val text: String, val lang: String?, val audioMs: Long?, val decodeMs: Long?) : SttResult

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
