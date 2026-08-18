package com.kevin.legion.advisor

import com.kevin.legion.advisor.playbooks.BioPlaybook
import com.kevin.legion.advisor.playbooks.CredPlaybook
import com.kevin.legion.advisor.playbooks.FleetPlaybook
import com.kevin.legion.advisor.playbooks.LogPlaybook

/**
 * One body of doctrine the driver can prime a sub-agent with, and the unit [PlaybookStore] stores
 * and [Priming] hands out (2026-08-18).
 *
 * **Why this exists as its own vocabulary rather than reusing [AdvisorAspect].** Priming now feeds
 * TWO surfaces, not one: the advisor harness ([AdvisorAgent], keyed by [AdvisorAspect]) and the
 * five voice dispatchers in [com.kevin.legion.service.LiveToolbox] (`ask_fleet`, `ask_body`,
 * `ask_goals`, `ask_pantry`, `ask_mail`, keyed by a plain domain string). Those two key spaces do
 * not line up - `body` and `bio` are the same doctrine under different names, `mail` and `pantry`
 * have no doctrine at all, and HOME is an advisor with deliberately no playbook. Keying the store
 * on either surface's vocabulary would force the other to lie about itself, so the store gets its
 * own, and both surfaces map INTO it ([Priming.forAdvisor], [Priming.topicForDispatchDomain]).
 *
 * [defaultText] is the shipped, compile-time playbook - the seed, not the value. What actually
 * rides a prompt is [PlaybookStore.text], which returns the driver's own edit when there is one.
 * The constants stay in the binary so a revert always has something true to revert TO, and so a
 * fresh install is primed before the driver has written a word.
 */
enum class PrimingTopic(
    /** Stable storage key. Matches [AdvisorAspect.key] for the four aspects that have doctrine,
     * so a file written under one vocabulary reads correctly under the other. */
    val key: String,
    /** Human label for the editor screen. */
    val title: String,
    /** One line under the title saying what this doctrine is FOR. */
    val blurb: String,
    /**
     * Substrings this topic's doctrine must still contain after a driver's edit, checked
     * case-insensitively by [PlaybookStore.save].
     *
     * **These are the professional-referral boundaries and the estimate-phrasing requirement** -
     * the content ticket 15 and CLAUDE.md both mark as the part a future trim must never reach
     * (pain and injury, medical conditions, disordered eating, minors and PEDs for BIO; tax,
     * investment selection, insurance and debt restructuring for CRED; safety-critical systems,
     * the owner's manual and physical inspection for FLEET). `PlaybookKeywordsTest` already fails
     * the build if a code change deletes one from a shipped constant; this field is the same guard
     * pointed at the one path that test cannot see, which is the driver typing into the editor at
     * runtime.
     *
     * Deliberately shallow substring checks, exactly as that test is. They cannot catch a
     * rewording that keeps the words and drops the meaning. What they can catch, and what they
     * exist for, is a wholesale replacement that quietly removes the line telling the advisor to
     * send someone to a doctor.
     */
    val requiredPhrases: List<String>,
) {
    BIO(
        "bio", "Training and nutrition",
        "How the coach reasons about lifting, food, sleep, bodyweight.",
        requiredPhrases = listOf(
            "estimate", "Pain or injury", "Medical conditions", "Disordered-eating", "Minors", "SARMs",
        ),
    ),
    LOG(
        "log", "Days and logistics",
        "How the planner reasons about tasks, reminders, the calendar.",
        // LOG names no professional-referral boundary (ticket 15 names none for it); its binding
        // content is the never-nag, never-guilt stance, which is CLAUDE.md sec 7's no-compulsion
        // rule wearing a playbook's clothes.
        requiredPhrases = listOf("never guilt", "never scold about streaks"),
    ),
    FLEET(
        "fleet", "The cars",
        "How the fleet advisor reasons about service, symptoms, ownership.",
        requiredPhrases = listOf(
            "ESTIMATE", "safety-critical", "owner's manual", "physical inspection",
        ),
    ),
    CRED(
        "cred", "Money",
        "How the money advisor reasons about spend, budgets, credit.",
        requiredPhrases = listOf(
            "estimate", "Tax:", "Investment selection", "Insurance:", "restructuring",
        ),
    ),
    ;

    /** The shipped playbook this topic falls back to - see the class doc: seed, not value. */
    val defaultText: String
        get() = when (this) {
            BIO -> BioPlaybook.TEXT
            LOG -> LogPlaybook.TEXT
            FLEET -> FleetPlaybook.TEXT
            CRED -> CredPlaybook.TEXT
        }

    /** Phrases from [requiredPhrases] that [candidate] is missing, case-insensitively. Empty when
     * the edit keeps every boundary. */
    fun missingPhrases(candidate: String): List<String> =
        requiredPhrases.filterNot { candidate.contains(it, ignoreCase = true) }

    companion object {
        /**
         * The size ceiling one playbook may occupy, in characters.
         *
         * 2,500 tokens is the real ceiling (ticket 11), and `PlaybookKeywordsTest` converts it the
         * same way this does: the chars/4 fallback, measured accurate to within about 4% against
         * `countTokens` on this codebase's prose. Deliberately the same arithmetic in both places
         * so a driver's edit is held to exactly the ceiling the shipped text is held to - and
         * worth knowing that FLEET ships with roughly 400 characters of headroom, so this is not a
         * theoretical limit.
         */
        const val MAX_CHARS = 2_500 * 4

        /** Resolves a stored [key] back, or null for an unrecognised one - a caller reading a
         * filename or a Room `aspect` column should treat a miss as data hygiene, never a crash. */
        fun fromKey(key: String): PrimingTopic? = values().firstOrNull { it.key == key }
    }
}
