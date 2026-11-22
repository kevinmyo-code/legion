import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { AssistantProvider, useAssistant } from '@/assistant/assistant-context'
import type { CascadeDeps, TurnStart } from '@/assistant/cascade-client'
import type { SseEvent } from '@/assistant/sse'
import { FakeAudio, settle } from '@/test/fake-live'

import { StoreRepPanel } from './store-rep-panel'

/**
 * The store-rep panel on /try: when a conversation that used the store rep ends, the after-call
 * card opens by itself, and the panel's switch is the engine's switch. Invented shop and call.
 */

const STATE = {
  active: true,
  shop_name: 'Larkspur & Pine',
  products: [
    { sku: 'LP-PK40', name: 'Ridgeline 40L Daypack', category: 'packs', price_cents: 12900, in_stock: 14, blurb: '' },
  ],
  orders: [],
  callback: 'off',
}

const CARD = {
  outcome: 'browsing',
  outcome_words: 'No sale',
  summary: ['1 visitor turn.'],
  transcript: [{ who: 'visitor', text: 'Hi', typed: true, at: '11-22 10:00:00.000' }],
  turns: [],
  median_first_audio_ms: null,
  record: { record_type: 'call', outcome: 'browsing' },
}

afterEach(() => {
  vi.unstubAllGlobals()
})

function serve(state = STATE) {
  const calls: string[] = []
  vi.stubGlobal('fetch', async (input: Request) => {
    const { pathname } = new URL(input.url)
    calls.push(`${input.method} ${pathname}`)
    const json = (body: unknown) =>
      new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    if (pathname === '/api/demo/shop/state') return json(state)
    if (pathname === '/api/demo/shop/scenario') return json({ ...state, active: false })
    if (pathname === '/api/demo/shop/call-card') return json(CARD)
    return new Response('{}', { status: 501 })
  })
  return calls
}

function Send() {
  const { client } = useAssistant()
  return <button onClick={() => client.send('Thanks, that will be all.')}>send</button>
}

function mount() {
  async function* answer(): AsyncGenerator<SseEvent> {
    yield { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } }
    yield { type: 'sentence', data: { text: 'Thanks for shopping.' } }
    yield { type: 'done', data: { status: 'done', final_text: 'Thanks for shopping.', end: true } }
  }
  const deps: CascadeDeps = {
    startTurn: async (): Promise<TurnStart> => ({ ok: true, events: answer() }),
    cancelTurn: () => {},
    audio: () => new FakeAudio(),
    now: () => Date.now(),
    utcOffsetMinutes: () => 0,
  }
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AssistantProvider voiceFirst={{ prepare: async () => true }} cascadeDeps={deps}>
        <Send />
        <StoreRepPanel />
      </AssistantProvider>
    </QueryClientProvider>,
  )
}

describe('StoreRepPanel', () => {
  test('a store-rep conversation that ends opens the after-call card by itself', async () => {
    const calls = serve()
    mount()
    await screen.findByRole('button', { name: /Store rep: on/ })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'send' }))
      await settle()
    })
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('No sale')).toBeInTheDocument()
    expect(calls).toContain('GET /api/demo/shop/call-card')
  })

  test('the switch posts to the engine and shows what it answered', async () => {
    const calls = serve()
    mount()
    fireEvent.click(await screen.findByRole('button', { name: /Store rep: on/ }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Back to the LEGION guide' }))
      await settle()
    })
    expect(calls).toContain('POST /api/demo/shop/scenario')
    expect(await screen.findByRole('button', { name: /Try: store rep/ })).toBeInTheDocument()
  })

  test('nothing is drawn for a caller the engine refuses', async () => {
    vi.stubGlobal('fetch', async () => new Response('{"detail":"no"}', { status: 403 }))
    mount()
    await act(settle)
    expect(screen.queryByRole('button', { name: /store rep/i })).not.toBeInTheDocument()
  })
})
