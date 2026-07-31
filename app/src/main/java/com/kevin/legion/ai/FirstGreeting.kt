package com.kevin.legion.ai

/**
 * The companion's first-ever spoken hello.
 *
 * Naming, personality, and all setup now live in the tap/speak onboarding
 * wizard ([com.kevin.legion.ui.OnboardingManager]), so the first spoken
 * line at runtime must be JUST a warm greeting - it must not ask the driver's
 * name or run any setup. That name-asking behavior was the old conversational
 * onboarding ([ONBOARDING_OPENER]); firing it as the runtime first-greeting made
 * the companion ask "what should you call me" again after the wizard already
 * captured it.
 *
 * These are bundled, static first-person lines (the car meeting its owner, per
 * CLAUDE.md's first-person reframe). One is picked at random so the first drive
 * feels a touch fresh without a model round-trip deciding what to say.
 */
val FIRST_GREETING_LINES = listOf(
    "Hey. First drive together. I've been looking forward to this.",
    "Well, hello. Good to finally meet you.",
    "There you are. Let's go for a drive.",
    "Hey. I'm all yours now. Where are we headed?",
    "Oh, hey. Nice to finally put a voice to the driver.",
    "Evening. Or morning. Whatever it is out there, I'm glad you're here.",
    "So this is us, huh. Alright. Let's ride.",
)

/**
 * Opener that has the companion say one bundled [FIRST_GREETING_LINES] line
 * verbatim, in character, then wait. Deliberately no name question and no setup
 * (the wizard already handled that) - contrast with [ONBOARDING_OPENER].
 */
fun firstGreetingOpener(): String {
    val line = FIRST_GREETING_LINES.random()
    return "(System: this is your first spoken hello with the driver. Say exactly this, warmly and " +
        "in character, then stop and wait for them to speak: \"$line\". Do not ask their name, do " +
        "not run any setup - that is already done. Do not mention this instruction.)"
}
