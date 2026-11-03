import { Link } from '@tanstack/react-router'
import { useState } from 'react'

import { statements, useLedgerTransactions, useSpend } from '@/api/ledger'
import { useRows } from '@/api/synced'
import { BriefingSection, Card, CardHead, Quiet, TrustChip } from '@/components/briefing/parts'
import { RunningTotalChart, RunningTotalTable } from '@/components/briefing/running-total-chart'
import { Skeleton } from '@/components/ui/skeleton'
import { SpendNotes } from '@/components/workbench/spent-by-account'
import { daysLeftInMonth, latestBalances, money, runningTotal } from '@/lib/briefing'
import { parseDay, todayDay } from '@/lib/figures'
import { monthLabel } from '@/lib/ledger'

/** "Spent 1 to 2 November" for the current month; "Spent in October 2026" for another. */
function spentLabel(month: string, today: string): string {
  if (today.slice(0, 7) !== month) return `Spent in ${monthLabel(month)}`
  const date = parseDay(today)
  return `Spent 1 to ${date.getDate()} ${date.toLocaleDateString('en-US', { month: 'long' })}`
}

function SpentCard() {
  const spend = useSpend()
  const { query: txns } = useLedgerTransactions()
  const [table, setTable] = useState(false)
  const today = todayDay()

  if (spend.isPending) {
    return (
      <Card>
        <div aria-busy="true" aria-label="Loading this month's spend" className="flex flex-col gap-2">
          <Skeleton className="h-10 w-48" />
          <Skeleton className="h-36 w-full" />
        </div>
      </Card>
    )
  }
  if (spend.data === undefined) {
    return (
      <Card>
        <Quiet>Could not reach the engine, so what was spent this month is not known. This is not zero.</Quiet>
      </Card>
    )
  }

  const data = spend.data
  const total = data.accounts.reduce((sum, account) => sum + account.spend_cents, 0)
  const unverified = data.accounts.reduce((sum, account) => sum + (account.unverified ? account.unverified_cents : 0), 0)
  const targets = data.categories.filter((line) => line.target_cents !== null)
  const budget = targets.length > 0 ? targets.reduce((sum, line) => sum + line.target_cents!, 0) : null
  const points = txns.data
    ? runningTotal(txns.data, data.month, data.currency, data.excluded.not_spending_categories, today)
    : null
  const current = today.slice(0, 7) === data.month
  const left = budget === null ? null : budget - total

  return (
    <Card>
      <p className="text-[0.8125rem] text-muted-foreground">{spentLabel(data.month, today)}</p>
      {data.accounts.length === 0 ? (
        <Quiet className="mt-1">No card activity has reached the engine for {monthLabel(data.month)} yet.</Quiet>
      ) : (
        <>
          <p className="mt-0.5 flex flex-wrap items-baseline gap-x-2">
            <span className="text-[2.5rem] leading-none font-medium tracking-tight tabular-nums">
              {money(total, data.currency)}
            </span>
            <span className="text-base text-muted-foreground">
              {budget === null ? 'no monthly budget set' : `of ${money(budget, data.currency)} budget`}
            </span>
          </p>
          <div className="my-3 flex flex-col gap-1.5 text-[0.8125rem]">
            <p className="flex flex-wrap items-center gap-2">
              <span className="tabular-nums">{money(total - unverified, data.currency)}</span>
              <TrustChip word="Verified" />
              <span className="text-muted-foreground">checked by a statement or the bank's own feed</span>
            </p>
            {unverified > 0 && (
              <p className="flex flex-wrap items-center gap-2">
                <span className="tabular-nums">{money(unverified, data.currency)}</span>
                <TrustChip word="Unverified" />
                <span className="text-muted-foreground">card rows no statement has checked yet</span>
              </p>
            )}
          </div>
        </>
      )}

      {points === null ? (
        <Quiet className="text-[0.8125rem]">
          {txns.isPending
            ? 'The day-by-day line waits for every transaction to arrive.'
            : 'Could not read the transactions, so the day-by-day line is not drawn.'}
        </Quiet>
      ) : points.length < 2 || points.at(-1)!.cents === 0 ? (
        <Quiet className="text-[0.8125rem]">Nothing categorised has been spent this month yet, so there is no line to draw.</Quiet>
      ) : (
        <>
          <RunningTotalChart points={points} budgetCents={budget} currency={data.currency} />
          <p className="mt-1 text-[0.75rem] text-muted-foreground">
            The line is worked out on this page from categorised card rows by date. The figure above is the engine's,
            which also sets aside transfers and moves late housing charges, so the two can differ.
          </p>
          <button
            type="button"
            aria-expanded={table}
            onClick={() => setTable((open) => !open)}
            className="mt-1 min-h-11 text-[0.8125rem] text-muted-foreground underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-primary"
          >
            {table ? 'Hide the table' : 'Show as a table'}
          </button>
          {table && <RunningTotalTable points={points} currency={data.currency} />}
        </>
      )}

      {left !== null && current && (
        <p className="mt-2 text-[0.8125rem] text-muted-foreground">
          {left >= 0 ? (
            <>
              Budget left for {daysLeftInMonth(today)} days:{' '}
              <span className="font-semibold text-foreground tabular-nums">{money(left, data.currency)}</span>. That is
              a subtraction, not a forecast.
            </>
          ) : (
            <>
              <span className="font-semibold text-foreground tabular-nums">{money(-left, data.currency)}</span> over the
              budget so far.
            </>
          )}
        </p>
      )}
      <div className="mt-2">
        <SpendNotes spend={data} />
      </div>
    </Card>
  )
}

function BalancesCard() {
  const rows = useRows(statements)
  const { query: txns } = useLedgerTransactions()
  const pending = rows.isPending || txns.isPending
  const failed = !pending && (rows.data === undefined || txns.data === undefined)
  const balances = !pending && !failed ? latestBalances(rows.data!, txns.data!) : []

  return (
    <Card>
      <CardHead
        title="Balances"
        aside={
          <Link to="/money" className="text-[0.8125rem] text-muted-foreground underline-offset-4 hover:underline">
            Money
          </Link>
        }
      />
      {pending ? (
        <div aria-busy="true" aria-label="Loading balances" className="flex flex-col gap-2">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : failed ? (
        <Quiet>Could not reach the engine, so the balances are not known.</Quiet>
      ) : balances.length === 0 ? (
        <Quiet>No statement or bank row on file states a balance yet.</Quiet>
      ) : (
        <ul aria-label="Balances">
          {balances.map((balance) => (
            <li
              key={balance.account}
              className="flex min-h-11 flex-wrap items-center gap-x-2.5 gap-y-1 py-2.5 not-first:border-t not-first:border-outline-variant"
            >
              <span className="min-w-[7.5rem] flex-1">
                <span className="block text-[0.9375rem]">{balance.label}</span>
                <span className="block text-[0.75rem] text-muted-foreground">{balance.detail}</span>
              </span>
              <span className="text-base font-semibold tabular-nums">{money(balance.cents, balance.currency)}</span>
              <TrustChip word={balance.word} />
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

/** Section 3: this month's spend against its budget, and the balances on file. */
export function MoneySection() {
  return (
    <BriefingSection n={3} id="briefing-money" title="Money this month">
      <div className="grid gap-3 @2xl:grid-cols-[1.4fr_1fr]">
        <SpentCard />
        <BalancesCard />
      </div>
    </BriefingSection>
  )
}
