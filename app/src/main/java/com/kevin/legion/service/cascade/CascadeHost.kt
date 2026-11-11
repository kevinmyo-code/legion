package com.kevin.legion.service.cascade

import com.kevin.legion.service.Phase
import org.json.JSONObject

/**
 * What a [CascadeSession] needs from [com.kevin.legion.service.LiveSessionController], and nothing
 * more: the strip's phase, notices, the ONE tool runner (ADR 0035: voice, typed chat and the
 * cascade call the same code), the panel, the crisis teardown, and the door back to Live.
 *
 * Every call may come from any thread; the controller's implementation posts to its main scope.
 */
interface CascadeHost {
    fun phase(phase: Phase, status: String)

    fun notice(text: String)

    /** The controller's bounded tool runner plus its per-call audit row and panel line. */
    suspend fun runTool(
        name: String,
        args: JSONObject,
        readThrough: Boolean,
        userText: String,
        turnSeq: Long,
    ): JSONObject

    /** One finished turn for the panel. */
    fun turnTranscript(heard: String, said: String)

    /** Genuine distress in what he said: the same teardown a crisis heard on Live gets. */
    fun crisis()

    /** The short tone a conversation ends with (Live's own). */
    fun closingTone()

    /**
     * The session is fully closed (mic, audio mode, mic claim released): open Gemini Live for this
     * conversation. [opener] is [Handover.opener]'s line, or null for Live's ordinary start.
     */
    fun handOverToLive(fromWakeWord: Boolean, opener: String?)

    /** The session ended on its own terms and released everything. */
    fun closed(reason: String)
}
