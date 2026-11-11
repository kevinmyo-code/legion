package com.kevin.legion.service

/**
 * Where the per-conversation context ([com.kevin.legion.ai.AriaBrain.buildLiveContext]: clock,
 * place, memory continuity, car status and the rest) rides when a conversation opens with no
 * generation (voice cost audit 2026-11-10, F2).
 *
 * It used to be the prefix of the GREETING_PROMPT / WAKE_ACK_PROMPT text turn, which is exactly
 * the billed generation F2 removes. It now goes at the end of the setup's system instruction for
 * that connect. Chosen over a `clientContent` turn with `turnComplete: false` because the system
 * instruction is certain to reach the model on gemini-3.8-live (it is how every rule already
 * arrives, and `buildBaseInstruction` already carries per-connect text such as the date), while a
 * non-completing client turn on this model is unverified on the wire (the head unit's
 * `sendContext` says the same). The cost is the same either way: it is input on every generation.
 * The wording is the old prefix's, so the model reads it as it always did.
 */
internal object LiveOpenContext {
    fun systemInstruction(base: String, live: String): String =
        if (live.isBlank()) base else "$base\n\n(Current context, use naturally if relevant:\n$live)"
}
