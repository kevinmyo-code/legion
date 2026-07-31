package com.kevin.legion.service

import com.kevin.legion.ai.GeminiKeyProvider

/**
 * Resolves how the next Gemini Live socket should authenticate. Per CLAUDE.md
 * sec 2 (2026-07-16 rewrite: two tiers, no trial, no subscription, no broker),
 * the only path is the driver's own Gemini key ([ConnectionMode.Direct]). Null
 * means voice isn't available right now (no key saved) - callers must not
 * connect and should route the driver to the key-entry / unlock screen instead.
 *
 * Shared by [LiveSessionController] (the app's main voice loop) and the
 * onboarding manager's per-field voice capture, so both pick the same auth path
 * and neither hardcodes the raw key. No user-facing notice here; callers gate on
 * the fast synchronous [com.kevin.legion.billing.EntitlementManager.canStartVoice]
 * pre-check first.
 */
internal fun resolveLiveConnectionMode(): ConnectionMode? {
    if (GeminiKeyProvider.hasKey()) return ConnectionMode.Direct(GeminiKeyProvider.key())
    return null
}
