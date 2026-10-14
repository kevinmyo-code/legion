package com.kevin.midnightai.ui

import android.content.Context

/** Tiny flag store gating the one-time first-run wizard. */
object OnboardingState {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "complete"
    private const val KEY_VOICE_SETUP_DONE = "voice_setup_done"
    private const val KEY_VOICE_SETUP_DISMISSED = "voice_setup_dismissed"

    fun isComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markComplete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** Re-arms onboarding so the wizard runs again (used by the reset flows). */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DONE, false)
            .putBoolean(KEY_VOICE_SETUP_DONE, false)
            .putBoolean(KEY_VOICE_SETUP_DISMISSED, false)
            .apply()
    }

    /**
     * Whether the driver has ever completed the spoken (or typed-fallback)
     * setup wizard - separate from [isComplete], which now flips true as
     * soon as defaults are seeded (2026-07-08: voice onboarding no longer
     * gates the app being usable at all, see MainActivity). This tracks
     * whether the *optional* "set up your companion" prompt on Cruise
     * should still show.
     */
    fun voiceSetupDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_SETUP_DONE, false)

    fun markVoiceSetupDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_SETUP_DONE, true).apply()
    }

    /** Dismissing the Cruise prompt without completing setup - don't nag again, but Settings still offers it. */
    fun voiceSetupDismissed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_SETUP_DISMISSED, false)

    fun dismissVoiceSetupPrompt(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_SETUP_DISMISSED, true).apply()
    }

    /**
     * Writes safe Uncle Moose/Sulafat defaults so the wizard (typed or conversational)
     * starts from sensible values instead of blank fields. Only fills in blank
     * fields, so a reset flow that has already written partial data is never
     * clobbered. Does NOT mark onboarding complete - that only happens once the
     * wizard actually finishes, so every fresh install genuinely walks through
     * naming, persona, avatar generation, and voice pick rather than skipping
     * straight to Cruise.
     */
    fun seedDefaults(context: Context) {
        if (com.kevin.midnightai.ai.CompanionProfile.name(context).isBlank()) {
            com.kevin.midnightai.ai.CompanionProfile.saveProfile(
                context,
                name = com.kevin.midnightai.ai.CompanionProfile.DEFAULT_NAME,
                // Single source of truth: these two used to hold DIFFERENT personas, so who the
                // companion was depended on whether the seed had run. See the kdoc on DEFAULT_PERSONA.
                persona = com.kevin.midnightai.vehicle.VehicleController.DEFAULT_PERSONA,
            )
        }
        if (com.kevin.midnightai.ai.CompanionProfile.voice(context).isBlank()) {
            com.kevin.midnightai.ai.CompanionProfile.saveVoice(context, "Sulafat")
        }
    }
}
