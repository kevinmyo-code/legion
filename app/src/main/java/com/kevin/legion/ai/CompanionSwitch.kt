package com.kevin.legion.ai

import com.kevin.legion.data.local.CompanionProfileEntity

/**
 * Resolving a spoken name ("can I talk to Dorothy") to a companion profile (Kevin, 2026-09-10).
 *
 * The decision half of the `switch_companion` voice tool, kept pure and away from Room and the
 * session so it can be tested as what it is: string matching with three interesting edge cases.
 * [com.kevin.legion.service.LiveSessionController] owns the effects - writing the active profile,
 * creating a row, and rebuilding the socket - because only it can rebuild a socket.
 *
 * **Matching is exact after normalising, never fuzzy.** A misheard name is a real risk on a small
 * transcript, but the failure modes are not symmetrical: [NotFound] costs one clarifying turn and
 * names every companion the user actually has, while a fuzzy match silently hands the conversation
 * to the wrong person. This is the same posture CLAUDE.md sec 4 takes about a figure that cannot be
 * verified, applied to a name.
 *
 * **A built-in persona with no profile row still resolves.** [BUILT_IN_PERSONAS] are templates, not
 * roster entries, so asking for Kratos before ever creating a Kratos profile would otherwise be a
 * [NotFound] that lists Kratos as unavailable while the picker plainly offers him. [CreateAndSwitch]
 * is that case, and the caller creates the row from the persona's own defaults - the same row the
 * Companions screen would have created, so the two paths cannot drift.
 */
object CompanionSwitch {

    /** What the caller should do about a spoken name. */
    sealed interface Outcome {
        /** [profileId] exists in the roster and is not currently active. */
        data class Switch(val profileId: String, val name: String) : Outcome

        /**
         * The name matched a built-in [persona] that has no profile row yet. Create one from its
         * defaults, then activate it.
         */
        data class CreateAndSwitch(val persona: Persona) : Outcome

        /** The name resolved to the companion already answering. Nothing to do. */
        data class AlreadyActive(val name: String) : Outcome

        /** Nothing matched. [available] is every name the user could have asked for. */
        data class NotFound(val spoken: String, val available: List<String>) : Outcome
    }

    /**
     * Resolves [spoken] against the [roster] first and [BUILT_IN_PERSONAS] second.
     *
     * Roster first is deliberate: a profile Kevin renamed is the thing he will say out loud, and a
     * renamed Alfred answering to "Alfred" would be surprising in a way that a built-in answering
     * to its own default name is not. A row also carries the voice and delivery he actually chose,
     * which the persona template does not.
     *
     * [activeProfileId] may be null on an install that has no active selection yet, in which case
     * nothing can be [AlreadyActive] and a match is always a real switch.
     */
    fun resolve(
        roster: List<CompanionProfileEntity>,
        spoken: String,
        activeProfileId: String?,
    ): Outcome {
        val wanted = normalise(spoken)
        val rostered = roster.firstOrNull { wanted.isNotEmpty() && normalise(it.assistantName) == wanted }
        // A built-in's own name or key ("kratos" resolves both ways), but only when no roster row
        // already wears that name - `rostered` would have caught it, and falling through to
        // CreateAndSwitch there would build a duplicate profile every time it was asked for.
        val builtIn = BUILT_IN_PERSONAS.firstOrNull {
            wanted.isNotEmpty() && rostered == null &&
                (normalise(it.defaultName) == wanted || normalise(it.key) == wanted)
        }
        return when {
            rostered == null && builtIn == null -> Outcome.NotFound(spoken.trim(), availableNames(roster))
            rostered == null -> Outcome.CreateAndSwitch(builtIn!!)
            rostered.profileId == activeProfileId -> Outcome.AlreadyActive(rostered.assistantName.trim())
            else -> Outcome.Switch(rostered.profileId, rostered.assistantName.trim())
        }
    }

    /**
     * Every name the user could ask for: the roster's own names, plus any built-in persona that has
     * no row yet. Rendered into [Outcome.NotFound] so the companion can say what IS available
     * rather than only what is not - a refusal that names no alternative leaves the user guessing,
     * which is the shape CLAUDE.md sec 7's outcome-verb rule objects to in its own domain.
     */
    fun availableNames(roster: List<CompanionProfileEntity>): List<String> {
        val taken = roster.map { normalise(it.assistantName) }.toSet()
        val fromRoster = roster.map { it.assistantName.trim() }.filter { it.isNotEmpty() }
        val unbuilt = BUILT_IN_PERSONAS
            .filter { normalise(it.defaultName) !in taken }
            .map { it.defaultName }
        return fromRoster + unbuilt
    }

    /** Lowercase, non-alphanumeric runs collapsed away entirely, so "Dorothy!" and "dorothy" agree. */
    private fun normalise(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }
}
