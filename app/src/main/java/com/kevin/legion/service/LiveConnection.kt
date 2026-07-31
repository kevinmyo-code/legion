package com.kevin.legion.service

import com.kevin.legion.ai.GeminiKeyProvider

/**
 * Resolves how the next Gemini Live socket should authenticate. No tiers, no
 * trial, no subscription, no broker (commercial model retired in the
 * 2026-07-31 pivot) - the only path is the driver's own Gemini key
 * ([ConnectionMode.Direct]). Null means voice isn't available right now (no
 * key saved) - callers must not connect and should route the driver to the
 * key-entry screen instead.
 *
 * Shared by [LiveSessionController] (the app's main voice loop) and the
 * onboarding manager's per-field voice capture, so both pick the same auth
 * path and neither hardcodes the raw key.
 */
internal fun resolveLiveConnectionMode(): ConnectionMode? {
    if (GeminiKeyProvider.hasKey()) return ConnectionMode.Direct(GeminiKeyProvider.key())
    return null
}
