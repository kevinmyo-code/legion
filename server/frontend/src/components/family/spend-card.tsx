import { ChevronRight, Wallet } from 'lucide-react'
import { useState } from 'react'

import { useSpend, type Spend } from '@/api/ledger'
import { BottomSheet } from '@/components/family/bottom-sheet'
import { relativeTime } from '@/components/freshness'
import { Skeleton } from '@/components/ui/skeleton'
import { Meter } from '@/components/workbench/charts'
import { Unverified } from '@/components/workbench/page'
import { SpendNotes } from '@/components/workbench/spent-by-account'
import { formatMoney } from '@/lib/figures'
import { HOME_COPY } from '@/lib/home-copy'
import { monthLabel } from '@/lib/ledger'

/**
 * "Spent this month" on Mia's Home (spec D6, ticket 10): one row per card, and a
 * tap opens the categories.
 *
 * **Nothing here is computed.** A figure is `spend_cents` exactly as the engine
 * sent it (`GET /api/ledger/spend`, one implementation of the phone's rules), so
 * this card and the phone's Money screen are meant to print the same cents.
 *
 * **The trust words stay on the figure.** `unverified` is in the same font as the
 * amount, on the same line, in words (CLAUDE.md section 4 rule 7); the sheet says
 * how much of a card's figure it covers. There is no chart on the card: a chart
 * would have to be drawn from this month's rows, and the card's job is the
 * figure and how old it is.
 *
 * **Four states, four renderings.** Loading is a skeleton; could not reach the
 * engine says so and shows NO amount (a failed read is never `$0.00`); nothing
 * yet says nothing has arrived; a refresh that failed over older data keeps the
 * older figures and says how old they are.
 */

function CardTitle({ children }: { children: React.ReactNode }) {
  return (
    <span className="mb-2 flex items-center gap-2.5">
      <span
        aria-hidden="true"
        className="grid size-8 shrink-0 place-items-center rounded-full bg-unverified-bg text-unverified-fg"
      >
        <Wallet className="size-4" />
      </span>
      {children}
    </span>
  )
}

export function SpendCard() {
  const spend = useSpend()
  const [open, setOpen] = useState(false)

  if (spend.isPending) {
    return (
      <section
        className="rounded-sheet bg-card px-4 pt-3.5 pb-4"
        aria-busy="true"
        aria-label="Loading this month's spend"
      >
        <CardTitle>
          <h2 className="flex-1 text-base font-medium">Spent this month</h2>
        </CardTitle>
        <Skeleton className="mb-2 h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </section>
    )
  }

  const data = spend.data
  if (data === undefined) {
    return (
      <section className="rounded-sheet bg-card px-4 pt-3.5 pb-4">
        <CardTitle>
          <h2 className="flex-1 text-base font-medium">Spent this month</h2>
        </CardTitle>
        <p className="text-[0.9375rem] text-muted-foreground">{HOME_COPY.spend.unreachable}</p>
      </section>
    )
  }

  if (data.accounts.length === 0) {
    return (
      <section className="rounded-sheet bg-card px-4 pt-3.5 pb-4">
        <CardTitle>
          <h2 className="flex-1 text-base font-medium">Spent this month</h2>
        </CardTitle>
        <p className="text-[0.9375rem] text-muted-foreground">{HOME_COPY.spend.empty}</p>
      </section>
    )
  }

  return (
    <section className="rounded-sheet bg-card px-4 pt-3.5 pb-4">
      <h2 className="sr-only">Spent this month</h2>
      <button
        type="button"
        className="block w-full rounded-card text-left outline-none focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-primary"
        onClick={() => setOpen(true)}
      >
        <CardTitle>
          <span className="flex-1 text-base font-medium" aria-hidden="true">
            Spent this month
          </span>
          <span className="flex items-center gap-0.5 text-[0.8125rem] text-muted-foreground">
            By category
            <ChevronRight className="size-4" aria-hidden="true" />
          </span>
        </CardTitle>
        <span className="flex flex-col gap-2.5">
          {data.accounts.map((account) => (
            <span key={account.account_last4} className="flex items-baseline justify-between gap-3">
              <span className="min-w-0">
                <span className="block text-[0.9375rem]">{account.label}</span>
                <span className="block text-[0.8125rem] text-muted-foreground">
                  {account.latest_row_at === null
                    ? 'No rows from this card yet'
                    : `Updated ${relativeTime(Date.parse(account.latest_row_at))}`}
                </span>
              </span>
              <span className="inline-flex shrink-0 flex-wrap items-center justify-end gap-x-2 text-[1.0625rem] font-medium tabular-nums">
                {formatMoney(account.spend_cents, data.currency)}
                {account.unverified && <Unverified />}
              </span>
            </span>
          ))}
        </span>
      </button>
      {spend.isError && (
        <p className="mt-3 text-[0.8125rem] text-muted-foreground">
          {HOME_COPY.spend.stale(relativeTime(spend.dataUpdatedAt))}
        </p>
      )}
      <SpendSheet spend={data} open={open} onOpenChange={setOpen} />
    </section>
  )
}

function SpendSheet({
  spend,
  open,
  onOpenChange,
}: {
  spend: Spend
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const withUnverified = spend.accounts.filter((account) => account.unverified_cents > 0)
  return (
    <BottomSheet
      open={open}
      onOpenChange={onOpenChange}
      title="Spent this month"
      description={`What the household spent in ${monthLabel(spend.month)}, by category, from the engine.`}
    >
      <p className="mb-4 text-[0.9375rem] text-muted-foreground">{monthLabel(spend.month)}</p>

      {spend.categories.length === 0 ? (
        <p className="rounded-control bg-surface-2 px-4 py-3 text-[0.9375rem] text-muted-foreground">
          Nothing spent has a category yet, so there are no lines to show.
        </p>
      ) : (
        <ul className="flex flex-col gap-2" aria-label="Spending by category">
          {spend.categories.map((line) => {
            const spent = formatMoney(line.spend_cents, spend.currency)
            const unverified = line.unverified ? <Unverified /> : undefined
            const target = line.target_cents
            return (
              <li key={line.category} className="rounded-control bg-surface-2 px-4 py-3">
                {target !== null && target > 0 ? (
                  <Meter
                    label={line.category}
                    value={line.spend_cents}
                    target={target}
                    valueText={spent}
                    targetText={formatMoney(target, spend.currency)}
                    extra={unverified}
                  />
                ) : (
                  <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
                    <span className="text-[0.9375rem] font-medium">{line.category}</span>
                    <span className="flex flex-wrap items-center gap-x-2 text-[0.9375rem] tabular-nums">
                      {spent}
                      {unverified}
                      <span className="text-muted-foreground">No target set</span>
                    </span>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}

      <div className="mt-4 flex flex-col gap-2">
        {withUnverified.map((account) => (
          <p key={account.account_last4} className="text-[0.8125rem] text-muted-foreground">
            {account.label}: {formatMoney(account.unverified_cents, spend.currency)} of{' '}
            {formatMoney(account.spend_cents, spend.currency)} is unverified, from rows no statement
            has checked yet.
          </p>
        ))}
        <SpendNotes spend={spend} />
      </div>
    </BottomSheet>
  )
}
