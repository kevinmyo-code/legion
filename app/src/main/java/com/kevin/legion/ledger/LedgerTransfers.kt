package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerTransaction

/**
 * Splits a period's transactions into what actually moved money in or out of
 * an entity ("operating") and what only moved money between two of the
 * entity's own accounts ("excluded"), so a monthly P&L never counts the same
 * dollar twice. `.scratch/ledger-pnl/issues/01-entity-profit-and-loss.md`,
 * §"the trap" and call 1.
 *
 * **The trap this file exists to close.** Kevin's US accounts move money to
 * each other constantly - his card statement prints `PAYMENT FROM CHK 3119`,
 * his checking CSV prints `Online Banking transfer from SAV 1490`, and his
 * card CSV prints `PAYMENT FROM SAV 1490 +1300.00`. Sum credits and debits
 * naively across an entity and that one $1,300 card payment becomes $1,300 of
 * income on the card AND $1,300 of expense somewhere else - both sides
 * inflate, and if only one of the two statements happens to be imported, the
 * NET is wrong too, not just the gross.
 *
 * **Three passes, ordered by how falsifiable the claim is.** Pass 1 (matched
 * pairs) is primary because it needs no interpretation of either bank's
 * wording: two rows, different accounts, equal magnitude, opposite sign,
 * close in time, is a transfer no matter what either statement calls it.
 * Pass 2 (own-account reference) runs on what survives pass 1 and needs no
 * partner row either: the description itself names an account Kevin actually
 * holds ([referencesOwnAccount]), which is evidence, not a guess. Pass 3
 * (keyword fallback) only runs on what survives both, and is reported under
 * the weakest [ExclusionReason] because it is the weakest claim - inferred
 * from wording alone, with no second row and no account digit confirming it.
 *
 * **Rejected: keyword rules alone.** A merchant literally named `PAYMENT
 * SOLUTIONS` would silently vanish from expenses with no second row to check
 * it against, and the P&L would quietly understate spend every month that
 * merchant appears. **Rejected: pairs only.** Importing the card statement
 * without the checking one leaves the card's `+1300` counted as income with
 * nothing to pair it against - pass 2 and pass 3 both exist precisely for the
 * half of a transfer that arrived alone, at two different strengths of
 * evidence.
 *
 * **2026-08-13: only-MATCHED-pairs-leave-spend is REVERSED for own-account
 * evidence, not for wording.** Kevin's 2026-08-07 decision ("only a PROVEN
 * pair is safe to pull from spend") was measured against Kevin's real data
 * and found to leave roughly $24,000 of his own money moving between his own
 * accounts counted as spend, because most of those movements only ever have
 * ONE leg imported (the card statement's `PAYMENT TO CRD 4146` with no
 * matching checking import that month). The naive fix - trust
 * [TRANSFER_KEYWORDS] wording alone - was measured too and rejected: it also
 * catches `Zelle payment to <a person's name>`, which is real money leaving
 * to someone else, not a transfer, and excluding it would recreate the exact
 * "understates spend with no signal" failure 2026-08-07 was written to avoid.
 * [OWN_ACCOUNT_MOVEMENT] threads this: it excludes on the SAME strength of
 * evidence pass 1 already trusted (a stated fact about which account the
 * money went to), never on wording alone, so a person's name can never
 * qualify and [SUSPECTED_TRANSFER] keeps 2026-08-07's original behaviour
 * unchanged for everything that isn't provably an own account.
 */

/**
 * Why a row was FLAGGED as a possible transfer. [MATCHED_TRANSFER] found its other half - a second
 * row, a different account, the exact opposite amount, close in time - and IS excluded from
 * [TransferAnalysis.operating]: nothing here is a claim about wording, both legs of the same dollar
 * moving between two of the entity's own accounts are provably present, so counting either leg would
 * double-count it.
 *
 * [OWN_ACCOUNT_MOVEMENT] (Kevin, 2026-08-13) found no partner ROW, but the description itself names
 * an account [referencesOwnAccount] confirms Kevin actually holds - a `PAYMENT TO CRD 4146` naming
 * his own card is proven evidence his money moved to an account of his, on file, whether or not that
 * account's own statement has been imported this period. It IS excluded from
 * [TransferAnalysis.operating], same as a matched pair, because the evidence for it is a stated
 * account identifier, not a guess about wording. **This reverses Kevin's 2026-08-07 decision** (see
 * [SUSPECTED_TRANSFER]'s doc comment below) for exactly this shape of row - see this file's own doc
 * comment for why the reversal is narrower than it looks.
 *
 * [SUSPECTED_TRANSFER] found no partner row AND no account reference [referencesOwnAccount] could
 * confirm - only a [TRANSFER_KEYWORDS] wording match - and per Kevin's 2026-08-07 decision does NOT
 * exclude the row from [TransferAnalysis.operating]: flagging it lets a screen say "this looks like a
 * transfer, unconfirmed" without silently dropping a real transaction on wording alone. A `Zelle
 * payment to JANE DOE` matches "payment to" but never an account digit - real money left to a person,
 * not to Kevin's own pocket, and it stays counted for exactly the reason 2026-08-07 gave: dropping an
 * unconfirmed row on wording alone is the "understates spend with no signal" failure CLAUDE.md §4
 * rule 6 names for a reconciliation gate, applied here to a transfer guess.
 */
enum class ExclusionReason { MATCHED_TRANSFER, OWN_ACCOUNT_MOVEMENT, SUSPECTED_TRANSFER }

/**
 * One flagged row and its reason. [pairedWith] is the [LedgerTransaction.id] of the row it paired
 * with - only ever set for [ExclusionReason.MATCHED_TRANSFER], since neither
 * [ExclusionReason.OWN_ACCOUNT_MOVEMENT] nor [ExclusionReason.SUSPECTED_TRANSFER] found a second row
 * to name (the former's evidence is the description's own printed account, not a partner row).
 *
 * **Not the same thing as "excluded from spend" anymore.** A [ExclusionReason.SUSPECTED_TRANSFER]
 * row appears here AND in [TransferAnalysis.operating] - this list is "flagged for a driver to see",
 * not "removed from the total". [ExclusionReason.MATCHED_TRANSFER] and
 * [ExclusionReason.OWN_ACCOUNT_MOVEMENT] rows are the two reasons actually pulled out of
 * [TransferAnalysis.operating] - see [analyzeTransfers]'s doc comment for why both are safe to pull
 * where wording alone is not.
 */
data class ExcludedRow(val txn: LedgerTransaction, val reason: ExclusionReason, val pairedWith: Long?)

/**
 * [operating] is what the P&L/budget sums - every [inPeriod] row EXCEPT a
 * [ExclusionReason.MATCHED_TRANSFER] or [ExclusionReason.OWN_ACCOUNT_MOVEMENT] leg (Kevin,
 * 2026-08-13: only an OWN-ACCOUNT movement - a proven pair, or a description naming an account Kevin
 * actually holds - is safe to pull out of spend). A [ExclusionReason.SUSPECTED_TRANSFER] row -
 * wording alone, no second row and no account reference confirming it - stays in [operating] and is
 * ALSO reported in [excluded] so a screen can flag it without understating spend on a guess.
 * [excluded] is kept, not discarded, so a driver can inspect exactly what was flagged, why, and
 * whether it was actually pulled from the total - CLAUDE.md §4 rule 7's "say it in words, and make it
 * inspectable" discipline applied to this exclusion rather than an unreconciled row.
 */
data class TransferAnalysis(
    val operating: List<LedgerTransaction>,
    val excluded: List<ExcludedRow>,
)

/**
 * Pass 3's fallback vocabulary, lower-cased, matched case-insensitively against
 * [LedgerTransaction.description]. `internal`, not `private`, so
 * [com.kevin.legion.ledger.isBankNoiseKey] can reuse this SAME list rather than a second,
 * independently-maintained one - the transfer/category work (`.scratch/car-probe-transfers/`,
 * 2026-08-13) found that transfers were flagged here but never kept out of the merchant-
 * categorisation pipeline at all, and the fix is explicitly "reuse the existing classification",
 * never a second transfer detector.
 */
internal val TRANSFER_KEYWORDS = listOf(
    "payment from",
    "payment to",
    "online banking transfer",
    "transfer from",
    "transfer to",
    "conf#",
)

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Whole days between two epoch-millis dates. Both parsers stamp `txnDate` at `atStartOfDay(ZoneOffset.UTC)`, so this is always an exact multiple of a day, never a fraction. */
private fun daysApart(a: Long, b: Long): Long = kotlin.math.abs(a - b) / DAY_MS

/**
 * Splits [inPeriod] into operating and excluded rows. [pairingWindow] is
 * deliberately WIDER than [inPeriod] - the period plus/minus [maxDaysApart]
 * days - because a transfer initiated 30 July and posted 2 August has one leg
 * in each calendar month, and only pairing against a window that reaches past
 * the period's own edges can ever see it. **Only rows in [inPeriod] are ever
 * returned, in either list** - [pairingWindow]'s sole job is to supply
 * candidate partners for [inPeriod] rows; a row that lives entirely outside
 * the period is never itself a result, matched or not.
 *
 * **Pass 1, matched pairs.** A pair is two rows, both still unconsumed, where
 * all of: different [LedgerTransaction.accountId]; `a.amountCents ==
 * -b.amountCents`; [daysApart] `<= maxDaysApart`. Greedy, single-consumption -
 * the same discipline `LedgerDedup.resolveDedup` uses and for the same
 * reason: one row must never absorb two partners, or a genuine third
 * transaction gets silently swallowed by a pair that already has a home.
 * Candidates are ranked by closest date first (so a monthly repeating
 * transfer of the same amount pairs with its own month's leg rather than a
 * neighbouring one), then by lowest [LedgerTransaction.id] as a deterministic
 * tie-break - **rows are processed in `id` order too**, so the whole pass
 * produces the same pairing regardless of what order its input list arrives
 * in.
 *
 * **Pass 2, own-account reference (Kevin, 2026-08-13).** Runs only over
 * [inPeriod] rows pass 1 left unconsumed, testing [referencesOwnAccount]
 * against [LedgerTransaction.description] and [ownAccountIds]. Marked
 * [ExclusionReason.OWN_ACCOUNT_MOVEMENT] and IS pulled out of
 * [TransferAnalysis.operating] - unlike pass 3 below, this is not a wording
 * guess: the description states a specific account, and [ownAccountIds]
 * proves Kevin holds it. A `PAYMENT TO CRD 4146` naming his own card is safe
 * to exclude even with no matching checking-statement row this period, for
 * the same reason a matched pair is safe - the evidence is a fact on file,
 * not an inference.
 *
 * **Pass 3, keyword fallback.** Runs only over [inPeriod] rows passes 1 and 2
 * left unconsumed, matching [TRANSFER_KEYWORDS] case-insensitively against
 * [LedgerTransaction.description]. Marked [ExclusionReason.SUSPECTED_TRANSFER]
 * - weaker than either pass above, because nothing here confirms it against a
 * second row or a stated account. **Kevin's 2026-08-07 decision, UNCHANGED for
 * this pass: a [ExclusionReason.SUSPECTED_TRANSFER] row is flagged in
 * [TransferAnalysis.excluded] but stays in [TransferAnalysis.operating] too.**
 * A `Zelle payment to <a person's name>` lands here - it matches "payment to"
 * but names no account [referencesOwnAccount] can confirm, so it is real
 * spend and must stay counted; pulling it out on wording alone would recreate
 * the exact "understates spend with no signal" failure 2026-08-07 exists to
 * prevent. The other statement simply not having landed yet is routine
 * (folders get scanned at different times), and a driver whose card payment
 * silently vanished from spend because neither the checking statement nor a
 * recognizable account reference had shown up yet would see an UNDERSTATED
 * total with no signal anything was wrong. That is a worse failure than
 * occasionally leaving an actual transfer's lone leg in the total until pass
 * 1 or pass 2 can prove it.
 *
 * **Known false-positive risk, stated rather than hidden.** A $50 charge on
 * one account and an unrelated $50 refund on another within [maxDaysApart]
 * days will pair as pass 1 sees them and both will drop out of the total. This
 * is exactly why [TransferAnalysis.excluded] is returned instead of silently
 * discarded, and why the UI lists it for inspection. The alternative - never
 * pairing at all - double-counts every real transfer, which given how often
 * Kevin's own accounts pay each other is the larger and far more common
 * error; this is the deliberate trade the ticket resolution makes.
 */
fun analyzeTransfers(
    inPeriod: List<LedgerTransaction>,
    pairingWindow: List<LedgerTransaction>,
    maxDaysApart: Int = 5,
    ownAccountIds: Set<String> = emptySet(),
): TransferAnalysis {
    val candidates = pairingWindow.sortedBy { it.id }
    val consumed = mutableSetOf<Long>()
    val partnerOf = mutableMapOf<Long, Long>()

    for (row in candidates) {
        if (row.id in consumed) continue
        val partner = candidates
            .asSequence()
            .filter { other ->
                other.id != row.id &&
                    other.id !in consumed &&
                    other.accountId != row.accountId &&
                    other.amountCents == -row.amountCents &&
                    daysApart(row.txnDate, other.txnDate) <= maxDaysApart
            }
            // Closest date wins; ties broken on id, never on encounter order -
            // this is what makes the whole function order-independent.
            .minWithOrNull(compareBy({ daysApart(row.txnDate, it.txnDate) }, { it.id }))
            ?: continue
        consumed += row.id
        consumed += partner.id
        partnerOf[row.id] = partner.id
        partnerOf[partner.id] = row.id
    }

    val operating = mutableListOf<LedgerTransaction>()
    val excluded = mutableListOf<ExcludedRow>()
    for (txn in inPeriod) {
        when {
            // A confirmed pair: both halves of the same dollar are on file,
            // so this leg is safely removed from spend - the only case
            // pulled out of `operating` before 2026-08-13.
            txn.id in consumed -> excluded += ExcludedRow(txn, ExclusionReason.MATCHED_TRANSFER, partnerOf[txn.id])
            // Pass 2 (2026-08-13): no partner row, but the description names an
            // account Kevin actually holds - proven evidence, not a guess, so
            // this is the SECOND case pulled out of `operating`.
            referencesOwnAccount(txn.description, ownAccountIds) ->
                excluded += ExcludedRow(txn, ExclusionReason.OWN_ACCOUNT_MOVEMENT, null)
            // A wording-only guess, no second row and no account digit
            // confirming it. Kevin's 2026-08-07 decision: flag it (so a
            // screen CAN say "looks like a transfer, unconfirmed"), but never
            // remove it from spend on a guess alone - the other statement
            // simply not having landed yet is routine, not evidence the
            // dollar didn't leave.
            looksLikeTransfer(txn.description) -> {
                excluded += ExcludedRow(txn, ExclusionReason.SUSPECTED_TRANSFER, null)
                operating += txn
            }
            else -> operating += txn
        }
    }
    return TransferAnalysis(operating, excluded)
}

private fun looksLikeTransfer(description: String): Boolean {
    val lower = description.lowercase()
    return TRANSFER_KEYWORDS.any { lower.contains(it) }
}
