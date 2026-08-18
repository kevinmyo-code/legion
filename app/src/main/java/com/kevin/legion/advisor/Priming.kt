package com.kevin.legion.advisor

import android.content.Context

/**
 * The one place that answers "what doctrine, if any, should this sub-agent read before it
 * answers?" (2026-08-18).
 *
 * **The gap this closes.** Before this, priming existed on exactly one of the two paths a question
 * can travel. An advisor exchange ([AdvisorAgent.composeContext]) prepended its aspect's playbook;
 * the five voice dispatchers in [com.kevin.legion.service.LiveToolbox] prepended nothing but four
 * sentences of behavioural rules (pull only what matters, call estimates estimates, no markdown).
 * So the same question about the driver's training answered from a 2,000-token playbook through
 * one door and from Flash's general knowledge through the other. One resolver, called by both,
 * is what stops those two answer qualities drifting apart again.
 *
 * **Not every domain gets doctrine, and the omissions are decisions.** See
 * [topicForDispatchDomain] for the routing table and the reason attached to each miss. A playbook
 * costs real tokens on every model call in a bounded investigate loop (up to four per dispatch),
 * so attaching one to a domain it cannot help is not neutral.
 */
object Priming {

    /**
     * The rule that keeps a primed voice answer honest, appended to any dispatch grounding that
     * actually carries doctrine.
     *
     * **Why it is needed only once doctrine is attached.** [AdvisorAnswer] already forces every
     * figure an advisor states to declare `basis: record | estimate | playbook`, machine-checked
     * by structured output. The voice dispatchers have no such envelope - they answer in plain
     * spoken prose. Handing them a playbook without this clause would let doctrine ("10-19 sets
     * per muscle per week") come out of the speaker in the same voice as a stored fact ("you did
     * 14 sets last week"), which is precisely the record-versus-not confusion the advisor schema
     * exists to prevent. Prose, not a schema, because the Live path has nowhere to put a tag - but
     * the same claim has to be made either way.
     */
    const val BASIS_CLAUSE: String =
        " Some of what you were given above is doctrine from a playbook, not a record of anything " +
            "that happened. When a number or claim comes from that doctrine rather than from a tool " +
            "result, say so in words - call it a rule of thumb or a general guideline. Never speak a " +
            "playbook figure as though it were something the driver logged."

    /**
     * The doctrine an advisor exchange should ride, or null when the aspect owns none.
     *
     * HOME is null by design, not by omission: it is a cross-aspect synthesis advisor with "no
     * fifth body of research to maintain, and no risk of it contradicting a playbook it only half
     * holds" ([AdvisorBriefs.HOME]). It carries an [AdvisorBrief.synthesisNote] instead, which
     * [AdvisorAgent.composeContext] still renders separately.
     */
    fun forAdvisor(context: Context, aspect: AdvisorAspect): String? = when (aspect) {
        AdvisorAspect.BIO -> PlaybookStore.text(context, PrimingTopic.BIO)
        AdvisorAspect.LOG -> PlaybookStore.text(context, PrimingTopic.LOG)
        AdvisorAspect.FLEET -> PlaybookStore.text(context, PrimingTopic.FLEET)
        AdvisorAspect.CRED -> PlaybookStore.text(context, PrimingTopic.CRED)
        AdvisorAspect.HOME -> null
    }

    /**
     * The routing table from a [com.kevin.legion.service.LiveToolbox] dispatcher domain to the
     * doctrine it should read first. Every miss below is a call with a reason, not a hole:
     *
     * - `fleet` -> [PrimingTopic.FLEET]. Service intervals, symptom triage and the
     *   "safety-critical, go to a shop" boundaries are exactly what a spoken car question needs and
     *   exactly what Flash will otherwise improvise.
     * - `body` -> [PrimingTopic.BIO]. Same doctrine the BIO advisor already reads; the whole point
     *   of this file is that asking out loud stops being the cheaper answer.
     * - `pantry` -> none. The pantry dispatcher answers inventory and spend questions off its own
     *   tools (what was bought, what it cost, what is on the list). BIO's coaching doctrine would
     *   add ~2,000 tokens per model call to answer "what did I spend on groceries" and change
     *   nothing about the answer.
     * - `goals` -> none. It hands off to the aspect advisors, and each of those already reads its
     *   own playbook through [forAdvisor]. Attaching doctrine here would prime the same text twice
     *   in one exchange and invite the dispatcher to answer a domain question itself instead of
     *   handing it to the advisor that owns it.
     * - `mail` -> none. There is no mail doctrine and no reason to write one; it is a read-only
     *   search over the driver's own inbox.
     *
     * An unrecognised domain returns null rather than a default, so a sixth dispatcher added later
     * is unprimed until someone decides what it should read - silent inheritance of the wrong
     * doctrine is worse than none.
     */
    fun topicForDispatchDomain(domain: String): PrimingTopic? = when (domain) {
        "fleet" -> PrimingTopic.FLEET
        "body" -> PrimingTopic.BIO
        else -> null
    }

    /**
     * The block to append to a dispatch grounding for [domain]: the doctrine under a labelled
     * header, plus [BASIS_CLAUSE]. Empty string when the domain reads no doctrine, so an unprimed
     * grounding is byte-identical to what it was before this file existed - no empty "PLAYBOOK:"
     * header costing tokens and telling the model nothing, the same rule
     * [AdvisorAgent.composeContext] already follows for HOME.
     */
    fun dispatchClause(context: Context, domain: String): String {
        val topic = topicForDispatchDomain(domain) ?: return ""
        return BASIS_CLAUSE + "\n\nPLAYBOOK:\n" + PlaybookStore.text(context, topic).trim()
    }
}
