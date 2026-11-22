import { api } from '@/api/client'
import type { components } from '@/api/schema'

/**
 * The store-rep demo's routes (`server/demo/shop/views.py`) for the /try page's own panel. The
 * engine decides everything (scenario, prices, stock, outcome); the page only shows it and sends
 * the visitor's own taps and form.
 */

export type ShopState = components['schemas']['ShopState']
export type ShopOrder = components['schemas']['ShopOrder']
export type CallCard = components['schemas']['CallCard']
export type CallTurn = components['schemas']['CallTurn']

export const SHOP_STATE_KEY = ['demo', 'shop', 'state'] as const

/** $1,234.00: the engine's cents, always with two places, as code speaks them. */
export function money(cents: number): string {
  const whole = Math.floor(Math.abs(cents) / 100).toLocaleString('en-US')
  const part = String(Math.abs(cents) % 100).padStart(2, '0')
  return `${cents < 0 ? '-' : ''}$${whole}.${part}`
}

export async function fetchShopState(): Promise<ShopState | null> {
  const { data } = await api.GET('/api/demo/shop/state')
  return data ?? null
}

export async function setScenario(active: boolean): Promise<ShopState | null> {
  const { data } = await api.POST('/api/demo/shop/scenario', { body: { active } })
  return data ?? null
}

export async function fetchCallCard(): Promise<CallCard | null> {
  const { data } = await api.GET('/api/demo/shop/call-card')
  return data ?? null
}

export interface CallbackForm {
  name: string
  contact: string
  note: string
  /** The honeypot: a person never sees or fills it. */
  website: string
}

export type CallbackResult = { ok: true; status: string; detail: string } | { ok: false; detail: string }

export async function sendCallback(form: CallbackForm): Promise<CallbackResult> {
  try {
    const { data, error } = await api.POST('/api/demo/shop/callback', { body: form })
    if (data) return { ok: true, status: data.status, detail: data.detail }
    const detail = (error as { detail?: unknown } | undefined)?.detail
    return { ok: false, detail: typeof detail === 'string' ? detail : 'Nothing was sent. Try again later.' }
  } catch {
    return { ok: false, detail: 'Nothing was sent: the engine could not be reached.' }
  }
}

export interface LatencyRow {
  label: string
  via: 'spoken' | 'typed'
  ms: number | null
}

/**
 * Each turn's engine-side time to first audio, for the card's table. A typed turn speaks nothing,
 * so its row says so rather than showing a number that was never measured.
 */
export function latencyRows(turns: readonly CallTurn[]): LatencyRow[] {
  let n = 0
  return turns.map((turn) => {
    const label = turn.kind === 'greeting' ? 'Greeting' : `Turn ${++n}`
    return { label, via: turn.spoken ? 'spoken' : 'typed', ms: turn.spoken ? turn.first_audio_ms : null }
  })
}

export const OUTCOME_TONE: Record<CallCard['outcome'], string> = {
  order_placed: 'bg-primary-container text-primary-container-foreground',
  needs_human: 'bg-destructive-container text-destructive-container-foreground',
  quoted_not_placed: 'bg-accent text-accent-foreground',
  browsing: 'bg-muted text-muted-foreground',
}
