import { useQuery, useQueryClient } from '@tanstack/react-query'
import { PhoneCall, ReceiptText } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import { CascadeClient } from '@/assistant/cascade-client'
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

import { CallCardView } from './call-card'
import { ShopMark } from './shop-mark'

/**
 * The store-rep demo as a section of /try (Kevin's ruling 2026-11-22: a section of its own).
 * Under the section's own words: the shop's mark, one control that puts the visit in the
 * store-rep scenario (the same engine switch Alfred's voice tool uses) and starts talking in the
 * same tap, the made-up catalogue, this visit's orders, the callback form, and the after-call card,
 * which appears here by itself when a conversation that used the store rep ends.
 *
 * It replaces the floating pill and its panel: one way in, in the page's reading order, and nothing
 * left over the page's corner where the phone's foot bar and the orb rest. The engine decides
 * everything; this reads the conversation only through `useAssistant`.
 */
export function StoreRepSection() {
  const { client, state } = useAssistant()
  const queryClient = useQueryClient()
  const shop = useQuery({ queryKey: SHOP_STATE_KEY, queryFn: fetchShopState, staleTime: 5_000 })
  const [card, setCard] = useState<CallCard | null>(null)
  const [cardAsked, setCardAsked] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const [switching, setSwitching] = useState(false)
  const switchingNow = useRef(false)
  const [leaveError, setLeaveError] = useState<string | null>(null)
  const cascade = client instanceof CascadeClient ? client : null

  // After each answer the scenario, the orders and the callback state may have changed by voice.
  const settled = state.activity === 'idle' || state.activity === 'listening'
  const turns = state.lines.length
  useEffect(() => {
    if (settled) void queryClient.invalidateQueries({ queryKey: SHOP_STATE_KEY })
  }, [settled, turns, state.phase, queryClient])

  // A conversation that used the store rep ends: its after-call card shows here by itself. On the
  // cascade a conversation is `live` from its first turn until it closes.
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
    setCardAsked(true)
  }

  // Still asking: the mark alone (never the no-visit line to a visitor whose state is on its way).
  if (shop.isPending) {
    return (
      <div className="rc-store">
        <ShopMark className="rc-shop-mark" />
      </div>
    )
  }
  // No visit (a member's preview), or a conversation that is not the engine's own voice (the Live
  // fallback does not play the rep): the mark and a line, no controls.
  if (!shop.data || !cascade) {
    return (
      <div className="rc-store">
        <ShopMark className="rc-shop-mark" />
        <p className="rc-store-hint">
          {shop.data
            ? "The store rep runs on the engine's own voice, which this visit is not using right now."
            : 'The store rep runs on a demo visit: open the page with the access code to talk to it.'}
        </p>
      </div>
    )
  }
  const data = shop.data
  const voice = cascade

  /**
   * In the tap: the scenario switch goes to the engine and a fresh call opens at once (an open
   * conversation with the guide ends first, so the rep always greets); the greeting waits for the
   * switch, and a tap or close meanwhile cancels it (`CascadeClient.startVoice`).
   */
  function talk() {
    if (switchingNow.current) return
    switchingNow.current = true
    if (state.phase !== 'idle') voice.reset()
    // Busy until the switch lands: a second click would cut the rep's hello and switch twice.
    setSwitching(true)
    const switched = setScenario(true)
      .then((next) => {
        if (next) queryClient.setQueryData(SHOP_STATE_KEY, next)
      })
      .finally(() => {
        switchingNow.current = false
        setSwitching(false)
      })
    void voice.startVoice(switched)
  }

  async function leave() {
    setLeaving(true)
    setLeaveError(null)
    try {
      const next = await setScenario(false)
      if (next) queryClient.setQueryData(SHOP_STATE_KEY, next)
      else setLeaveError('The engine did not switch back. Try again.')
    } finally {
      setLeaving(false)
    }
  }

  return (
    <div className="rc-store" data-active={data.active ? 'true' : 'false'}>
      <ShopMark className="rc-shop-mark" title={`${data.shop_name}, a made-up shop`} />
      <div className="rc-store-controls">
        <Button className="rc-store-talk" onClick={talk} disabled={switching}>
          Talk to the store rep
        </Button>
        {data.active && (
          <Button variant="outline" onClick={() => void leave()} disabled={leaving}>
            Back to the LEGION guide
          </Button>
        )}
        {leaveError && (
          <p role="alert" className="text-destructive">
            {leaveError}
          </p>
        )}
        <p className="rc-store-hint">
          {data.active
            ? `The rep of ${data.shop_name}, a made-up outdoor shop, is on: nothing is charged or shipped. Try "I need a daypack for weekend hikes", then "that's too expensive", then buy something.`
            : `The orb becomes the rep of ${data.shop_name}, a made-up outdoor shop. Nothing is charged or shipped.`}
        </p>
      </div>
      <Catalogue state={data} />
      {data.orders.length > 0 && <Orders state={data} />}
      {data.active && <Callback state={data} />}
      <div className="rc-store-card">
        {cardAsked ? (
          card ? (
            <>
              <h3 className="rc-h3">After the call</h3>
              <p className="rc-store-hint">
                What a business system would receive from this call, built by code from the engine&apos;s own log.
              </p>
              <CallCardView card={card} />
            </>
          ) : (
            <p className="rc-store-hint">No store-rep call on this visit yet.</p>
          )
        ) : (
          <Button variant="outline" onClick={() => void showCard()}>
            <ReceiptText />
            After-call record
          </Button>
        )}
      </div>
    </div>
  )
}

function Catalogue({ state }: { state: ShopState }) {
  return (
    <details className="rc-store-catalogue">
      <summary>Catalogue ({state.products.length})</summary>
      <ul>
        {state.products.map((p) => (
          <li key={p.sku}>
            <span>
              <span className="rc-store-name">{p.name}</span>
              <span className="rc-store-hint">
                {p.sku} · {p.in_stock ? `${p.in_stock} in stock` : 'out of stock'}
              </span>
            </span>
            <span className="rc-store-price">{money(p.price_cents)}</span>
          </li>
        ))}
      </ul>
    </details>
  )
}

function Orders({ state }: { state: ShopState }) {
  return (
    <div className="rc-store-orders">
      <h3 className="rc-h3">This visit&apos;s orders</h3>
      <ul>
        {state.orders.map((o) => (
          <li key={o.ref}>
            <span>
              {o.ref} <span className="rc-store-hint">{o.status === 'placed' ? 'placed' : 'read back, not placed'}</span>
            </span>
            <span className="rc-store-price">{money(o.total_cents)}</span>
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
  if (result?.ok) return <p role="status">{result.detail}</p>
  if (state.callback !== 'available') {
    return <p className="rc-store-hint">{CALLBACK_WORDS[state.callback]}</p>
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
    <form className="rc-store-form" onSubmit={(event) => void submit(event)} aria-label="Ask for a callback">
      <p className="rc-store-hint">Kevin gets your name, contact and a summary of this call. Nobody is dialled automatically.</p>
      <Input name="name" required maxLength={60} placeholder="Your name" aria-label="Your name" autoComplete="name" />
      <Input name="contact" required maxLength={120} placeholder="Phone or email" aria-label="Phone or email" autoComplete="email" />
      <Textarea name="note" maxLength={280} placeholder="Anything to add (optional)" aria-label="Note" rows={2} />
      <input name="website" tabIndex={-1} autoComplete="off" aria-hidden="true" className="hidden" defaultValue="" />
      {result && !result.ok && (
        <p role="alert" className="text-destructive">
          {result.detail}
        </p>
      )}
      <Button type="submit" disabled={busy}>
        Send to Kevin
      </Button>
    </form>
  )
}
