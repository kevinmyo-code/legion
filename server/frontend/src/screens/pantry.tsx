import { Fragment, useMemo, useState } from 'react'

import {
  groceryStaples,
  receiptLineItems,
  receipts,
  type GroceryStaple,
  type Receipt,
  type ReceiptLineItem,
} from '@/api/aspects'
import { useRows, wire } from '@/api/synced'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { CrudPanel } from '@/components/workbench/crud-panel'
import { Loaded } from '@/components/workbench/loaded'
import { Estimate, EmptySentence, ErrorSentence, PageHeader, Panel, Unverified } from '@/components/workbench/page'
import type { FieldSpec, Values } from '@/components/workbench/record-form'
import {
  formatDay,
  formatInstantDate,
  formatMoney,
  formatNumber,
  fromLocalInput,
  toLocalInput,
} from '@/lib/figures'

/**
 * `/pantry`: grocery staples (a table the web can write) and receipts (a table
 * only the reconciliation gate writes, so read only here).
 *
 * The trust rules this screen carries are the ones web-and-households 06 wrote
 * down, and each is a sentence on screen rather than a colour:
 *
 * - **Macros on a receipt are an estimate** (CLAUDE.md section 4 rule 5): a
 *   receipt never prints calories or protein, so every line's four figures are a
 *   model's guess from the product name. The word `estimate` is printed beside
 *   them, on every line that has them.
 * - **`unaccounted_cents` is "unaccounted", never tax.** It is the part of the
 *   printed total the captured lines do not explain, stored in its own column so
 *   a missed line is never absorbed into a figure that makes the arithmetic
 *   balance by construction (rule 7's 2026-08-26 amendment).
 * - **A receipt the gate could not verify says `unverified`**, in words, on its
 *   row; it never reads as a verified one.
 */

// ---- Staples --------------------------------------------------------------

const STAPLE_FIELDS: FieldSpec[] = [
  { name: 'display_name', label: 'Item', kind: 'text', required: true, placeholder: 'Oat milk' },
  {
    name: 'times_bought',
    label: 'Times ticked',
    kind: 'number',
    required: true,
    min: '1',
    step: '1',
    hint: 'How many trips this was ticked off a grocery list. At least 1.',
  },
  { name: 'last_bought_at', label: 'Last ticked', kind: 'datetime', required: true },
]

function stapleInitial(row: GroceryStaple | null): Values {
  return {
    display_name: row?.display_name ?? '',
    times_bought: String(row?.times_bought ?? 1),
    last_bought_at: toLocalInput(row?.last_bought_at ?? new Date()),
  }
}

function StaplesPanel() {
  return (
    <CrudPanel
      table={groceryStaples}
      title="Grocery staples"
      description="Items that have been ticked off a grocery list on a trip. A tick records a tap, not a purchase."
      addLabel="Add a staple"
      what="grocery staples"
      empty="No staples yet. An item becomes a staple once it has been ticked off a grocery list on a trip, or you can add one here."
      columns={[
        { header: 'Item', cell: (row) => <span className="font-medium">{row.display_name}</span> },
        { header: 'Times ticked', align: 'right', cell: (row) => row.times_bought },
        { header: 'Last ticked', cell: (row) => formatInstantDate(row.last_bought_at) },
      ]}
      sort={(a, b) => b.times_bought - a.times_bought || a.display_name.localeCompare(b.display_name)}
      fields={STAPLE_FIELDS}
      identity={(row) => row.origin_guid}
      initial={stapleInitial}
      toBody={(values, row, guid) =>
        wire<GroceryStaple>({
          // The key the phone matches a staple on. An existing staple keeps its
          // own; a new one is the spelling, lowercased.
          name: row?.name ?? values.display_name.trim().toLowerCase(),
          display_name: values.display_name.trim(),
          times_bought: Math.round(Number(values.times_bought)),
          last_bought_at: fromLocalInput(values.last_bought_at),
          origin_guid: guid,
        })
      }
      rowLabel={(row) => `"${row.display_name}"`}
      deleteConsequence="This staple is removed for everyone in the household."
    />
  )
}

// ---- Receipts -------------------------------------------------------------

/** How the engine's provenance tag reads to a person. `UNRECONCILED` is the
 * chip with the word `unverified`; the gated ones say what verified them. */
function Check({ provenance }: { provenance: Receipt['provenance'] }) {
  switch (provenance) {
    case 'UNRECONCILED':
      return <Unverified />
    case 'DETERMINISTIC':
      return <span>Verified, read by a parser</span>
    case 'LLM_RECONCILED':
      return <span>Verified, read by a model</span>
    case 'USER':
      return <span>Entered by hand</span>
  }
}

function macroNumber(value: string | null): string | null {
  if (value === null) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? formatNumber(parsed) : null
}

/** The four macro figures of a line, each unit spelled, and the word estimate. */
function Macros({ line }: { line: ReceiptLineItem }) {
  const parts = [
    ['kcal', macroNumber(line.estimated_calories_kcal), ''],
    ['protein', macroNumber(line.estimated_protein_g), ' g'],
    ['carbs', macroNumber(line.estimated_carbs_g), ' g'],
    ['fat', macroNumber(line.estimated_fat_g), ' g'],
  ] as const
  const present = parts.filter(([, value]) => value !== null)
  if (present.length === 0) return <span className="text-muted-foreground">No macro estimate</span>
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2">
      <span>
        {present
          .map(([name, value, unit]) => (name === 'kcal' ? `${value} kcal` : `${value}${unit} ${name}`))
          .join(', ')}
      </span>
      <Estimate />
    </span>
  )
}

function LineItems({ receipt, lines }: { receipt: Receipt; lines: ReceiptLineItem[] }) {
  if (lines.length === 0) {
    return <EmptySentence>This receipt has no line items stored.</EmptySentence>
  }
  return (
    <div className="flex flex-col gap-3">
      <dl className="flex flex-wrap gap-x-8 gap-y-1 text-[0.9375rem]">
        {[
          ['Subtotal', receipt.subtotal_cents],
          ['Tax', receipt.tax_cents],
          ['Other charges', receipt.other_charges_cents],
        ].map(([name, cents]) => (
          <div key={name as string} className="flex gap-2">
            <dt className="text-muted-foreground">{name} printed</dt>
            <dd className="tabular-nums">
              {cents === null ? 'not stated' : formatMoney(cents as number, receipt.currency)}
            </dd>
          </div>
        ))}
        <div className="flex gap-2">
          <dt className="text-muted-foreground">Total printed</dt>
          <dd className="font-medium tabular-nums">{formatMoney(receipt.total_cents, receipt.currency)}</dd>
        </div>
      </dl>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Line</TableHead>
            <TableHead className="text-right">Qty</TableHead>
            <TableHead className="text-right">Unit price</TableHead>
            <TableHead className="text-right">Line total</TableHead>
            <TableHead>Macros</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {lines.map((line) => (
            <TableRow key={line.id}>
              <TableCell>
                {line.name}
                {line.reversal_of !== null && (
                  <span className="ml-2 text-[0.8125rem] text-muted-foreground">reversal of an earlier line</span>
                )}
              </TableCell>
              <TableCell className="text-right">{formatNumber(Number(line.quantity), 3)}</TableCell>
              <TableCell className="text-right">
                {line.unit_price_cents === null ? 'not stated' : formatMoney(line.unit_price_cents, receipt.currency)}
              </TableCell>
              <TableCell className="text-right">{formatMoney(line.total_price_cents, receipt.currency)}</TableCell>
              <TableCell>
                <Macros line={line} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

const FIRST_RECEIPTS = 25

function ReceiptsPanel() {
  const query = useRows(receipts)
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
  const [showAll, setShowAll] = useState(false)
  // Line items are one unfiltered table: read it only once a receipt is opened.
  const lines = useRows(receiptLineItems, open.size > 0)

  const linesByReceipt = useMemo(() => {
    const grouped = new Map<string, ReceiptLineItem[]>()
    for (const line of lines.data ?? []) {
      const list = grouped.get(line.receipt_id) ?? []
      list.push(line)
      grouped.set(line.receipt_id, list)
    }
    return grouped
  }, [lines.data])

  function toggle(id: string) {
    setOpen((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  return (
    <Panel
      title="Receipts"
      description="Read only: receipts come from the reconciliation gate, never from a form. Macros on a receipt line are never printed on the receipt, so they are estimates."
    >
      <Loaded
        query={query}
        what="receipts"
        quiet
        empty="No receipts yet. They appear here once a receipt photo has been read."
        render={(rows) => {
          const sorted = [...rows].sort(
            (a, b) => b.purchase_date.localeCompare(a.purchase_date) || b.created_at.localeCompare(a.created_at),
          )
          const shown = showAll ? sorted : sorted.slice(0, FIRST_RECEIPTS)
          return (
            <div className="flex flex-col gap-3">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Date</TableHead>
                    <TableHead>Store</TableHead>
                    <TableHead className="text-right">Total</TableHead>
                    <TableHead>Check</TableHead>
                    <TableHead className="w-0">
                      <span className="sr-only">Lines</span>
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {shown.map((receipt) => {
                    const expanded = open.has(receipt.id)
                    return (
                      <Fragment key={receipt.id}>
                        <TableRow>
                          <TableCell>{formatDay(receipt.purchase_date)}</TableCell>
                          <TableCell className="font-medium">{receipt.store}</TableCell>
                          <TableCell className="text-right">
                            <div>{formatMoney(receipt.total_cents, receipt.currency)}</div>
                            {receipt.unaccounted_cents !== null && (
                              <div className="text-[0.8125rem] text-muted-foreground">
                                {formatMoney(receipt.unaccounted_cents, receipt.currency)} unaccounted
                              </div>
                            )}
                          </TableCell>
                          <TableCell>
                            <Check provenance={receipt.provenance} />
                          </TableCell>
                          <TableCell>
                            <Button
                              variant="ghost"
                              size="sm"
                              aria-expanded={expanded}
                              aria-label={`${expanded ? 'Hide' : 'Show'} lines for ${receipt.store}, ${receipt.purchase_date}`}
                              onClick={() => toggle(receipt.id)}
                            >
                              {expanded ? 'Hide lines' : 'Show lines'}
                            </Button>
                          </TableCell>
                        </TableRow>
                        {expanded && (
                          <TableRow className="hover:bg-transparent">
                            <TableCell colSpan={5} className="bg-surface-2/60 py-4">
                              <div className="flex flex-col gap-3">
                                {receipt.unaccounted_cents !== null && (
                                  <p className="rounded-control bg-surface-3 px-4 py-3 text-[0.9375rem]">
                                    <span className="font-medium">
                                      {formatMoney(receipt.unaccounted_cents, receipt.currency)} unaccounted.
                                    </span>{' '}
                                    The printed total is more than the lines captured here explain, and the
                                    difference could not be checked again. It is not tax and it is not added
                                    to any figure.
                                  </p>
                                )}
                                {lines.isPending ? (
                                  <p className="text-[0.9375rem] text-muted-foreground">Reading line items.</p>
                                ) : lines.data === undefined ? (
                                  <ErrorSentence>
                                    Could not reach the engine, so these are not the real line items.{' '}
                                    {lines.error?.message}
                                  </ErrorSentence>
                                ) : (
                                  <LineItems receipt={receipt} lines={linesByReceipt.get(receipt.id) ?? []} />
                                )}
                              </div>
                            </TableCell>
                          </TableRow>
                        )}
                      </Fragment>
                    )
                  })}
                </TableBody>
              </Table>
              {!showAll && sorted.length > FIRST_RECEIPTS && (
                <div>
                  <Button variant="secondary" onClick={() => setShowAll(true)}>
                    Show all {sorted.length} receipts
                  </Button>
                </div>
              )}
            </div>
          )
        }}
      />
    </Panel>
  )
}

export function PantryScreen() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Pantry"
        subtitle="What has been ticked off the grocery list, and the receipts that were read."
      />
      <StaplesPanel />
      <ReceiptsPanel />
    </div>
  )
}
