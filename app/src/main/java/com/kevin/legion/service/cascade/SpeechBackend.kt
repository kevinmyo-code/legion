package com.kevin.legion.service.cascade

import java.io.ByteArrayOutputStream

/**
 * The speech half of a cascade conversation: where his words become text and the reply becomes
 * sound. Everything else in [CascadeSession] (wake word, mic, turn taking, barge-in, the Gemini text
 * loop, tools, context cache, history) is the same whichever backend runs.
 *
 * Two backends (Kevin, 2026-11-15: "lets change the fallback to the text+gemini voice, so that we
 * save costs from the sts"): the laptop voice service ([LaptopSpeech]) first, then Gemini in the
 * cloud ([CloudSpeech]: Gemini speech-to-text, the Puck voice). Gemini Live is used only when the
 * cloud voice itself cannot start or fails.
 */
internal interface SpeechBackend {
    val kind: Kind
    val stt: SpeechToText
    val tts: TextToSpeech

    /** How long the final text may take after the phone ends a turn. */
    val sttFinalTimeoutMs: Long

    /** How long one clip's first audio may take. */
    val ttsFirstAudioTimeoutMs: Long

    /** Releases the backend; every wait still pending ends at once. Idempotent. */
    fun close(reason: String)

    /** [token] is what the logs and `session_start model=` carry. */
    enum class Kind(val token: String) {
        LAPTOP("laptop"),
        CLOUD("cloud"),
    }
}

/** The laptop voice service (Kyutai STT and TTS over Tailscale) as a [SpeechBackend]. */
internal class LaptopSpeech(val connection: LaptopVoiceConnection) : SpeechBackend {
    override val kind = SpeechBackend.Kind.LAPTOP
    override val stt: SpeechToText get() = connection
    override val tts: TextToSpeech get() = connection
    override val sttFinalTimeoutMs = CascadeTimings.STT_FINAL_TIMEOUT_MS
    override val ttsFirstAudioTimeoutMs = CascadeTimings.TTS_FIRST_AUDIO_TIMEOUT_MS

    override fun close(reason: String) = connection.close(reason)
}

/**
 * One user turn's utterance, tagged with the backend it went to, so a failure is charged to the
 * backend that produced it (a laptop failure that arrives after the swap to the cloud must not read
 * as a cloud failure and escalate to Live).
 *
 * With [keepAudio] the phone also keeps the audio it sent (capped at [capBytes]), so words sent to a
 * laptop that then fails are transcribed again by the cloud instead of being lost.
 */
internal class TurnUtterance(
    private val inner: SttUtterance,
    val backend: SpeechBackend,
    private val keepAudio: Boolean,
    private val capBytes: Int = KEEP_CAP_BYTES,
) : SttUtterance by inner {
    private val kept = ByteArrayOutputStream()

    /** Cloud drafts started for this utterance (each is a paid transcription and model call). */
    private val cloudDrafts = java.util.concurrent.atomic.AtomicInteger(0)

    /** Another cloud draft may start: fewer than [SpeculationRules.MAX_CLOUD_DRAFTS] have. */
    val cloudDraftBudgetLeft: Boolean get() = cloudDrafts.get() < SpeculationRules.MAX_CLOUD_DRAFTS

    /** Counts one cloud draft against the cap; false (and not counted) when the cap is spent. */
    fun spendCloudDraft(): Boolean {
        while (true) {
            val n = cloudDrafts.get()
            if (n >= SpeculationRules.MAX_CLOUD_DRAFTS) return false
            if (cloudDrafts.compareAndSet(n, n + 1)) return true
        }
    }

    override fun audio(pcm: ByteArray, offset: Int, length: Int) {
        inner.audio(pcm, offset, length)
        if (!keepAudio || length <= 0) return
        synchronized(kept) {
            val room = capBytes - kept.size()
            if (room > 0) kept.write(pcm, offset, minOf(length, room))
        }
    }

    /** The audio sent so far, or null when this utterance keeps none. */
    fun keptAudio(): ByteArray? = if (keepAudio) synchronized(kept) { kept.toByteArray() } else null

    companion object {
        /** 30 s of 16 kHz PCM16 mono: past the 28 s wall-clock utterance cap. */
        const val KEEP_CAP_BYTES = 30 * 32_000
    }
}

/**
 * A line the model is asked for at the start of a cascade conversation instead of a greeting, or
 * folded into one already open ([CascadeSession.say]): Brief me, a proactive line, a ring
 * announcement. These went to Live until the cascade replaced it (Kevin, 2026-11-15).
 *
 * [prompt] is an instruction to the model ("(System: ...)"), never shown as his words. [listen]
 * false is a speak-only line: no mic, and the session closes once it is said. [readThrough] marks
 * the turn as carrying outside text (ADR 0090). [userInitiated] puts a failure on screen.
 */
data class CascadeOpener(
    val prompt: String,
    val listen: Boolean,
    val readThrough: Boolean = false,
    val userInitiated: Boolean = true,
    /**
     * A listening check-in: after the line, a question gets the cue, the listening mark and the
     * reply window, and silence gets a scripted closer; a statement just closes. Only on an opener
     * that listens; a line folded into an open conversation ([CascadeSession.say]) ignores it.
     */
    val checkIn: com.kevin.legion.service.checkin.CheckInMark? = null,
)
