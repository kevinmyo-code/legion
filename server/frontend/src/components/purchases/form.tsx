import { Check, Lock, Users } from 'lucide-react'
import { useId, useRef, useState } from 'react'

import { useLogPurchase, useUpdatePurchase, type PurchaseFields } from '@/api/purchases'
import type { Purchase } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { todayEpochDay } from '@/lib/day'
import {
  ENTERED_BY_HAND,
  epochDayForIso,
  isoForEpochDay,
  parsePriceCents,
  priceFieldText,
} from '@/lib/purchases'

/** A fresh idempotency key. `crypto.randomUUID` needs a secure context; the
 * fallback only has to be unique, not secret. */
function newSyncId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * The "Log it" form, for a new entry or an edit of one's own.
 *
 * What it promises, because Mia is typing into it on a phone:
 *  - **Saved only on a 2xx.** Nothing is optimistic: `onSaved` is called after the
 *    engine said yes and not before.
 *  - **A failure keeps what was typed** and says in words what did not happen
 *    ("Can't reach the bought log right now. Nothing was logged.").
 *  - **A price is what someone typed**: the form says so beside the field.
 *  - **Private is a switch with its meaning spelled out** (ADR 0052): the lock
 *    and "Only you can see this", or "Shared with the household".
 *
 * A retry after a lost answer reuses one `sync_id`, so the engine hands back the
 * entry it already made instead of logging a second.
 */
export function PurchaseForm({
  entry,
  defaultItem = '',
  onSaved,
  onCancel,
}: {
  /** Present to edit that entry; absent to log a new one. */
  entry?: Purchase
  defaultItem?: string
  onSaved: (saved: Purchase | undefined) => void
  onCancel?: () => void
}) {
  const uid = useId()
  const today = todayEpochDay()
  const [item, setItem] = useState(entry?.item ?? defaultItem)
  const [day, setDay] = useState(isoForEpochDay(entry?.bought_on ?? today))
  const [store, setStore] = useState(entry?.store ?? '')
  const [price, setPrice] = useState(priceFieldText(entry?.price_cents))
  const [note, setNote] = useState(entry?.quantity_note ?? '')
  const [priv, setPriv] = useState(entry?.visibility === 'private')
  const [problem, setProblem] = useState<string | null>(null)
  const syncId = useRef(newSyncId())

  const log = useLogPurchase()
  const update = useUpdatePurchase()
  const busy = log.isPending || update.isPending
  const didNot = entry ? 'Nothing was changed.' : 'Nothing was logged.'

  function submit(event: React.FormEvent) {
    event.preventDefault()
    const name = item.trim()
    if (name === '') return setProblem(`Say what was bought. ${didNot}`)
    const boughtOn = epochDayForIso(day)
    if (boughtOn === null) return setProblem(`Pick the day it was bought. ${didNot}`)
    const parsed = parsePriceCents(price)
    if (!parsed.ok) return setProblem(`Price should look like 8.99, or leave it empty. ${didNot}`)
    setProblem(null)
    const fields: PurchaseFields = {
      item: name,
      bought_on: boughtOn,
      store: store.trim() === '' ? null : store.trim(),
      price_cents: parsed.cents,
      quantity_note: note.trim() === '' ? null : note.trim(),
      visibility: priv ? 'private' : 'shared',
    }
    const done = {
      onSuccess: (saved: Purchase | undefined) => {
        // The next new entry is a new entry, not a retry of this one.
        syncId.current = newSyncId()
        onSaved(saved)
      },
      onError: (error: Error) => setProblem(`${error.message} What you typed is still here.`),
    }
    if (entry) update.mutate({ id: entry.id, fields }, done)
    else log.mutate({ fields, syncId: syncId.current }, done)
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={submit} noValidate>
      {problem && (
        <p
          role="alert"
          className="rounded-control bg-destructive-container px-4 py-3 text-[0.9375rem] text-destructive-container-foreground"
        >
          {problem}
        </p>
      )}
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${uid}-item`}>What was bought</Label>
        <Input
          id={`${uid}-item`}
          value={item}
          onChange={(event) => setItem(event.target.value)}
          placeholder="e.g. Shampoo"
          autoComplete="off"
        />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${uid}-day`}>Bought on</Label>
        <Input
          id={`${uid}-day`}
          type="date"
          value={day}
          max={isoForEpochDay(today)}
          onChange={(event) => setDay(event.target.value)}
        />
        <p className="px-1 text-[0.8125rem] text-muted-foreground">Today unless you change it.</p>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${uid}-store`}>
            Store <span className="font-normal text-muted-foreground">(optional)</span>
          </Label>
          <Input
            id={`${uid}-store`}
            value={store}
            onChange={(event) => setStore(event.target.value)}
            placeholder="HEB"
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${uid}-price`}>
            Price <span className="font-normal text-muted-foreground">(optional)</span>
          </Label>
          <Input
            id={`${uid}-price`}
            value={price}
            inputMode="decimal"
            onChange={(event) => setPrice(event.target.value)}
            placeholder="8.99"
          />
        </div>
      </div>
      <p className="-mt-2 px-1 text-[0.8125rem] text-muted-foreground">
        A price is {ENTERED_BY_HAND}. It is not checked against the bank and never counts in Money.
      </p>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${uid}-note`}>
          Quantity or note <span className="font-normal text-muted-foreground">(optional)</span>
        </Label>
        <Input
          id={`${uid}-note`}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          placeholder="2 bottles"
        />
      </div>
      <div className="flex items-center justify-between gap-4 rounded-control bg-surface-2 px-4 py-3">
        <div className="min-w-0">
          <p className="flex items-center gap-1.5 text-[0.9375rem] font-medium">
            {priv ? (
              <>
                <Lock className="size-4" aria-hidden="true" /> Private
              </>
            ) : (
              <>
                <Users className="size-4" aria-hidden="true" /> Shared with the household
              </>
            )}
          </p>
          <p className="text-[0.8125rem] text-muted-foreground">
            {priv
              ? 'Only you can see this. Nobody else sees it in the log or asks about it.'
              : 'Anyone in the household can see it and ask about it.'}
          </p>
        </div>
        <Switch checked={priv} onCheckedChange={setPriv} aria-label="Only me" />
      </div>
      <div className="flex items-center justify-end gap-2">
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
        )}
        <Button type="submit" size="lg" disabled={busy}>
          <Check />
          {busy ? 'Saving' : entry ? 'Save changes' : 'Log it'}
        </Button>
      </div>
    </form>
  )
}
