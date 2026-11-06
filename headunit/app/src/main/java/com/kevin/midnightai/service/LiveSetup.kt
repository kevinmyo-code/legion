package com.kevin.midnightai.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Live `setup` message and the 3.8 wire rules, pure so a JVM test can pin them without a
 * socket. Ported 2026-11-06 from the phone (`com.kevin.legion.service.GeminiLiveSession`,
 * commit 29057645 and its later comments) when the head unit moved off
 * `gemini-3.1-flash-live-preview`.
 *
 * **3.8-live was not a drop-in for the phone and is not one here.**
 * - It made NON_BLOCKING the default for function calls: the model keeps talking while a tool
 *   runs and hears the result after it has already spoken. That makes the outcome-verb rule (root
 *   CLAUDE.md section 7) unobeyable, so every declaration is stamped `BLOCKING` here
 *   ([withBlockingBehavior]). The head unit has no async read tools (the phone's ADR 0065 set),
 *   so nothing here is NON_BLOCKING.
 * - It reports speech onset as a root `voiceActivity` ACTIVITY_START, and sends
 *   `inputTranscription` only once, when the user stops ([isActivityStart]).
 * - The phone found three setup fields that break it on this v1beta endpoint: `thinkingConfig`,
 *   `proactivity` (rejected before setupComplete) and `enableAffectiveDialog` (every conversation
 *   closed on the first mic audio). None is sent; `LiveSetupTest` pins them out.
 */
object LiveSetup {

    /** Live model, the phone's (`models/gemini-3.8-live`, stable). Read the object doc before swapping. */
    const val MODEL = "models/gemini-3.8-live"

    /** Setup keys 3.8-live rejects or breaks on (phone, 2026-09-27 and 2026-10-02). Never sent. */
    val FORBIDDEN_SETUP_KEYS = setOf("thinkingConfig", "proactivity", "enableAffectiveDialog")

    /**
     * Stamps `behavior: BLOCKING` on every function declaration, at the one choke point, so a tool
     * added later in any file cannot skip it. Copies, never mutates the caller's array; an
     * explicit `behavior` already set is left alone.
     */
    fun withBlockingBehavior(declarations: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until declarations.length()) {
            val decl = JSONObject(declarations.getJSONObject(i).toString())
            if (!decl.has("behavior")) decl.put("behavior", "BLOCKING")
            out.put(decl)
        }
        return out
    }

    /**
     * True when a root server message is `voiceActivity` of type `ACTIVITY_START`: the earliest
     * user-speech signal 3.8-live gives (phone `FollowUpWindow.isActivityStart`, A25 spike
     * 2026-10-27). ACTIVITY_END is not a cancel and reads false.
     */
    fun isActivityStart(root: JSONObject): Boolean =
        root.optJSONObject("voiceActivity")?.optString("type") == "ACTIVITY_START"

    /** The `setup` message. [GeminiLiveSession] passes its live session state in. */
    @Suppress("LongParameterList")
    fun buildSetupJson(
        systemInstruction: String,
        functionDeclarations: JSONArray,
        voiceName: String,
        vadMode: Boolean,
        subtitles: Boolean,
        silenceDurationMs: Int,
        prefixPaddingMs: Int,
    ): JSONObject {
        val tools = JSONArray().put(JSONObject().put("googleSearch", JSONObject()))
        if (functionDeclarations.length() > 0) {
            tools.put(JSONObject().put("functionDeclarations", withBlockingBehavior(functionDeclarations)))
        }
        val setup = JSONObject().apply {
            put("model", MODEL)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put(
                    "voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)),
                ))
            })
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemInstruction)),
            ))
            put("tools", tools)
            // The driver's input transcript: feeds the crisis backstop and the mic diagnostic
            // log. On 3.8 it arrives once, at the end of the utterance (see isActivityStart).
            put("inputAudioTranscription", JSONObject())
            // Moose's own speech transcript, only with the debug subtitle toggle on.
            if (subtitles) put("outputAudioTranscription", JSONObject())
            // Conversation mode lets the server VAD run turn-taking; speak-only disables it.
            put("realtimeInputConfig", JSONObject().put(
                "automaticActivityDetection",
                JSONObject().apply {
                    put("disabled", !vadMode)
                    if (vadMode) {
                        put("silenceDurationMs", silenceDurationMs)
                        put("prefixPaddingMs", prefixPaddingMs)
                    }
                },
            ))
        }
        return JSONObject().put("setup", setup)
    }
}
