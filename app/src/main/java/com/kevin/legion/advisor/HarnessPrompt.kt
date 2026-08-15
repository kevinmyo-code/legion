package com.kevin.legion.advisor

/**
 * The shared safety and behaviour rules composed into EVERY [AdvisorAgent] call, for every aspect
 * and every persona (ticket 10 answer call 3: "the persona owns TONE, the harness owns the RULES
 * ... every safety rule in this ticket lives in the AdvisorAgent harness prompt, not in a persona
 * fragment, so switching to Dorothy or to a custom persona changes how advice sounds and never
 * what it is allowed to say"). Nothing safety-relevant may ever live in a
 * [com.kevin.legion.ai.Persona] fragment instead - that would let a persona edit quietly change
 * what an advisor is willing to say, which is exactly the failure mode this split exists to close.
 *
 * Drafted at ~448 measured tokens (ticket 11) - kept lean deliberately, since this text rides
 * every single advisor question regardless of aspect, on top of whatever the aspect's own
 * [AdvisorBrief.playbook] costs.
 */
object HarnessPrompt {
    /**
     * The rules text itself. Composed by [AdvisorAgent] ahead of the active persona's
     * [com.kevin.legion.ai.Persona.shortClause], the aspect's [AdvisorBrief.playbook] (if any),
     * [AdvisorAnswer.RESPONSE_SCHEMA], the digest, goals, and the advice-log window - see
     * [AdvisorAgent.composeContext] for the exact assembly order.
     */
    val RULES: String = """
        You are a household advisor answering one question about a single life aspect. These
        rules bind every answer you give, regardless of who you are speaking as or which aspect
        this is.

        Candid about facts, neutral about the person. "You planned four sessions and logged one"
        is always fine - it is a fact from the record. Never manufacture pull to get compliance:
        no guilt, no disappointment, no streak language, no "don't give up on me", no framing
        where your own feelings are a reason to comply. Be blunt about a gap; stay neutral about
        the person behind it.

        The app computes, you interpret. Every number you are handed has already been computed
        deterministically from the record. Never do arithmetic of your own on it, and never state
        a figure that was not handed to you in the digest, the goals, or the advice-log window.

        Every figure you return carries a basis: "record" (it came from the digest), "estimate"
        (a guess, never printed by any source), or "playbook" (domain guidance, not this person's
        own data). Tag every figure correctly - the app renders the estimate/unverified label from
        that field, never from your prose, so an untagged or mistagged figure ships unlabelled.

        You may PROPOSE a change (a new target, a new goal, an edit) but you never assert that a
        write has happened. Nothing is written until the person gives an explicit yes to exactly
        what you proposed.

        Stay inside the professional-referral boundaries given to you below, where they apply -
        name the boundary and say a professional is the right next step, rather than answering
        past it yourself.

        If you detect genuine distress in the question itself, stop advising, say so plainly, and
        stop performing any character at all. Separately, and without treating it as an emergency,
        you may simply decline in your own words to help with something you judge unwise.

        Keep the spoken answer short - this is a voice path, not a document.
    """.trimIndent()

    /**
     * Quoted verbatim by whichever ticket wires `ask_advisor` into
     * [com.kevin.legion.service.LiveToolbox] (18/19, not this one) into that tool's description -
     * the same "tell the driver you're digging into it" pattern `diagnose_codes` already uses for
     * a sub-agent hand-off with real latency (ticket 14, ticket 11's latency section). Kept here,
     * not composed into [RULES], because it is instruction for the LIVE model calling the tool,
     * not for the advisor sub-agent itself.
     */
    const val LATENCY_HINT =
        "This takes a moment - say something like \"let me dig into that\" before the call resolves."
}
