package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerCurrency

/**
 * A reporting entity: a set of accounts whose money is treated as one pot for
 * a monthly profit-and-loss. `.scratch/ledger-pnl/issues/01-entity-profit-and-loss.md`
 * call 2.
 *
 * **Derived from currency, not configured.** Kevin: "instead of many accounts
 * tracking, just put everything into one US entity." The obvious alternative -
 * a user-maintained account-to-entity mapping, the same shape as
 * `LedgerAccountMappingPreferences` - was rejected because it is a setup step
 * that has to be kept correct by hand every time a new account or card shows
 * up, and it is unnecessary: [LedgerTransaction.currency] already answers the
 * question for free, on every row, with no mapping to leave unset. It also
 * happens to match reality exactly as it stands today - BofA is USD, DBS/POSB
 * is SGD - and the SG entity arrives with zero extra work the day Kevin wants
 * it surfaced.
 *
 * One entity per currency also means **no FX ever enters a P&L** - the same
 * reason [com.kevin.legion.ui.ledger.BalancesSection] refuses to combine SGD
 * and USD ("Not combined. No exchange rate is applied."). Do not add a
 * combined all-entity view; it would need an exchange rate nobody printed,
 * which is CLAUDE.md §4 rule 5 (anything a source document doesn't state
 * cannot be gated).
 */
enum class LedgerEntity(val displayName: String, val currency: LedgerCurrency) {
    US("US", LedgerCurrency.USD),
    SG("Singapore", LedgerCurrency.SGD);

    companion object {
        /** The one entity that owns [currency] - a total function, since every [LedgerCurrency] maps to exactly one entity today. */
        fun of(currency: LedgerCurrency): LedgerEntity =
            entries.first { it.currency == currency }
    }
}
