package com.kevin.legion.advisor

/**
 * One aspect's contribution to the shared [AdvisorAgent] harness (ticket 01 answer call 1: "one
 * harness, five briefs" - playbook + digest builder + writable-proposal schema per aspect, with
 * the shared safety rules living once in [HarnessPrompt] so no aspect can forget them).
 *
 * **[playbook] and [writableOps] are OPTIONAL, not required.** HOME ([The cross-aspect HOME
 * advisor](../../.scratch/aspect-advisors/issues/09-home-advisor.md)) has neither: it is a
 * synthesis advisor with no domain expertise and no body of research of its own ("no fifth body of
 * research to maintain, and no risk of it contradicting a playbook it only half holds"), and it is
 * read-only - a concrete proposal is always handed off to the aspect advisor that actually owns
 * the relevant playbook and allowlist ("HOME needs no entry in the propose-accept-write allowlist
 * at all"). A harness that required either field for every brief would need reworking to admit
 * HOME - this is stated explicitly in that ticket's own "Consequence for the harness" section, so
 * building one that assumes both are always present is a known, named mistake, not an oversight.
 */
data class AdvisorBrief(
    val aspect: AdvisorAspect,
    /**
     * Domain-specific advice rules and professional-referral boundaries for this aspect (ticket 10
     * "From law, not asked": injury pain/medical conditions/disordered-eating/minors for BIO, tax/
     * investment selection/insurance/debt restructuring for CRED, safety-critical systems and the
     * owner's manual for FLEET). Null for HOME, which owns no domain playbook of its own.
     */
    val playbook: String? = null,
    /**
     * A short steer for HOW this aspect's advisor should reason, distinct from [playbook]'s
     * content rules - e.g. HOME's framing from ticket 09 answer call 2: spot the cross-domain
     * interaction, name the goal most at risk, name the trajectory, and say so in words rather
     * than improvise when a question needs domain depth this brief doesn't carry. Optional for
     * every aspect, not just HOME - an aspect advisor may also want a short reasoning steer beyond
     * its playbook's content.
     */
    val synthesisNote: String? = null,
    /** Builds this aspect's deterministic digest - see [DigestBuilder]'s own doc comment. */
    val digestBuilder: DigestBuilder,
    /**
     * The set of write operation names this aspect's advisor may propose (never execute - see
     * [HarnessPrompt]'s propose-never-assert rule). Empty for HOME (ticket 09 answer call 4: HOME
     * is read-only by design, not by omission). Not validated against
     * [com.kevin.legion.service.LiveToolbox] here - that wiring belongs to a later ticket; this is
     * the allowlist a brief DECLARES, and enforcing a proposal actually lands inside it is the
     * accept-path's job.
     */
    val writableOps: Set<String> = emptySet(),
)
