import { observeElementRect, useVirtualizer, type Rect } from '@tanstack/react-virtual'
import { Check, X } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'

import {
  categoryRules,
  ledgerCategories,
  useLedgerTransactions,
  useSetTransactionCategory,
  useSpend,
  type CategoryRule,
  type LedgerTransaction,
} from '@/api/ledger'
import { useRows } from '@/api/synced'
import { Button } from '@/components/ui/button'
import { CategoryCombobox } from '@/components/workbench/category-combobox'
import { Loaded } from '@/components/workbench/loaded'
import { EmptySentence, ErrorSentence, Panel, Unverified } from '@/components/workbench/page'
import { formatDay, formatMoney, formatShortDay } from '@/lib/figures'
import {
  NO_FILTERS,
  accountLabels,
  applyFilters,
  categoryHistory,
  categoryNames,
  isFiltered,
  isUnverified,
  monthLabel,
  monthsPresent,
  netByCurrency,
  newestFirst,
  sourceWord,
  type TransactionFilters,
} from '@/lib/ledger'
import { cn } from '@/lib/utils'

/**
 * Money, Transactions: the whole ledger, filterable, with a category a click away.
 *
 * What this tab will and will not say, because it is the screen where a wrong
 * number is easiest to print:
 *
 * - **No total over a partial fetch.** The ledger arrives 500 rows at a time. Until
 *   every page is here the tab says "Still loading N" and shows no row count, no
 *   net and no month; the counts on the filter chips wait too, since a count over
 *   part of the ledger is the same mistake.
 * - **`unverified` is a word in the Status column** and beside any net that has an
 *   unverified row added into it. A row's `verification_note` is shown verbatim in
 *   the detail panel; it is the engine's sentence and not ours to paraphrase.
 * - **Nothing here edits a transaction.** The gate wrote it. A category is laid
 *   over it, optimistically, and put back with a sentence if the engine says no.
 */

const ROW_HEIGHT = 56
// What to assume for a scroll box that measures as nothing: before the first
// layout, in a hidden tab, or in jsdom, which has no layout at all. Without a
// size the virtualizer renders no rows, and a table of nothing reads as an empty
// ledger. A real measurement always replaces it.
const INITIAL_RECT: Rect = { width: 1000, height: 640 }

function amountText(txn: LedgerTransaction): string {
  const money = formatMoney(txn.amount_cents, txn.currency)
  return txn.amount_cents > 0 ? `+${money}` : money
}

/** "Oct 2" this year, "Oct 2, 2025" before it: the year only where it is news. */
function dateText(txn: LedgerTransaction): string {
  return txn.txn_date.slice(0, 4) === String(new Date().getFullYear())
    ? formatShortDay(txn.txn_date)
    : formatDay(txn.txn_date)
}

function ChipSelect({
  label,
  value,
  onChange,
  options,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  options: readonly { value: string; label: string }[]
}) {
  return (
    <select
      aria-label={label}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      className={cn(
        'h-9 max-w-52 rounded-full px-3.5 text-[0.9375rem] outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary',
        value === 'all' ? 'bg-surface-2' : 'bg-primary-container text-primary-container-foreground',
      )}
    >
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  )
}

function ToggleChip({
  label,
  count,
  pressed,
  onToggle,
}: {
  label: string
  count: number
  pressed: boolean
  onToggle: () => void
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onToggle}
      className={cn(
        'inline-flex h-9 items-center gap-1.5 rounded-full px-3.5 text-[0.9375rem] outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary',
        pressed
          ? 'bg-primary-container text-primary-container-foreground'
          : 'bg-surface-2 hover:bg-surface-3',
      )}
    >
      {pressed && <Check className="size-4" aria-hidden="true" />}
      {label}
      <span className="tabular-nums text-muted-foreground">{count.toLocaleString('en-US')}</span>
    </button>
  )
}

function DetailPanel({
  txn,
  account,
  categories,
  rules,
  onChoose,
  onClose,
}: {
  txn: LedgerTransaction
  account: string
  categories: readonly string[]
  rules: readonly CategoryRule[]
  onChoose: (txn: LedgerTransaction, category: string | null) => void
  onClose: () => void
}) {
  const unverified = isUnverified(txn)
  const checked =
    txn.provenance === 'LLM_RECONCILED'
      ? 'Checked against its statement, read by a model.'
      : 'Checked against its statement, read by a parser.'
  return (
    <aside
      aria-label="Selected transaction"
      className="flex w-full shrink-0 flex-col gap-4 rounded-card bg-surface-2 p-5 xl:sticky xl:top-4 xl:w-64"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-[0.8125rem] text-muted-foreground">Selected transaction</p>
          <h3 className="text-lg leading-snug font-medium break-words">{txn.description}</h3>
        </div>
        <Button variant="ghost" size="icon-sm" aria-label="Close details" onClick={onClose}>
          <X />
        </Button>
      </div>
      <p className="text-[1.75rem] leading-none font-medium tabular-nums">{amountText(txn)}</p>

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-[0.9375rem]">
        <dt className="text-muted-foreground">Date</dt>
        <dd>{formatDay(txn.txn_date)}</dd>
        <dt className="text-muted-foreground">Account</dt>
        <dd>
          {account}
          <span className="text-muted-foreground"> ending {txn.account_last4}</span>
        </dd>
        <dt className="text-muted-foreground">Source</dt>
        <dd>{sourceWord(txn)}</dd>
        {txn.balance_cents !== null && (
          <>
            <dt className="text-muted-foreground">Balance after</dt>
            <dd className="tabular-nums">{formatMoney(txn.balance_cents, txn.currency)}</dd>
          </>
        )}
        {txn.line_ref.trim() !== '' && (
          <>
            <dt className="text-muted-foreground">Reference</dt>
            <dd className="break-all">{txn.line_ref}</dd>
          </>
        )}
        {txn.reversal_of !== null && (
          <>
            <dt className="text-muted-foreground">Reversal</dt>
            <dd>Reverses an earlier row</dd>
          </>
        )}
      </dl>

      <div className="flex flex-col gap-1.5">
        <span className="text-[0.8125rem] text-muted-foreground">Category</span>
        <CategoryCombobox
          value={txn.category}
          categories={categories}
          label="Category of the selected transaction"
          onPick={(category) => onChoose(txn, category)}
          onClear={txn.category_source === 'person' ? () => onChoose(txn, null) : undefined}
        />
        <p className="text-[0.8125rem] text-muted-foreground">{categoryHistory(txn, rules)}</p>
      </div>

      {unverified ? (
        <div className="rounded-control bg-unverified-bg p-4 text-unverified-fg">
          <p className="text-[0.9375rem] font-semibold">unverified</p>
          <p className="mt-1 text-[0.875rem]">
            {txn.verification_note ?? 'Entered by hand. No statement has checked it.'}
          </p>
        </div>
      ) : (
        <p className="rounded-control bg-surface-3 p-4 text-[0.875rem]">
          <span className="font-semibold">Verified.</span> {checked}
        </p>
      )}
    </aside>
  )
}

function Table({
  rows,
  resetKey,
  labels,
  categories,
  selectedId,
  onSelect,
  onChoose,
}: {
  rows: readonly LedgerTransaction[]
  /** Changes when the filters do; the list goes back to its top. */
  resetKey: unknown
  labels: ReadonlyMap<string, string>
  categories: readonly string[]
  selectedId: string | null
  onSelect: (id: string) => void
  onChoose: (txn: LedgerTransaction, category: string | null) => void
}) {
  const scroller = useRef<HTMLDivElement>(null)
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 8,
    initialRect: INITIAL_RECT,
    observeElementRect: (instance, report) =>
      observeElementRect(instance, (rect) =>
        report(rect.width === 0 && rect.height === 0 ? INITIAL_RECT : rect),
      ),
  })
  useEffect(() => {
    scroller.current?.scrollTo?.({ top: 0 })
  }, [resetKey])
  const items = virtualizer.getVirtualItems()
  const before = items.length > 0 ? items[0].start : 0
  const after = items.length > 0 ? virtualizer.getTotalSize() - items[items.length - 1].end : 0

  return (
    <div
      ref={scroller}
      className="max-h-[calc(100dvh-22rem)] min-h-96 overflow-auto rounded-card bg-surface-1"
    >
      <table
        aria-label="Transactions"
        aria-rowcount={rows.length + 1}
        className="w-full table-fixed border-collapse text-[0.9375rem]"
      >
        <colgroup>
          <col className="w-[4.5rem]" />
          <col />
          <col className="w-[6.75rem]" />
          <col className="w-[6.5rem]" />
          <col className="w-48" />
          <col className="w-[5.5rem]" />
          <col className="w-[6.5rem]" />
        </colgroup>
        <thead className="sticky top-0 z-10 bg-surface-1 text-[0.8125rem] text-muted-foreground">
          <tr className="h-10 text-left">
            <th className="px-3 font-medium">Date</th>
            <th className="px-3 font-medium">Description</th>
            <th className="px-3 font-medium">Account</th>
            <th className="px-3 text-right font-medium">Amount</th>
            <th className="px-3 font-medium">Category</th>
            <th className="px-3 font-medium">Source</th>
            <th className="px-3 font-medium">Status</th>
          </tr>
        </thead>
        <tbody>
          {before > 0 && (
            <tr aria-hidden="true" style={{ height: before }}>
              <td colSpan={7} />
            </tr>
          )}
          {items.map((item) => {
            const txn = rows[item.index]
            const selected = txn.id === selectedId
            return (
              <tr
                key={txn.id}
                aria-rowindex={item.index + 2}
                data-selected={selected || undefined}
                style={{ height: ROW_HEIGHT }}
                onClick={() => onSelect(txn.id)}
                className={cn(
                  'cursor-pointer border-t border-outline-variant',
                  selected ? 'bg-primary-container/60' : 'hover:bg-surface-2',
                )}
              >
                <td className="px-3 leading-snug tabular-nums text-muted-foreground">{dateText(txn)}</td>
                <td className="px-3">
                  <button
                    type="button"
                    aria-label={`Details of ${txn.description}`}
                    aria-pressed={selected}
                    onClick={(event) => {
                      event.stopPropagation()
                      onSelect(txn.id)
                    }}
                    className="line-clamp-2 w-full text-left leading-snug outline-none focus-visible:underline"
                  >
                    {txn.description}
                  </button>
                </td>
                <td className="px-3 leading-snug text-muted-foreground">
                  {labels.get(txn.account_last4) ?? `Card ending ${txn.account_last4}`}
                </td>
                <td className="px-3 text-right whitespace-nowrap tabular-nums">{amountText(txn)}</td>
                <td className="px-2" onClick={(event) => event.stopPropagation()}>
                  <CategoryCombobox
                    value={txn.category}
                    categories={categories}
                    label={`Category for ${txn.description}`}
                    onPick={(category) => onChoose(txn, category)}
                    onClear={txn.category_source === 'person' ? () => onChoose(txn, null) : undefined}
                  />
                </td>
                <td className="px-3 text-muted-foreground">{sourceWord(txn)}</td>
                <td className="px-3">{isUnverified(txn) && <Unverified />}</td>
              </tr>
            )
          })}
          {after > 0 && (
            <tr aria-hidden="true" style={{ height: after }}>
              <td colSpan={7} />
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

/** The chips, the one-line count and the table, over a COMPLETE list of rows. */
function Ledger({
  rows,
  need,
}: {
  rows: readonly LedgerTransaction[]
  /** Open with "Needs a category" pressed (the link from Home). */
  need: boolean
}) {
  const spend = useSpend()
  const knownCategories = useRows(ledgerCategories)
  const rules = useRows(categoryRules)
  const setCategory = useSetTransactionCategory()

  const [filters, setFilters] = useState<TransactionFilters>({ ...NO_FILTERS, needCategory: need })
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    if (need) setFilters((current) => ({ ...current, needCategory: true }))
  }, [need])

  const labels = useMemo(() => accountLabels(rows, spend.data?.accounts), [rows, spend.data])
  const names = useMemo(
    () => categoryNames(rows, (knownCategories.data ?? []).map((category) => category.name)),
    [rows, knownCategories.data],
  )
  const months = useMemo(() => monthsPresent(rows), [rows])
  const needCount = useMemo(() => rows.filter((row) => row.category === null).length, [rows])
  const unverifiedCount = useMemo(() => rows.filter(isUnverified).length, [rows])
  const shown = useMemo(() => applyFilters(rows, filters).sort(newestFirst), [rows, filters])
  const net = useMemo(() => netByCurrency(shown), [shown])
  const selected = rows.find((row) => row.id === selectedId) ?? null

  function set<K extends keyof TransactionFilters>(key: K, value: TransactionFilters[K]) {
    setFilters((current) => ({ ...current, [key]: value }))
  }

  function choose(txn: LedgerTransaction, category: string | null) {
    setNotice(null)
    setCategory.mutateAsync({ txn, category }).catch((error: unknown) => {
      const said = error instanceof Error ? error.message : 'Nothing was saved.'
      setNotice(`${said} "${txn.description}" is back to ${txn.category ?? 'no category'}.`)
    })
  }

  return (
    <div className="flex flex-col gap-4">
      <div role="group" aria-label="Filters" className="flex flex-wrap items-center gap-2">
        <ChipSelect
          label="Account"
          value={filters.account}
          onChange={(value) => set('account', value)}
          options={[
            { value: 'all', label: 'All accounts' },
            ...[...labels].map(([last4, label]) => ({ value: last4, label })),
          ]}
        />
        <ChipSelect
          label="Month"
          value={filters.month}
          onChange={(value) => set('month', value)}
          options={[
            { value: 'all', label: 'All months' },
            ...months.map((month) => ({ value: month, label: monthLabel(month) })),
          ]}
        />
        <ChipSelect
          label="Category"
          value={filters.category}
          onChange={(value) => set('category', value)}
          options={[
            { value: 'all', label: 'All categories' },
            ...names.map((name) => ({ value: name, label: name })),
          ]}
        />
        <ToggleChip
          label="Needs a category"
          count={needCount}
          pressed={filters.needCategory}
          onToggle={() => set('needCategory', !filters.needCategory)}
        />
        <ToggleChip
          label="Unverified"
          count={unverifiedCount}
          pressed={filters.unverified}
          onToggle={() => set('unverified', !filters.unverified)}
        />
        {isFiltered(filters) && (
          <Button variant="ghost" size="sm" onClick={() => setFilters(NO_FILTERS)}>
            Clear filters
          </Button>
        )}
      </div>

      <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 text-[0.9375rem]">
        <p aria-live="polite">
          Showing {shown.length.toLocaleString('en-US')} of {rows.length.toLocaleString('en-US')} {rows.length === 1 ? 'transaction' : 'transactions'}.
        </p>
        {net.length > 0 && (
          <p className="inline-flex flex-wrap items-center gap-x-4 gap-y-1 tabular-nums">
            {net.map((figure) => (
              <span key={figure.currency} className="inline-flex flex-wrap items-center gap-x-2">
                <span className="text-muted-foreground">Net of these rows</span>
                {formatMoney(figure.cents, figure.currency)}
                {figure.unverified && <Unverified />}
              </span>
            ))}
          </p>
        )}
      </div>

      {notice && (
        <div className="flex items-center justify-between gap-3">
          <ErrorSentence>{notice}</ErrorSentence>
          <Button variant="ghost" size="sm" onClick={() => setNotice(null)}>
            Dismiss
          </Button>
        </div>
      )}

      {shown.length === 0 ? (
        <EmptySentence>No transactions match these filters.</EmptySentence>
      ) : (
        <div className="flex flex-col gap-4 xl:flex-row xl:items-start">
          <div className="min-w-0 flex-1">
            <Table
              rows={shown}
              resetKey={filters}
              labels={labels}
              categories={names}
              selectedId={selectedId}
              onSelect={setSelectedId}
              onChoose={choose}
            />
          </div>
          {selected && (
            <DetailPanel
              txn={selected}
              account={labels.get(selected.account_last4) ?? `Card ending ${selected.account_last4}`}
              categories={names}
              rules={rules.data ?? []}
              onChoose={choose}
              onClose={() => setSelectedId(null)}
            />
          )}
        </div>
      )}
    </div>
  )
}

export function TransactionsTab({ need }: { need: boolean }) {
  const { query, loaded } = useLedgerTransactions()
  return (
    <Panel
      title="Transactions"
      description="Everything the bank files brought in. The bank file's own category, a rule's, or yours: the Source column says which. Nothing here changes an amount or a date."
    >
      <Loaded
        query={query}
        what="transactions"
        quiet
        pending={
          loaded > 0
            ? `Still loading transactions: ${loaded.toLocaleString('en-US')} so far. Nothing is counted or totalled until every one has arrived.`
            : 'Still loading transactions. Nothing is counted or totalled until every one has arrived.'
        }
        empty="No transactions yet. They arrive with the daily bank pull."
        render={(rows) => <Ledger rows={rows} need={need} />}
      />
    </Panel>
  )
}
