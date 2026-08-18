package com.kevin.legion.ai

import android.content.Context

/**
 * Placeholder for "has first-run setup finished" - the real onboarding flow
 * lived in Midnight AI's `ui/` package, which is an intentional clean-slate
 * rebuild here (see README.md). This exists only so [com.kevin.legion.service.AriaForegroundService]
 * (session prewarming, proactive gating) has something to check against
 * before the real onboarding UI is rebuilt. Currently proxies "has a Gemini
 * key" - replace with the real flag once onboarding exists.
 *
 * **Reads [CompanionProfile.geminiKey] directly, NOT [GeminiKeyProvider.hasKey].**
 * [GeminiKeyProvider.key] deliberately falls back to `BuildConfig.GEMINI_API_KEY`
 * so Kevin's own convenience dev builds (a real key baked in via
 * `local.properties`, per that object's own doc comment) don't need a key
 * re-entered by hand every install. That fallback is correct for "what key do
 * we actually call Gemini with", but wrong for "did the driver finish setup":
 * a stranger who clones, sideloads, and hasn't entered a key yet is BYO-key by
 * design (`-Pnokey` ships an empty `BuildConfig` field), so on a real
 * distributed build the two checks agree - but on Kevin's own dev build they
 * diverged, and this object silently reported onboarding complete (so
 * `ProactiveBus.speakIfAllowed` stopped gating) the moment `CompanionProfile`'s
 * on-file key was cleared, even with none saved. Caught by
 * `ProactiveBusTest > blocked while onboarding is incomplete` failing only
 * under a plain `./gradlew testDebugUnitTest` (no `-Pnokey`), i.e. only when a
 * real dev key is baked in - see that test's own setup comment.
 */
object OnboardingState {
    fun isComplete(context: Context): Boolean = CompanionProfile.geminiKey(context).isNotBlank()
}
