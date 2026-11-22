import { useQuery, useQueryClient } from '@tanstack/react-query'
import { PhoneCall, ReceiptText, ShoppingBag, X } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import {
  type CallCard,
  type CallbackResult,
  fetchCallCard,
  fetchShopState,
  money,
  SHOP_STATE_KEY,
  sendCallback,
  setScenario,
  type ShopState,
} from '@/lib/store-rep'

import { CallCardDialog } from './call-card'

/**
 * The store-rep demo on /try (Kevin, 2026-11-22): a pill that opens a small panel beside the page.
 * The panel switches the visit into the scenario (the same switch Alfred's voice tool uses), shows
 * the made-up catalogue, this visit's orders, the callback form, and the after-call card, which
 * also opens by itself when a conversation that used the store rep closes.
 *
 * Self-contained on purpose: the page hooks it in with one line (`screens/try.tsx`), and it reads
 * the conversation only through `useAssistant`, so the page's own layout can change around it.
 */
export function StoreRepPanel() {
  const { state } = useAssistant()
  const queryClient = useQueryClient()
  const shop = useQuery({ queryKey: SHOP_STATE_KEY, queryFn: fetchShopState, staleTime: 5_000 })
  const [open, setOpen] = useState(false)
  const [card, setCard] = useState<CallCard | null>(null)
  const [cardOpen, setCardOpen] = useState(false)
  const panelId = useId()

  // After each answer (the reply finished, or the conversation ended) the scenario, the orders and
  // the callback state may have changed by voice: read them again.
  const settled = state.activity === 'idle' || state.activity === 'listening'
  const turns = state.lines.length
  useEffect(() => {
    if (settled) void queryClient.invalidateQueries({ queryKey: SHOP_STATE_KEY })
  }, [settled, turns, state.phase, queryClient])

  // A conversation that used the store rep ends: the after-call card opens by itself. On the
  // cascade a conversation is `live` from its first turn until it closes (a sign-off, the mic
  // window, the time limit), which leaves it `idle` or `ended`.
  const usedShop = useRef(false)
  const lastPhase = useRef(state.phase)
  useEffect(() => {
    if (shop.data?.active && state.phase === 'live') usedShop.current = true
  }, [shop.data?.active, state.phase])
  useEffect(() => {
    const was = lastPhase.current
    lastPhase.current = state.phase
    if (was === 'live' && state.phase !== 'live' && usedShop.current) {
      usedShop.current = false
      void showCard()
    }
  }, [state.phase])

  async function showCard() {
    setCard(await fetchCallCard())
    setCardOpen(true)
  }

  async function toggle(active: boolean) {
    const next = await setScenario(active)
    if (next) queryClient.setQueryData(SHOP_STATE_KEY, next)
  }

  if (!shop.data) return null
  const data = shop.data
  return (
    <>
      <div className="fixed bottom-[calc(env(safe-area-inset-bottom)+6.5rem)] left-4 z-40 flex flex-col items-start gap-2">
        {open && (
          <section
            id={panelId}
            aria-label={`${data.shop_name} store-rep demo`}
            className="flex max-h-[min(70vh,36rem)] w-[min(22rem,calc(100vw-2rem))] flex-col gap-3 overflow-y-auto rounded-xl border border-outline-variant bg-card p-4 text-card-foreground shadow-lg"
          >
            <header className="flex items-start justify-between gap-2">
              <div>
                <h2 className="text-lg font-semibold">{data.shop_name}</h2>
                <p className="text-[0.8125rem] text-muted-foreground">
                  A made-up shop. Alfred plays its rep; nothing is charged or shipped.
                </p>
              </div>
              <Button variant="ghost" size="icon-sm" aria-label="Close the store-rep panel" onClick={() => setOpen(false)}>
                <X />
              </Button>
            </header>
            <ScenarioSwitch state={data} onToggle={toggle} />
            <Catalogue state={data} />
            {data.orders.length > 0 && <Orders state={data} />}
            {data.active && <Callback state={data} />}
            <Button variant="outline" onClick={() => void showCard()}>
              <ReceiptText />
              After-call record
            </Button>
          </section>
        )}
        <Button
          variant={data.active ? 'default' : 'outline'}
          className="rounded-full bg-card shadow-md data-[active=true]:bg-primary"
          data-active={data.active}
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen(!open)}
        >
          <ShoppingBag />
          {data.active ? 'Store rep: on' : 'Try: store rep'}
        </Button>
      </div>
      <CallCardDialog card={card} open={cardOpen} onOpenChange={setCardOpen} />
    </>
  )
}

function ScenarioSwitch({ state, onToggle }: { state: ShopState; onToggle: (active: boolean) => Promise<void> }) {
  const [busy, setBusy] = useState(false)
  const flip = async () => {
    setBusy(true)
    try {
      await onToggle(!state.active)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="flex flex-col gap-2">
      <Button onClick={() => void flip()} disabled={busy} variant={state.active ? 'outline' : 'default'}>
        {state.active ? 'Back to the LEGION guide' : 'Talk to the store rep'}
      </Button>
      <p className="text-[0.8125rem] text-muted-foreground">
        {state.active
          ? 'Tap the orb and shop: "I need a daypack for weekend hikes." Try "that\'s too expensive", then buy something.'
          : 'Or just ask Alfred: "Pretend you\'re a store rep."'}
      </p>
    </div>
  )
}

function Catalogue({ state }: { state: ShopState }) {
  return (
    <details className="group">
      <summary className="cursor-pointer text-sm font-semibold">Catalogue ({state.products.length})</summary>
      <ul className="mt-2 flex flex-col divide-y divide-outline-variant">
        {state.products.map((p) => (
          <li key={p.sku} className="flex items-baseline justify-between gap-3 py-1.5 text-sm">
            <span className="min-w-0">
              <span className="block truncate">{p.name}</span>
              <span className="text-xs text-muted-foreground">
                {p.sku} · {p.in_stock ? `${p.in_stock} in stock` : 'out of stock'}
              </span>
            </span>
            <span className="shrink-0 tabular-nums">{money(p.price_cents)}</span>
          </li>
        ))}
      </ul>
    </details>
  )
}

function Orders({ state }: { state: ShopState }) {
  return (
    <div className="flex flex-col gap-1">
      <h3 className="text-sm font-semibold">This visit&apos;s orders</h3>
      <ul className="flex flex-col gap-1">
        {state.orders.map((o) => (
          <li key={o.ref} className="flex items-center justify-between gap-2 text-sm">
            <span>
              {o.ref}{' '}
              <span className="text-xs text-muted-foreground">{o.status === 'placed' ? 'placed' : 'read back, not placed'}</span>
            </span>
            <span className="tabular-nums">{money(o.total_cents)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

const CALLBACK_WORDS: Record<string, string> = {
  off: 'Hand-off to a person is switched off on this demo.',
  already: 'You have already asked for a callback on this visit.',
  day_full: "Today's callback requests are used up.",
  no_visit: 'Callbacks work only on a demo visit.',
}

function Callback({ state }: { state: ShopState }) {
  const queryClient = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const [result, setResult] = useState<CallbackResult | null>(null)
  const [busy, setBusy] = useState(false)
  if (result?.ok) return <p role="status" className="text-sm">{result.detail}</p>
  if (state.callback !== 'available') {
    return <p className="text-[0.8125rem] text-muted-foreground">{CALLBACK_WORDS[state.callback]}</p>
  }
  if (!showForm) {
    return (
      <Button variant="outline" onClick={() => setShowForm(true)}>
        <PhoneCall />
        Ask for a person
      </Button>
    )
  }
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setBusy(true)
    const sent = await sendCallback({
      name: String(form.get('name') ?? ''),
      contact: String(form.get('contact') ?? ''),
      note: String(form.get('note') ?? ''),
      website: String(form.get('website') ?? ''),
    })
    setBusy(false)
    setResult(sent)
    if (sent.ok) void queryClient.invalidateQueries({ queryKey: SHOP_STATE_KEY })
  }
  return (
    <form className="flex flex-col gap-2" onSubmit={(event) => void submit(event)} aria-label="Ask for a callback">
      <p className="text-[0.8125rem] text-muted-foreground">
        Kevin gets your name, contact and a summary of this call. Nobody is dialled automatically.
      </p>
      <Input name="name" required maxLength={60} placeholder="Your name" aria-label="Your name" autoComplete="name" />
      <Input
        name="contact"
        required
        maxLength={120}
        placeholder="Phone or email"
        aria-label="Phone or email"
        autoComplete="email"
      />
      <Textarea name="note" maxLength={280} placeholder="Anything to add (optional)" aria-label="Note" rows={2} />
      <input name="website" tabIndex={-1} autoComplete="off" aria-hidden="true" className="hidden" defaultValue="" />
      {result && !result.ok && (
        <p role="alert" className="text-[0.8125rem] text-destructive">
          {result.detail}
        </p>
      )}
      <Button type="submit" disabled={busy}>
        Send to Kevin
      </Button>
    </form>
  )
}
