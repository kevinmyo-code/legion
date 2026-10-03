import type { Spend } from '@/api/ledger'
import { EmptySentence, Unverified } from '@/components/workbench/page'
import { formatInstant, formatMoney } from '@/lib/figures'
import { monthLabel } from '@/lib/ledger'

/**
 * What each card has spent this month, as the engine computed it.
 *
 * Shared by Home's "Spent this month" panel and the top of Budgets so the two
 * can never print different figures for the same card. Nothing is computed here:
 * a figure is `spend_cents` as sent, and the word `unverified` stands beside it
 * whenever the engine says a row inside it was never checked (CLAUDE.md section
 * 4 rule 7). Each card also says when its newest row reached the engine, because
 * a figure with no date on it reads as live.
 */
export function SpentByAccount({ spend }: { spend: Spend }) {
  if (spend.accounts.length === 0) {
    return (
      <EmptySentence>
        No card activity has reached the engine for {monthLabel(spend.month)} yet.
      </EmptySentence>
    )
  }
  return (
    <ul className="flex max-w-xl flex-col gap-3">
      {spend.accounts.map((account) => (
        <li
          key={account.account_last4}
          className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-0.5"
        >
          <div className="min-w-0">
            <p className="text-[0.9375rem] font-medium">{account.label}</p>
            <p className="text-[0.8125rem] text-muted-foreground">
              {account.latest_row_at === null
                ? 'No rows from this card yet'
                : `As of ${formatInstant(account.latest_row_at)}`}
              {account.unverified && account.unverified_cents > 0
                ? `, ${formatMoney(account.unverified_cents, spend.currency)} of it unverified`
                : ''}
            </p>
          </div>
          <span className="inline-flex flex-wrap items-center gap-x-2 text-[1.0625rem] font-medium tabular-nums">
            {formatMoney(account.spend_cents, spend.currency)}
            {account.unverified && <Unverified />}
          </span>
        </li>
      ))}
    </ul>
  )
}

/**
 * The sentences that keep a spend figure honest: what is NOT in it, and whether
 * it is final. The engine states both (`uncategorised_cents`, `complete`); this
 * only says them in words.
 */
export function SpendNotes({ spend }: { spend: Spend }) {
  return (
    <div className="flex flex-col gap-1 text-[0.8125rem] text-muted-foreground">
      <p className="inline-flex flex-wrap items-center gap-x-2">
        <span>
          {spend.uncategorised_cents > 0
            ? `${formatMoney(spend.uncategorised_cents, spend.currency)} nobody has categorised yet is not in these figures.`
            : 'Everything spent has a category, so nothing is left out for want of one.'}
        </span>
        {spend.uncategorised_cents > 0 && spend.uncategorised_unverified && <Unverified />}
      </p>
      {!spend.complete && (
        <p>Not final: some days this month are not yet covered by a checked statement.</p>
      )}
    </div>
  )
}
