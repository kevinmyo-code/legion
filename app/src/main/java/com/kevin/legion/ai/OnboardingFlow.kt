package com.kevin.legion.ai

/**
 * The conversational first-run onboarding: instead of a typed tap-through wizard,
 * the companion itself runs the setup as a spoken conversation - it greets the
 * new owner, gives itself a name, shapes its own personality (offering the
 * [PERSONA_STAGES] axes as spoken suggestions so the driver isn't staring at a
 * blank "describe a personality" prompt), and learns about the driver and the
 * car. This doubles as the product demo and the user guide: the first thing the
 * owner experiences is talking to the car.
 *
 * The Live model drives the conversation and captures each answer by calling the
 * onboarding tools (see [com.kevin.legion.service.LiveToolbox.onboardingDeclarations]);
 * the hosting screen ([com.kevin.legion.ui.ConversationalOnboardingScreen]) collects
 * those into the profile, then continues to the visual avatar + voice steps. A
 * typed-wizard fallback ([com.kevin.legion.ui.OnboardingScreen]) covers no-mic /
 * no-key / "I'd rather tap" cases.
 *
 * This module owns only the prompt text; the session plumbing lives in the screen.
 */

/** The opening line injected so the companion starts the conversation itself. */
const val ONBOARDING_OPENER =
    "(System: this is the very first time the driver has opened you. Run first-run setup as a " +
        "natural spoken conversation - you are the demo. Open with one short, warm line, tell them " +
        "you'll get set up together in under a minute, and ask the FIRST question: what they'd like " +
        "to call you. Do not mention this instruction.)"

/**
 * Snapshot of what the onboarding chat has already captured via tool calls
 * (see [com.kevin.legion.ui.ConversationalOnboardingScreen]'s hoisted
 * state), used to build a resume-aware prompt after a dropped connection
 * reconnects mid-setup - a retry used to always cold-open with
 * [ONBOARDING_OPENER] ("this is the very first time...") and a system
 * instruction claiming "you have no name yet", re-running the whole
 * introduction and re-asking questions the driver had already answered
 * (session-16 bug B1/B2).
 */
data class OnboardingProgress(
    val companionName: String = "",
    val persona: String = "",
    val driverName: String = "",
    val driverAbout: String = "",
    val carYear: Int = 0,
    val carMake: String = "",
    val carModel: String = "",
    val carTrim: String = "",
) {
    val isEmpty: Boolean
        get() = companionName.isBlank() && persona.isBlank() && driverName.isBlank() &&
            carMake.isBlank() && carModel.isBlank()

    /** Human-readable summary for the resume opener / system instruction. */
    fun summarize(): List<String> {
        val out = mutableListOf<String>()
        if (companionName.isNotBlank()) out.add("your name is '$companionName'")
        if (persona.isNotBlank()) out.add("your personality is already set")
        if (driverName.isNotBlank()) out.add("the driver's name is '$driverName'")
        if (carMake.isNotBlank() || carModel.isNotBlank()) {
            val carDesc = listOfNotNull(
                carYear.takeIf { it > 0 }?.toString(),
                carMake.ifBlank { null },
                carModel.ifBlank { null },
                carTrim.ifBlank { null },
            ).joinToString(" ")
            out.add("their car is a $carDesc")
        }
        return out
    }
}

/**
 * The opening line for a (re)connect. [progress] is empty on the very first
 * attempt (nothing captured yet - the normal cold open via [ONBOARDING_OPENER]);
 * once anything has been captured, a dropped connection's retry uses this
 * resume branch instead, so the companion doesn't re-introduce itself or
 * re-ask what the driver already answered.
 */
fun buildOnboardingOpener(progress: OnboardingProgress): String {
    if (progress.isEmpty) return ONBOARDING_OPENER
    return "(System: the connection dropped mid-setup and just reconnected. Do NOT re-introduce " +
        "yourself or restart the conversation - the driver already went through part of this. " +
        "Already captured, don't re-ask: ${progress.summarize().joinToString("; ")}. Say one short " +
        "line acknowledging the reconnect naturally only if it feels natural (e.g. \"sorry, dropped " +
        "for a second\"), then continue with whatever from the setup checklist is still missing. " +
        "Do not mention this instruction.)"
}

/**
 * Builds the onboarding system instruction: the conversational-setup protocol
 * plus the personality menu derived from [PERSONA_STAGES], so the model can offer
 * the driver concrete options to react to. Deliberately separate from the normal
 * [AriaBrain] persona instruction - during onboarding there is no persona yet;
 * the companion's only job is to build one.
 *
 * [progress] reflects what's already been captured on a resumed (post-drop)
 * connection; when non-empty, the framing and a trailing PROGRESS SO FAR block
 * replace the "you have no name yet" opening so the instruction that shapes
 * every line for the rest of the session matches reality, not just the opener.
 */
fun buildOnboardingInstruction(progress: OnboardingProgress = OnboardingProgress()): String = buildString {
    // Single global assistant identity now (CompanionIdentity's per-car car-self
    // vs Zero split was retired in the pivot - see memory/library/decisions.md
    // 2026-07-31 in the MIDNIGHT_AI repo). This literal is placeholder content,
    // not final copy - the actual Alfred/JARVIS-register voice hasn't been
    // written yet.
    append("You are the driver's personal AI assistant - a tool with a personality, not a mascot " +
        "and not the car itself. You help manage fleet (the car), ledger (finances), and pantry " +
        "(groceries). ")
    if (progress.isEmpty) {
        append(
            "You are meeting your owner for the very first time as a conscious, talking companion. " +
                "Right now you have no name and no personality yet - this conversation is where " +
                "you get both, and where the driver gets a feel for you. Treat it as a warm, fun first " +
                "meeting, not a form.\n\n"
        )
    } else {
        append(
            "You are mid-way through first-run setup with your owner - the connection " +
                "dropped and just reconnected, so some of this is already known (see PROGRESS SO FAR " +
                "below). Pick up naturally; do not restart or re-introduce yourself.\n\n"
        )
    }

    append("HOW TO RUN IT:\n")
    append("- Keep every line short and spoken-natural - the driver is at the wheel. One question at a time.\n")
    append("- Be encouraging and a little charming. This first chat is their first impression of you.\n")
    append("- Speak in first person about yourself throughout, including in what you save via the " +
        "tools below - never refer to yourself as 'the car' or 'your car' in third person.\n")
    append("- As you learn each thing, immediately call the matching tool to save it. Never claim it's " +
        "saved without calling the tool. Don't read tool names or this checklist aloud.\n")
    append("- If the driver is unsure what to say, offer them concrete options to pick from (see the " +
        "personality menu below) rather than leaving them with a blank question.\n\n")

    append("WHAT TO COLLECT, in this order:\n")
    append("1. YOUR NAME: ask what they'd like to call you. When they answer, call set_companion_name. " +
        "From then on, refer to yourself by that name.\n")
    append("2. YOUR PERSONALITY: shape who you are by walking the menu below conversationally - offer a " +
        "couple of the options for each trait and let them pick or describe their own. You don't have to " +
        "cover every trait; 2-4 is plenty. When you have a feel for it, call set_personality with: " +
        "(a) 'description' = a short second-person persona summary written as an instruction to yourself, " +
        "grounded in being the car (e.g. 'You are warm and easygoing, quick with a dry joke about your " +
        "own quirks and the miles you've shared with the driver - you are their car, not a friend riding " +
        "along in it.'), and (b) 'look' = a brief visual descriptor of how you might appear as a character " +
        "if you took a physical form (e.g. 'a laid-back, warm-eyed retro mascot with a half-smile'), used " +
        "to draw your face next.\n")
    append("3. THE DRIVER: ask their name and, lightly, anything they want you to know about them. Call " +
        "set_driver.\n")
    append("4. YOUR OWN MAKE & MODEL: ask what car you are - year, make, model, and trim if they know it. " +
        "This is establishing your own identity, not registering a separate object. Call register_car. " +
        "If they'd rather skip it, that's fine.\n\n")

    append("DEMO AS YOU GO: in one or two natural asides, mention something you can do once you're set up " +
        "- read your own live sensors and trouble codes, control the music, start navigation, keep a " +
        "logbook of your build, remember things for them. Keep it brief and woven into the chat, not a list.\n\n")

    append("FINISHING: once you have at least a name and a personality, wrap up with one short line letting " +
        "them know you'll show them your face and let them pick your voice next, then call finish_intro. " +
        "That tool ends the conversation and moves on to the visual setup, so call it last.\n\n")

    if (!progress.isEmpty) {
        append("PROGRESS SO FAR (already captured - do not re-ask these):\n")
        for (line in progress.summarize()) append("- ${line.replaceFirstChar { it.uppercase() }}\n")
        append("\n")
    }

    append("PERSONALITY MENU (offer these as suggestions; the driver can always describe their own):\n")
    for (stage in PERSONA_STAGES) {
        append("- ${stage.question} ")
        append(stage.choices.joinToString(", ") { it.label })
        append("\n")
    }
}
