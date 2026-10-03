import { Link } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { Pencil } from 'lucide-react'
import { useState, type FormEvent } from 'react'

import {
  budgetTargets,
  invalidateSpend,
  useSpend,
  type BudgetTarget,
  type Spend,
} from '@/api/ledger'
import { useRows, useSave, wire } from '@/api/synced'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Meter } from '@/components/workbench/charts'
import { Loaded } from '@/components/workbench/loaded'
import { ErrorSentence, Panel, Unverified } from '@/components/workbench/page'
import { SpendNotes, SpentByAccount } from '@/components/workbench/spent-by-account'
import { centsToDollars, dollarsToCents, formatMoney, newGuid } from '@/lib/figures'
import { monthLabel } from '@/lib/ledger'

/**
 * Money, Budgets: this month's spend against the targets the household set, one
 * line per category.
 *
 * Every figure here is the engine's (`GET /api/ledger/spend`); this tab only
 * lays it out. Three things it will not do: total the lines itself (the engine
 * names no total and an invented one would be a third definition of "spend"),
 * hide a category with no target (it says "No target set" and offers one), and
 * leave out of sight what the figures leave out - uncategorised money, money
 * moved between the household's own accounts, charges that count in the next
 * month - each is a sentence under the lines.
 *
 * A target edit writes a `budget_targets` row effective from the first of THIS
 * month, so last month's target is not rewritten. It is not optimistic: the
 * engine can refuse a number, and a meter that moves and moves back is worse
 * than one that waits.
 */

interface Line {
  category: string
  spend_cents: number
  target_cents: number | null
  unverified: boolean
}

/** The engine's lines, biggest spend first. A category with neither spend nor a
 * target this month is not a line: the engine lists none, and one is not
 * invented here. */
function linesFor(spend: Spend): Line[] {
  return spend.categories
    .map((line) => ({ ...line }))
    .sort((a, b) => b.spend_cents - a.spend_cents || a.category.localeCompare(b.category))
}

function TargetForm({
  line,
  month,
  currency,
  existing,
  onDone,
}: {
  line: Line
  month: string
  currency: string
  /** This category's target row already effective from this month, if any. */
  existing: BudgetTarget | undefined
  onDone: () => void
}) {
  const queryClient = useQueryClient()
  const save = useSave(budgetTargets)
  const [text, setText] = useState(line.target_cents === null ? '' : centsToDollars(line.target_cents))
  const [problem, setProblem] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const cents = dollarsToCents(text)
    if (cents === null || cents < 0) {
      setProblem('Enter dollars and cents, like 500 or 500.00. Nothing was saved.')
      return
    }
    setProblem(null)
    // One row per category per month: editing again reuses the row's own key
    // rather than minting a second row for the same month.
    const identity = existing?.origin_guid ?? newGuid()
    try {
      await save.mutateAsync({
        identity,
        body: wire<BudgetTarget>({
          category: line.category,
          currency,
          amount_cents: cents,
          effective_from_month: `${month}-01`,
          origin_guid: identity,
        }),
      })
      await invalidateSpend(queryClient)
      onDone()
    } catch (error) {
      setProblem(error instanceof Error ? error.message : 'Nothing was saved.')
    }
  }

  const id = `target-${line.category.replace(/\W+/g, '-').toLowerCase()}`
  return (
    <form onSubmit={submit} className="mt-2 flex flex-col gap-2" noValidate>
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={id} className="text-[0.8125rem] text-muted-foreground">
          Monthly target for {line.category}, in dollars, from {monthLabel(month)}
        </label>
        <Input
          id={id}
          inputMode="decimal"
          value={text}
          onChange={(event) => setText(event.target.value)}
          className="w-32"
          autoFocus
        />
        <Button type="submit" size="sm" disabled={save.isPending}>
          {save.isPending ? 'Saving' : 'Save'}
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={onDone} disabled={save.isPending}>
          Cancel
        </Button>
      </div>
      {problem && <ErrorSentence>{problem}</ErrorSentence>}
    </form>
  )
}

function BudgetLine({
  line,
  spend,
  existing,
  canEdit,
}: {
  line: Line
  spend: Spend
  existing: BudgetTarget | undefined
  canEdit: boolean
}) {
  const [editing, setEditing] = useState(false)
  const hasTarget = line.target_cents !== null && line.target_cents > 0
  const spent = formatMoney(line.spend_cents, spend.currency)
  const unverified = line.unverified ? <Unverified /> : undefined

  return (
    <li className="rounded-control bg-surface-2 px-4 py-3">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          {hasTarget ? (
            <Meter
              label={line.category}
              value={line.spend_cents}
              target={line.target_cents ?? 0}
              valueText={spent}
              targetText={formatMoney(line.target_cents ?? 0, spend.currency)}
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
        </div>
        <Button
          variant="ghost"
          size="sm"
          disabled={!canEdit || editing}
          aria-label={`${hasTarget ? 'Edit' : 'Set'} target for ${line.category}`}
          onClick={() => setEditing(true)}
        >
          <Pencil /> {hasTarget ? 'Edit target' : 'Set target'}
        </Button>
      </div>
      {editing && (
        <TargetForm
          line={line}
          month={spend.month}
          currency={spend.currency}
          existing={existing}
          onDone={() => setEditing(false)}
        />
      )}
    </li>
  )
}

/** What the figures above leave out, each in a sentence the engine's numbers fill in. */
function LeftOut({ spend }: { spend: Spend }) {
  const money = (cents: number) => formatMoney(cents, spend.currency)
  const { excluded } = spend
  const sentences: string[] = []
  if (excluded.not_spending_cents > 0) {
    const names = excluded.not_spending_categories.join(', ')
    sentences.push(
      `${money(excluded.not_spending_cents)} was filed under categories marked not spending${names ? ` (${names})` : ''}.`,
    )
  }
  if (excluded.own_account_moves_cents > 0) {
    sentences.push(`${money(excluded.own_account_moves_cents)} moved between your own accounts.`)
  }
  if (excluded.early_charges_counted_here_cents > 0) {
    sentences.push(
      `${money(excluded.early_charges_counted_here_cents)} of Housing dated in the last days of ${monthLabel(previousMonth(spend.month))} is counted in this month.`,
    )
  }
  if (excluded.early_charges_counted_next_month_cents > 0) {
    sentences.push(
      `${money(excluded.early_charges_counted_next_month_cents)} of Housing dated in the last days of this month counts in next month.`,
    )
  }
  if (sentences.length === 0) return null
  return (
    <div className="flex flex-col gap-1 text-[0.8125rem] text-muted-foreground">
      <p className="font-medium text-foreground">Left out of the lines above</p>
      {sentences.map((sentence) => (
        <p key={sentence}>{sentence}</p>
      ))}
    </div>
  )
}

function previousMonth(month: string): string {
  const [year, number] = month.split('-').map(Number)
  const total = year * 12 + number - 2
  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, '0')}`
}

export function BudgetsTab() {
  const spend = useSpend()
  const targets = useRows(budgetTargets)

  return (
    <Panel
      title={spend.data ? `Budgets, ${monthLabel(spend.data.month)}` : 'Budgets'}
      description="Spend this month against the targets you set, as the engine counts it. A target changed here applies from the first of this month."
    >
      <Loaded
        query={spend}
        what="this month's spend"
        quiet
        empty=""
        isEmpty={() => false}
        render={(data) => {
          const effective = `${data.month}-01`
          const existing = (category: string) =>
            targets.data?.find(
              (row) =>
                row.category === category &&
                row.currency === data.currency &&
                row.effective_from_month === effective,
            )
          const lines = linesFor(data)
          return (
            <div className="flex flex-col gap-6">
              <section aria-label="Spent this month" className="flex flex-col gap-3">
                <h3 className="text-base font-medium">Spent this month</h3>
                <SpentByAccount spend={data} />
                <SpendNotes spend={data} />
                {data.uncategorised_cents > 0 && (
                  <p className="text-[0.8125rem]">
                    <Link to="/money" search={{ need: true }} className="text-primary underline">
                      See the transactions that need a category
                    </Link>
                  </p>
                )}
              </section>

              {targets.isError && targets.data === undefined && (
                <ErrorSentence>
                  Could not reach the engine, so targets cannot be changed right now. {targets.error.message}
                </ErrorSentence>
              )}

              <section aria-label="Categories" className="flex flex-col gap-3">
                <h3 className="text-base font-medium">By category</h3>
                {lines.length === 0 ? (
                  <p className="rounded-control bg-surface-2 px-4 py-3 text-[0.9375rem] text-muted-foreground">
                    No category has any spend or a target this month.
                  </p>
                ) : (
                  <ul className="flex flex-col gap-2">
                    {lines.map((line) => (
                      <BudgetLine
                        key={line.category}
                        line={line}
                        spend={data}
                        existing={existing(line.category)}
                        canEdit={targets.data !== undefined}
                      />
                    ))}
                  </ul>
                )}
              </section>

              <LeftOut spend={data} />
            </div>
          )
        }}
      />
    </Panel>
  )
}
