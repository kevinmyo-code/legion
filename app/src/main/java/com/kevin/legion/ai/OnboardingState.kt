package com.kevin.legion.ai

import android.content.Context

/**
 * Placeholder for "has first-run setup finished" - the real onboarding flow
 * lived in Midnight AI's `ui/` package, which is an intentional clean-slate
 * rebuild here (see README.md). This exists only so [com.kevin.legion.service.AriaForegroundService]
 * (session prewarming, proactive gating) has something to check against
 * before the real onboarding UI is rebuilt. Currently proxies "has a Gemini
 * key" - replace with the real flag once onboarding exists.
 */
object OnboardingState {
    fun isComplete(context: Context): Boolean = GeminiKeyProvider.hasKey()
}
