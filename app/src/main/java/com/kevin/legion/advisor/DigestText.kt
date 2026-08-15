package com.kevin.legion.advisor

import com.kevin.legion.plan.TrustTier

/**
 * Shared wire-format helpers every [DigestBuilder] MUST route its figures through, so the compact
 * labelled text (ticket 08 answer call 2: "not JSON... reads naturally to the model and is
 * eyeballable in a log when advice goes wrong") cannot drift into five slightly different label
 * vocabularies across BIO/LOG/FLEET/CRED/HOME.
 *
 * This is the load-bearing safety surface for two hard rules that live in CLAUDE.md, not just in
 * this ticket's taste:
 * - **§4 rule 7**: a figure touching an `UNRECONCILED`/no-anchor row must say so in words, on
 *   every surface. [unverified] is that word, spelled once, so a builder can never hand-roll
 *   "(unreconciled)" or "(approx)" and drift from what the harness's own rules text (see
 *   [HarnessPrompt]) tells the model to expect.
 * - **§4 rule 5**: anything the source document does not itself state (pantry macro guesses, a
 *   playbook's suggested range) is an estimate, labelled in words. [estimate] is that word.
 *
 * Plus ticket 08's own third law: **an empty domain reads "not logged", never zero** - [notLogged]
 * exists so a builder cannot accidentally print `0` for a day nobody recorded, which would have
 * the coach scolding for a gap that is actually a missing record, not a real zero.
 *
 * [withTier] reuses [com.kevin.legion.plan.TrustTier]/[com.kevin.legion.plan.combinedTier] rather
 * than reinventing a tier vocabulary here - `plan/Plan.kt`'s own doc comment is explicit that a
 * generic base type is exactly what that package refuses to grow, so this stays a plain formatting
 * call over an already-computed [TrustTier], never a second source of truth for what a tier means.
 */
object DigestText {

    /** One labelled figure on the wire: `"BUDGET groceries target" "400.00"` -> `"BUDGET
     * groceries target 400.00"`. [label] and [value] are kept as separate parameters (rather than
     * one pre-joined string) so a caller cannot forget the space, and so [withTier]/[unverified]/
     * [estimate] compose onto the result predictably regardless of how many words [label] has. */
    fun line(label: String, value: String): String = "$label $value"

    /**
     * Appends a [TrustTier] as a bracket tag - `[proven]` or `[reported]` - to an already-built
     * line. Callers combine several underlying [TrustTier]s with
     * [com.kevin.legion.plan.combinedTier] before calling this once per emitted line, per D6's
     * "REPORTED the instant any one entry is" rule in `plan/Plan.kt`.
     */
    fun withTier(line: String, tier: TrustTier): String = "$line [${tier.name.lowercase()}]"

    /**
     * Marks a figure unverified IN WORDS (CLAUDE.md §4 rule 7). Appends `(unverified)` to
     * [value] - never a symbol, never a colour, never left to the model's own prose to notice.
     */
    fun unverified(value: String): String = "$value (unverified)"

    /**
     * Marks a figure as an estimate IN WORDS (CLAUDE.md §4 rule 5). Appends `(estimate)` to
     * [value] - the source document never stated this number; it was guessed (a macro figure) or
     * is domain guidance rather than this person's own data (a playbook's suggested range).
     */
    fun estimate(value: String): String = "$value (estimate)"

    /**
     * The rendering for an absent value. NEVER `0`, NEVER a blank string - "not logged" is a fact
     * about the record (nothing was written), not a claim about the world (nothing happened).
     * Ticket 08's own worked example: a digest that reported `0 kcal` for an unlogged day would
     * have the coach scolding for a day that was simply never recorded.
     */
    fun notLogged(): String = "not logged"
}
