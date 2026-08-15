package com.kevin.legion.advisor

import android.content.Context

/**
 * One aspect's deterministic digest (ticket 08 answer call 1): reads existing controllers/DAOs
 * read-only and produces the compact labelled text block the [AdvisorAgent] harness ships to
 * Gemini. Advisor concerns stay entirely out of the domain controllers this way, and there is
 * exactly one place per aspect to audit what a question costs (ticket 11's token table is that
 * audit). New advisor = new [DigestBuilder]; this ticket owns only the interface, not any
 * implementation (BIO/LOG/FLEET/CRED land in tickets 16/17, HOME's cross-aspect digest is its own
 * ticket).
 *
 * [build] MUST route every figure it emits through [DigestText] - see that object's doc comment
 * for why. It takes a plain [Context] (not a ViewModel or a pre-fetched snapshot) because a digest
 * is built fresh, synchronously with the advisor call, off whatever controllers/DAOs it needs;
 * there is no caching layer here and none is wanted; a stale digest handed to a coaching answer is
 * worse than the extra read.
 */
interface DigestBuilder {
    /** Which aspect this builder speaks for - lets [AdvisorAgent] assert a brief's
     * [DigestBuilder.aspect] matches its own [AdvisorBrief.aspect] rather than trusting the wiring
     * silently. */
    val aspect: AdvisorAspect

    /**
     * Builds the compact labelled text digest for one advisor exchange. Read-only: never writes
     * anything, never blocks on network (Drive sync, Gemini) - the ceiling ticket 11 measured
     * assumes this is local Room/controller reads only, not another remote call stacked under the
     * advisor's own latency budget.
     */
    suspend fun build(context: Context): String
}
