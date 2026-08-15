package com.kevin.legion.goals

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.plan.combinedTier

/**
 * Goal-progress math shared between the CRED digest and the on-screen GOALS panel meter
 * (quant-viz ticket 08). Extracted from [com.kevin.legion.advisor.digest.CredDigestBuilder], which
 * used to hold BOTH the current-value read and (per this ticket) an implicit progress fraction as
 * two private, digest-only functions - moving them here means a savings balance is read the exact
 * same way, and a fraction is computed the exact same way, on whichever side asks for one. One
 * definition, digest and screen can never disagree (map taste call 6's posture, applied to goals).
 */
object GoalProgress {

    /**
     * Progress toward an ACCUMULATION goal ("save $30k", not "lose 10 lbs" - see
     * [com.kevin.legion.ui.goals.GoalsPanel]'s doc comment on why direction-ambiguous metrics never
     * call this): `currentValue / targetValue`, or `null` when there is nothing sane to divide by.
     *
     * `null` for [targetValue] `== null`, `<= 0.0` (an accumulation goal's target is always a
     * positive ceiling; a zero or negative target is not a shape this function tries to make sense
     * of rather than silently returning a nonsensical or infinite fraction), so a caller can render
     * "no meter" instead of a broken one. [currentValue] is NOT clamped here - a driver who already
     * exceeded the goal gets a fraction > 1.0, and it is [DeckMeter]'s own `coerceIn(0f, 1f)`
     * (see `ui/common/DeckPanels.kt`) that turns that into a full bar, not this function silently
     * capping the number it returns.
     */
    fun accumulationProgress(currentValue: Double, targetValue: Double?): Float? {
        if (targetValue == null || targetValue <= 0.0) return null
        return (currentValue / targetValue).toFloat()
    }

    /**
     * `savings_balance_cents` current value: the sum of every USD account's latest known statement
     * balance ([com.kevin.legion.data.local.LedgerTransactionDao.latestBalanceCents]), moved
     * verbatim out of [com.kevin.legion.advisor.digest.CredDigestBuilder]'s former private
     * `savingsProgress` (same query shape, same [TrustTier] rule: PROVEN only when every
     * contributing account has ever cleared a real reconciliation gate,
     * [com.kevin.legion.data.local.LedgerTransactionDao.hasReconciledRows]). Returns `null` when no
     * USD account has a known balance at all - nothing to report a figure against, and nothing for
     * a caller to build a meter or a digest line from.
     */
    suspend fun savingsBalanceCents(db: CarDatabase): Pair<Long, TrustTier>? {
        val txnDao = db.ledgerTransactionDao()
        val usdAccounts = txnDao.allAccountIds().filter { txnDao.currencyForAccount(it) == LedgerCurrency.USD }
        if (usdAccounts.isEmpty()) return null
        var total = 0L
        var any = false
        val tiers = mutableListOf<TrustTier>()
        for (accountId in usdAccounts) {
            val balance = txnDao.latestBalanceCents(accountId) ?: continue
            any = true
            total += balance
            tiers += if (txnDao.hasReconciledRows(accountId, LedgerCurrency.USD)) TrustTier.PROVEN else TrustTier.REPORTED
        }
        if (!any) return null
        return total to tiers.combinedTier()
    }
}
