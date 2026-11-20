package com.kevin.legion.service.cascade

import com.kevin.legion.service.ChatTurn
import org.json.JSONArray
import org.json.JSONObject

/**
 * An audio-first round 1 (Kevin, 2026-11-19, speed plan item 4b; OFF, not wired into the session
 * yet): his turn goes to the text model as audio, so one call does both understanding and the tool
 * choice, instead of speech-to-text (median 1.1 s on the 11-19 drive) and then the model. The
 * transcript is still made beside it, for the record and the panel.
 *
 * This file is the request only, and `AudioFirstEvalRun` (laptop, made-up clips) runs the same
 * utterances both ways and reports tool-choice agreement and latency. What the session must do
 * before a switch can turn it on, consumer by consumer of the transcript in the turn path (each
 * keys on his words, so in audio-first each either waits for the transcript or runs on the model's
 * structured output):
 * - the stop and sleep phrase ([CascadeSession] `endsConversation`, [com.kevin.legion.service.WakePhrases]):
 *   waits for the transcript; a stop cancels the round and nothing it chose runs;
 * - [SttGuard] (noise, foreign script): waits; a drop cancels the round;
 * - the crisis check ([com.kevin.legion.ai.CrisisDetector]): waits; a hit cancels the round;
 * - the language switch ([SpokenLanguages]): waits; a refusal cancels the round;
 * - [LoneWordWriteGuard] and `answersSpokenAsk`: read `record.heard` when a write is called, so every
 *   tool call of the round is held until the transcript is in (the speculative draft's `hold` gate,
 *   [SpeculativeAnswer], is the existing shape for this);
 * - [com.kevin.legion.vehicle.ClearCodesVoiceGate]: reads his words at the clear call: same hold;
 * - the read-through mark (ADR 0090) keys on tools, not words: unchanged;
 * - the turn's merge carry and history: the transcript, once in, replaces [AUDIO_NOTE] in history,
 *   so later turns are text as today;
 * - the reply's speech may start before the transcript only if every guard above has passed; until
 *   then it is held (so the gain is max(speech-to-text, round 1) instead of their sum).
 * Pure: no Android.
 */
internal object AudioFirstRound {
    /** Stands in for his words in the text part of the turn; the audio part carries them. */
    const val AUDIO_NOTE = "(His words this turn are in the attached audio. Answer what he said.)"

    private const val INPUT_RATE = 16_000

    /**
     * [history], then his turn as [pcm16k] (16 kHz PCM16 mono) with [AUDIO_NOTE]; the preamble rides
     * as a text turn's does.
     */
    fun contents(history: List<ChatTurn>, pcm16k: ByteArray, preamble: String): JSONArray {
        val contents = CascadePrompt.contents(history, AUDIO_NOTE, preamble)
        val last = contents.getJSONObject(contents.length() - 1)
        last.getJSONArray("parts").put(
            JSONObject().put(
                "inlineData",
                JSONObject().put("mimeType", "audio/wav")
                    .put("data", CloudSpeechWire.base64(CloudSpeechWire.wav(pcm16k, INPUT_RATE))),
            ),
        )
        return contents
    }
}
