package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod

/**
 * Outcome of [LedgerReconciliationCheck.check]. Mirrors `public.commit_statement`'s two possible
 * results (`supabase/migrations/20260825000600_commit_statement_rpc.sql`) - [Committed] and
 * [Quarantined], nothing else. There is no partial-success shape: the whole point of CLAUDE.md
 * section 4 rule 2 is that a document either reconciles completely or writes nothing at all.
 */
sealed class LedgerGateOutcome {
    /** [sumCents] is handed back only so a caller can log or display it; nothing about whether
     * this outcome is [Committed] depends on the caller re-deriving it. */
    data class Committed(val sumCents: Long) : LedgerGateOutcome()

    /** [reason] is worded for a person, not a stack trace - see [StatementParseException.userMessage]'s
     * own doc comment for why that distinction matters at the UI boundary. */
    data class Quarantined(val reason: String) : LedgerGateOutcome()
}

/**
 * The ledger half of CLAUDE.md section 4's reconciliation gate, extracted into one small pure
 * function so it can run in exactly two places and never drift between them:
 *
 * 1. [com.kevin.legion.ledger.parsers.LegionCsvStatementParser] calls this as the phone's own
 *    fast, local, worded pre-check (ticket 03 ruling 2) before a CSV import ever reaches the
 *    network.
 * 2. `public.commit_statement` (`supabase/migrations/20260825000600_commit_statement_rpc.sql`)
 *    implements the SAME arithmetic in SQL and is the one that is actually authoritative - the
 *    phone's copy exists only so a bad extraction fails fast rather than after a round trip.
 *
 * Ruling 2 accepted the cost of two implementations of one arithmetic on the explicit condition
 * that something proves they agree: `app/src/test/resources/gate-corpus.json`, checked from this
 * side by `GateCorpusTest` and from the SQL side by `tools/gate_corpus_sql.py`. Do not
 * reimplement this arithmetic a third time anywhere else - route through this function instead,
 * the same way `GateCorpusTest` does, so the corpus's guarantee actually covers what runs on the
 * phone rather than a hand-copied lookalike of it.
 *
 * **Rule 6 first, deliberately.** With zero lines, `sum` is 0 and `closing - opening` can also be
 * 0, so an empty extraction can satisfy both anchors below on figures that are themselves zero -
 * exactly how a real BofA card statement once passed with four silently dropped interest rows,
 * and it only held because interest was zero that month. The non-empty check must run before any
 * arithmetic, never as a side effect of the arithmetic failing to find anything to disagree with.
 *
 * **AMENDED 2026-08-27, ticket 12's "RULED" section.** [statedTotalCents] is nullable now: no bank
 * format Kevin holds prints one combined total, and demanding a number that was never printed is
 * not a stronger gate, it is an unsatisfiable one. A `null` total is accepted ONLY when
 * [provenance] is [IngestMethod.DETERMINISTIC] - the balance-delta check below is still mandatory
 * either way, so this never drops below two read anchors (opening, closing), it only makes the
 * third conditional on the extraction being code rather than a model. An `LLM_RECONCILED` payload
 * missing its total is still quarantined: ruling 4's three-anchor requirement is unchanged for the
 * path it was written about (a self-consistent hallucination can satisfy a single-anchor check;
 * that risk is specific to a nondeterministic extraction and does not apply here). A parser must
 * never pass `sum(lines)` as [statedTotalCents] to dodge this - that would make anchor 1 an
 * identity, section 4 rule 6's failure shape again.
 */
object LedgerReconciliationCheck {
    fun check(
        amountsCents: List<Long>,
        statedTotalCents: Long?,
        openingBalanceCents: Long,
        closingBalanceCents: Long,
        provenance: IngestMethod = IngestMethod.LLM_RECONCILED,
    ): LedgerGateOutcome {
        if (amountsCents.isEmpty()) {
            return LedgerGateOutcome.Quarantined(
                "No transactions were extracted from this file. An empty extraction can never " +
                    "satisfy the gate, whatever the stated totals are.",
            )
        }

        val sum = amountsCents.sum()

        if (statedTotalCents == null) {
            // The scope guard (the amendment's load-bearing half): only a DETERMINISTIC
            // extraction may qualify on two anchors. Anything else missing a printed total is
            // exactly the unverifiable shape ruling 4 exists to refuse.
            if (provenance != IngestMethod.DETERMINISTIC) {
                return LedgerGateOutcome.Quarantined(
                    "This statement states no printed total, and only a deterministically parsed " +
                        "statement can qualify without one. Nothing was imported.",
                )
            }
            // else: no anchor 1 to check - this statement's bank never prints one. Fall through
            // to the balance-delta check, which is still mandatory.
        } else if (sum != statedTotalCents) {
            return LedgerGateOutcome.Quarantined(
                "Lines sum to $sum cents but the statement states a total of $statedTotalCents " +
                    "cents. Nothing was imported.",
            )
        }

        if (closingBalanceCents - openingBalanceCents != sum) {
            return LedgerGateOutcome.Quarantined(
                "Closing balance minus opening balance is ${closingBalanceCents - openingBalanceCents} " +
                    "cents but the lines sum to $sum cents. Nothing was imported.",
            )
        }

        return LedgerGateOutcome.Committed(sum)
    }
}
