package com.kevin.legion.ai

import android.content.Context

/**
 * Single source of the assistant's identity clause for system instructions.
 *
 * No longer placeholder. The register comes from whichever [Persona] the
 * active companion profile names - Alfred, Dorothy, or a future one - so
 * switching profiles on a device genuinely changes who answers, not just the
 * name on the label.
 *
 * [CompanionProfile.persona] holds the persona key and
 * [CompanionProfile.name] the driver's chosen name for it, which may differ
 * from [Persona.defaultName]: a profile can be Alfred's register wearing
 * another name. The name is injected rather than hardcoded into the register
 * for exactly that reason.
 *
 * Replaces Midnight AI's `CompanionIdentity`, which branched between "Zero
 * rides along" and "the driver's own car speaks". Both are gone; the car is
 * data, not a speaker.
 */
object AssistantIdentity {

    /** Full identity clause for the main conversational system instruction. */
    fun clause(context: Context): String {
        val persona = personaFor(CompanionProfile.persona(context))
        return withName(persona.clause, persona, CompanionProfile.name(context))
    }

    /** Compressed clause for sub-agent one-shot prompts, where tokens are the budget. */
    fun shortClause(context: Context): String {
        val persona = personaFor(CompanionProfile.persona(context))
        return withName(persona.shortClause, persona, CompanionProfile.name(context))
    }

    /** An in-character first hello for the active persona. */
    fun greeting(context: Context): String {
        val persona = personaFor(CompanionProfile.persona(context))
        val chosen = persona.greetings.randomOrNull() ?: "Hello."
        return withName(chosen, persona, CompanionProfile.name(context))
    }

    /**
     * Substitutes a renamed profile's name into copy written for the persona's
     * default. Only rewrites a standalone occurrence of the default name, so
     * "Dorothy" inside an ordinary word is never mangled; if the driver kept
     * the default, this is a no-op.
     */
    private fun withName(text: String, persona: Persona, name: String): String {
        val chosen = name.trim()
        if (chosen.isBlank() || chosen == persona.defaultName) return text
        return text.replace(Regex("\\b${Regex.escape(persona.defaultName)}\\b"), chosen)
    }
}
