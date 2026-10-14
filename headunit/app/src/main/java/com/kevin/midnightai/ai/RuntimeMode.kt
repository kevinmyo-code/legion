package com.kevin.midnightai.ai

/**
 * Whether the AI layer can run on this install: it can exactly when a Gemini key is saved.
 * Governs Gemini-billed actions (Live voice, sub-agent diagnosis, recap prose). Nothing else
 * is gated by it; OBD history in particular is open to every install (the paywall that used to
 * hide it was removed 2026-10-14 with the commercial model).
 */
enum class RuntimeMode {
    /** A Gemini key is saved (the driver's own, or a dev BuildConfig key). Full capability, direct to Google. */
    BYO_KEY,

    /** No key saved. The base app stays fully usable; Gemini-billed actions pause. */
    AI_PAUSED,
}
