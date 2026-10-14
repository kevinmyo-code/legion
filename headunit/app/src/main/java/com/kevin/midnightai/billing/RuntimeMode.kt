package com.kevin.midnightai.billing

/**
 * Effective AI-capability tier for the current install.
 *
 * Per CLAUDE.md sec 2 (2026-07-16 pricing rewrite), this is two tiers only:
 * free install -> $10 one-time unlock -> the driver's own Gemini key. No
 * trial, no subscription, no backend, no broker.
 *
 * This governs Gemini-billed actions (Live voice, AI image gen, sub-agent
 * diagnosis) AND, since 2026-07-16, **OBD history** - which is the one gate
 * that is NOT about serving cost. Per sec 2 the free tier sees its last drive
 * in full and the $10 unlock adds the retained history, trends and
 * drive-to-drive comparison. That is a deliberate value-capture decision, not
 * a cost boundary: the data is local, on the driver's device, and free to
 * serve.
 */
enum class RuntimeMode {
    /** Driver has entered their own Gemini key (or a dev BuildConfig key). Full capability, direct to Google. */
    BYO_KEY,

    /** No key saved. The base app stays fully usable; Gemini-billed actions and retained OBD history pause. */
    AI_PAUSED,
}
