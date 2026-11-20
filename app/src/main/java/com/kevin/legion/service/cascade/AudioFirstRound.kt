package com.kevin.legion.service.cascade

import com.kevin.legion.service.ChatTurn
import org.json.JSONArray
import org.json.JSONObject

/**
 * The audio-first round's request (Kevin, 2026-11-19; wired in [CascadeSession] behind
 * [VoiceEngineSettings.audioFirst], ON at ship): his turn goes to the text model as audio, so one
 * call does both understanding and the choice of a read, instead of speech-to-text and then the
 * model. The transcript is made beside it. The rules (writes wait and are re-chosen from the
 * words, round 2 on text, the checks on the transcript) are [AudioFirstGate]'s and
 * [GeminiTextLoop]'s; this file only builds the turn and swaps the audio for the words.
 * Pure: no Android.
 */
@Suppress("NestedBlockDepth") // one walk over a turn's parts
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

    /** [turn] (a user turn [contents] built) with its audio removed and [AUDIO_NOTE] replaced by [words]. */
    fun withTranscript(turn: JSONObject, words: String): JSONObject {
        val parts = turn.getJSONArray("parts")
        val kept = JSONArray()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.has("inlineData")) continue
            val text = p.optString("text", "")
            kept.put(if (p.has("text")) JSONObject(p.toString()).put("text", text.replace(AUDIO_NOTE, words)) else p)
        }
        return JSONObject(turn.toString()).put("parts", kept)
    }
}
