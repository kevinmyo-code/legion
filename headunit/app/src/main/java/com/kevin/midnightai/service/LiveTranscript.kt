package com.kevin.midnightai.service

import org.json.JSONObject

/**
 * Reads the two transcripts out of one Live `serverContent` message (2026-11-09). Pulled out of
 * [GeminiLiveSession.handleServerContent] unchanged so a JVM test can feed a real message shape
 * through to the conversation spool (`LiveTranscriptSpoolTest`): the driver's words come from
 * `inputTranscription` (requested by `inputAudioTranscription` in [LiveSetup]), the companion's
 * from `outputTranscription` (`outputAudioTranscription`). The phone reads the same two fields
 * (`app/.../service/GeminiLiveSession.kt`, `handleServerContent`).
 */
internal object LiveTranscript {
    /** The driver's transcript chunk and its `finished` flag, or null when this message has none. */
    fun input(content: JSONObject): Pair<String, Boolean>? {
        val t = content.optJSONObject("inputTranscription") ?: return null
        val text = t.optString("text")
        return if (text.isEmpty()) null else text to t.optBoolean("finished")
    }

    /** The companion's own speech chunk, or null when this message has none. */
    fun output(content: JSONObject): String? =
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
}
