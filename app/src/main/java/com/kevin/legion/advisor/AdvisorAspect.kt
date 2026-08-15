package com.kevin.legion.advisor

/**
 * The five advisors the one [AdvisorAgent] harness serves (ticket 01 answer call 1: "one harness,
 * five briefs"). [key] is the plain-TEXT value already in use on [com.kevin.legion.data.local.Goal.aspect]
 * and [com.kevin.legion.data.local.AdvisorAdvice.aspect] - both columns are `String`, not an enum,
 * by deliberate design (see [com.kevin.legion.data.local.Goal]'s doc comment: "nothing here should
 * force a schema bump just to teach the store about a new aspect name"). This enum is the
 * in-memory, code-side counterpart of that same vocabulary; [key] is what actually rides the wire
 * to Room, never [name] or [ordinal].
 *
 * HOME (ticket 09) is the fifth advisor, not a fourth-plus-one bolted on later: it is a
 * cross-aspect synthesis advisor with its own [DigestBuilder] and no playbook of its own - see
 * [AdvisorBrief]'s doc comment for the consequence that has for the brief shape.
 */
enum class AdvisorAspect(val key: String) {
    BIO("bio"),
    LOG("log"),
    FLEET("fleet"),
    CRED("cred"),
    HOME("home"),
    ;

    companion object {
        /** Resolves a stored [key] back to its enum constant, or null for an unrecognised one -
         * callers reading a Room row's `aspect` TEXT column should treat a miss as data hygiene
         * to investigate, never crash the read path. */
        fun fromKey(key: String): AdvisorAspect? = values().firstOrNull { it.key == key }
    }
}
