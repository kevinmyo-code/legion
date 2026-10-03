import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'

import { ingestedFiles, useLedgerTransactions, useSpend } from '@/api/ledger'
import { useRows } from '@/api/synced'
import { relativeTime } from '@/components/freshness'
import { Button } from '@/components/ui/button'
import { SpendNotes, SpentByAccount } from '@/components/workbench/spent-by-account'
import { Skeleton } from '@/components/ui/skeleton'
import { plural } from '@/lib/figures'
import { monthLabel } from '@/lib/ledger'
import { useSurface } from '@/lib/surface'

/**
 * Workbench Home's two money panels: "Needs a decision" and "Spent this month".
 *
 * They read the same hooks the Money screen does (`useLedgerTransactions`,
 * `useSpend`, the files table), so a figure here is the one Money would print,
 * and the second screen to ask costs no second fetch (one query cache).
 *
 * Each panel is one of FOUR things and says which, in words: still loading, could
 * not reach the engine, nothing to show, or the figures. In particular a count of
 * transactions that need a category is never printed while the ledger is only
 * partly in - that would be a count of part of it.
 *
 * Kept in this file, and mounted by a single line on the Home route, so the
 * agenda being rewritten beside it and this do not touch the same code.
 */

function Section({
  title,
  action,
  children,
}: {
  title: string
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="rounded-sheet bg-card px-4 pt-3.5 pb-4 md:px-5">
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <h2 className="text-base font-medium">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}

function Decision({
  title,
  detail,
  action,
}: {
  title: string
  detail: string
  action?: ReactNode
}) {
  return (
    <li className="flex items-center justify-between gap-3 rounded-control bg-surface-2 py-2.5 pr-2.5 pl-4">
      <div className="min-w-0">
        <p className="text-[0.9375rem] font-medium">{title}</p>
        <p className="text-[0.8125rem] text-muted-foreground">{detail}</p>
      </div>
      {action}
    </li>
  )
}

/** The third sentence: older data is on screen and the last refresh failed. */
function StaleNote({ updatedAt }: { updatedAt: number }) {
  return (
    <p className="text-[0.8125rem] text-muted-foreground">
      The last refresh did not reach the engine. This is what was read {relativeTime(updatedAt)}, not
      what is there now.
    </p>
  )
}

function Quiet({ children }: { children: ReactNode }) {
  return <li className="px-1 text-[0.9375rem] text-muted-foreground">{children}</li>
}

export function NeedsDecisionPanel() {
  const { query: transactions, loaded } = useLedgerTransactions()
  const files = useRows(ingestedFiles)

  const needCategory = transactions.data?.filter((row) => row.category === null).length
  const held = files.data?.filter((file) => file.state === 'QUARANTINED')

  return (
    <Section title="Needs a decision">
      <ul className="flex flex-col gap-1.5">
        {transactions.isPending ? (
          <Quiet>
            Still loading transactions{loaded > 0 ? `: ${loaded.toLocaleString('en-US')} so far` : ''}.
            The count of those needing a category waits for all of them.
          </Quiet>
        ) : needCategory === undefined ? (
          <Quiet>
            Could not reach the engine, so how many transactions need a category is not known.
          </Quiet>
        ) : needCategory > 0 ? (
          <Decision
            title={`${plural(needCategory, 'transaction')} ${needCategory === 1 ? 'needs' : 'need'} a category`}
            detail="Categories drive the monthly picture."
            action={
              <Button asChild variant="secondary" size="sm">
                <Link to="/money" search={{ need: true }}>
                  Review
                </Link>
              </Button>
            }
          />
        ) : (
          <Quiet>Every transaction has a category.</Quiet>
        )}

        {files.isPending ? (
          <Quiet>Still loading the bank files.</Quiet>
        ) : held === undefined ? (
          <Quiet>
            Could not reach the engine, so whether any bank file is held for review is not known.
          </Quiet>
        ) : held.length > 0 ? (
          <Decision
            title={`${plural(held.length, 'file')} held for review`}
            detail={`${held.map((file) => file.display_name ?? 'Unnamed file').join(', ')}. Nothing from ${held.length === 1 ? 'it was' : 'them was'} added.`}
            action={
              <Button asChild variant="secondary" size="sm">
                <Link to="/money" search={{ tab: 'files' }}>
                  See why
                </Link>
              </Button>
            }
          />
        ) : (
          <Quiet>No bank file is held for review.</Quiet>
        )}
      </ul>
      {(transactions.isError && transactions.data !== undefined) ||
      (files.isError && files.data !== undefined) ? (
        <div className="mt-2">
          <StaleNote updatedAt={Math.min(transactions.dataUpdatedAt, files.dataUpdatedAt)} />
        </div>
      ) : null}
    </Section>
  )
}

export function SpentThisMonthPanel() {
  const spend = useSpend()
  return (
    <Section
      title="Spent this month"
      action={
        <Link to="/money" search={{ tab: 'budgets' }} className="text-[0.8125rem] text-primary underline">
          Open Money
        </Link>
      }
    >
      {spend.isPending ? (
        <div className="flex flex-col gap-2" aria-busy="true" aria-label="Loading this month's spend">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : spend.data === undefined ? (
        <p className="text-[0.9375rem] text-muted-foreground">
          Could not reach the engine, so this is not the real figure for what was spent this month.
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="sr-only">{monthLabel(spend.data.month)}</p>
          <SpentByAccount spend={spend.data} />
          <SpendNotes spend={spend.data} />
          {spend.isError && <StaleNote updatedAt={spend.dataUpdatedAt} />}
        </div>
      )}
    </Section>
  )
}

/** Both panels, on the workbench only: the narrow family view has no tables to
 * link to and shows what a card spent in its own, smaller form. */
export function WorkbenchMoneyPanels() {
  const surface = useSurface()
  if (surface !== 'workbench') return null
  return (
    <>
      <NeedsDecisionPanel />
      <SpentThisMonthPanel />
    </>
  )
}
