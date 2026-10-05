import { History, Lock, Pencil, ListChecks } from 'lucide-react'
import { useState, type ReactNode } from 'react'

import { useDeletePurchase } from '@/api/purchases'
import type { Purchase } from '@/api/types'
import { PurchaseForm } from '@/components/purchases/form'
import { InlineConfirm } from '@/components/settings/inline-confirm'
import { Button } from '@/components/ui/button'
import { VisibilityMark } from '@/components/visibility-mark'
import { dateForEpochDay } from '@/lib/day'
import {
  ENTERED_BY_HAND,
  dayLabel,
  formatCents,
  mayChange,
  sourceLabel,
} from '@/lib/purchases'
import { cn } from '@/lib/utils'

/** The chip each source wears. The words are the meaning; the glyph is decoration. */
export function SourceChip({ source }: { source: string }) {
  const Icon = source === 'GROCERIES_TICK' ? ListChecks : source === 'MANUAL' ? Pencil : History
  return (
    <span className="inline-flex h-6 items-center gap-1 rounded-full bg-surface-3 px-2.5 text-[0.8125rem] whitespace-nowrap text-muted-foreground">
      <Icon className="size-3.5" aria-hidden="true" />
      {sourceLabel(source)}
    </span>
  )
}

/** A typed price, with the words that say so beside it, always. */
export function PriceText({ cents }: { cents: number }) {
  return (
    <span className="tabular-nums">
      {formatCents(cents)} <em className="text-[0.8125rem] text-muted-foreground not-italic">{ENTERED_BY_HAND}</em>
    </span>
  )
}

/** "Only you can see this", with the lock: the words are the disclosure. */
export function PrivateNote({ className }: { className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-1 text-[0.8125rem] text-muted-foreground', className)}>
      <Lock className="size-3.5" aria-hidden="true" />
      Only you can see this
    </span>
  )
}

function whoText(entry: Purchase): string {
  return entry.logged_by ? entry.logged_by : 'Logged by: not recorded'
}

function loggedLine(entry: Purchase): string {
  if (entry.source === 'GROCERIES_BACKFILL') {
    return 'Imported from a Groceries tick made before the log existed; who ticked it was never recorded.'
  }
  const at = new Date(entry.logged_at).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
  return `Logged ${at}.`
}

/** The details one entry opens to: note, how it got here, and, for the person
 * who may change it, edit and delete. Shared by the phone row and the desk table. */
export function EntryDetails({
  entry,
  today,
  onEdit,
}: {
  entry: Purchase
  today: number
  /** Where editing happens when it is not in this block (the desk's side panel). */
  onEdit?: () => void
}) {
  const [mode, setMode] = useState<'view' | 'edit' | 'delete'>('view')
  const remove = useDeletePurchase()
  const weekday = dateForEpochDay(entry.bought_on).toLocaleDateString(undefined, { weekday: 'long' })

  if (mode === 'edit') {
    return (
      <div className="mt-3">
        <PurchaseForm entry={entry} onSaved={() => setMode('view')} onCancel={() => setMode('view')} />
      </div>
    )
  }

  return (
    <div className="mt-2 flex flex-col gap-1.5 text-[0.9375rem] text-muted-foreground">
      {entry.quantity_note && (
        <p>
          Quantity / note: <span className="text-foreground">{entry.quantity_note}</span>
        </p>
      )}
      <p>
        Bought {weekday}, {dayLabel(entry.bought_on, today)}. {loggedLine(entry)}
      </p>
      {entry.price_cents != null && entry.price_note && (
        <p>The price is {entry.price_note}.</p>
      )}
      {mayChange(entry) && mode === 'view' && (
        <div className="-ml-3 flex flex-wrap gap-1">
          <Button variant="ghost" size="sm" onClick={() => (onEdit ? onEdit() : setMode('edit'))}>
            Edit this entry
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setMode('delete')}>
            Delete this entry
          </Button>
        </div>
      )}
      {mode === 'delete' && (
        <InlineConfirm
          question={`Delete "${entry.item}" from the bought log?`}
          consequence="It stops answering when did we last buy it. Nothing else changes: a Groceries tick that made it stays ticked."
          confirmLabel="Delete entry"
          busyLabel="Deleting"
          onConfirm={() => remove.mutateAsync(entry.id)}
          onClose={() => setMode('view')}
        />
      )}
    </div>
  )
}

/** One entry on the phone: the date, the item as it was logged, who, where it
 * came from, the price in words. Tapping opens the details. */
export function EntryRow({
  entry,
  today,
  inlineDate = false,
}: {
  entry: Purchase
  today: number
  inlineDate?: boolean
}) {
  const [open, setOpen] = useState(false)
  const date = dateForEpochDay(entry.bought_on)
  const month = date.toLocaleDateString(undefined, { month: 'short' })
  const money: ReactNode[] = []
  if (entry.store) money.push(<span key="store">{entry.store}</span>)
  if (entry.price_cents != null) money.push(<PriceText key="price" cents={entry.price_cents} />)

  return (
    <li className="rounded-card bg-card px-3 py-2">
      <button
        type="button"
        aria-expanded={open}
        className="flex min-h-14 w-full items-start gap-3 rounded-control py-1 text-left outline-none focus-visible:outline-2 focus-visible:outline-primary"
        onClick={() => setOpen((value) => !value)}
      >
        {!inlineDate && (
          <span aria-hidden="true" className="flex w-11 shrink-0 flex-col items-center leading-tight">
            <b className="text-lg font-semibold tabular-nums">{date.getDate()}</b>
            <small className="text-xs text-muted-foreground">{month}</small>
          </span>
        )}
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="text-base font-medium">
            {entry.item}
            {inlineDate && (
              <span className="font-normal text-muted-foreground"> · {dayLabel(entry.bought_on, today)}</span>
            )}
          </span>
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem] text-muted-foreground">
            <span className="sr-only">Bought {dayLabel(entry.bought_on, today)}. </span>
            <span>{whoText(entry)}</span>
            <SourceChip source={entry.source} />
          </span>
          {money.length > 0 && (
            <span className="flex flex-wrap items-center gap-x-2 text-[0.9375rem]">
              {money.map((node, index) => (
                <span key={index} className="inline-flex items-center gap-2">
                  {index > 0 && <span aria-hidden="true">·</span>}
                  {node}
                </span>
              ))}
            </span>
          )}
          {entry.visibility === 'private' && <PrivateNote />}
        </span>
      </button>
      {open && <EntryDetails entry={entry} today={today} />}
    </li>
  )
}

/** Who sees it, for the desk table: the shared mark the rest of the web uses, or
 * the lock and the sentence for a private entry. */
export function SeenBy({ entry }: { entry: Purchase }) {
  return entry.visibility === 'private' ? <PrivateNote /> : <VisibilityMark visibility="shared" />
}
