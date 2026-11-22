import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { AssistantProvider, useAssistant } from '@/assistant/assistant-context'
import type { CascadeDeps, TurnStart } from '@/assistant/cascade-client'
import type { SseEvent } from '@/assistant/sse'
import { FakeAudio, settle } from '@/test/fake-live'

import { StoreRepSection } from './store-rep-section'

/**
 * The store-rep demo as a section of /try (Kevin, 2026-11-22): one control that switches the visit
 * to the store rep and talks in the same tap (the greeting waits for the switch), the after-call
 * card in the section when a store-rep conversation ends, and the shop's own mark. Invented shop,
 * invented call.
 */

const STATE = {
  active: false,
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

function serve(state: typeof STATE, log: string[]) {
  let current = state
  vi.stubGlobal('fetch', async (input: Request) => {
    const { pathname } = new URL(input.url)
    log.push(`${input.method} ${pathname}`)
    const json = (body: unknown) =>
      new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    if (pathname === '/api/demo/shop/state') return json(current)
    if (pathname === '/api/demo/shop/scenario') {
      // The switch takes a while: the greeting must not go before it lands.
      await new Promise((resolve) => setTimeout(resolve, 60))
      log.push('scenario switched')
      const body = (await input.json()) as { active: boolean }
      current = { ...current, active: body.active }
      return json(current)
    }
    if (pathname === '/api/demo/shop/call-card') return json(CARD)
    return new Response('{}', { status: 501 })
  })
}

function Send() {
  const { client } = useAssistant()
  return <button onClick={() => client.send('Thanks, that will be all.')}>send</button>
}

function mount(log: string[]) {
  async function* answer(): AsyncGenerator<SseEvent> {
    yield { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } }
    yield { type: 'sentence', data: { text: 'Thanks for shopping.' } }
    yield { type: 'done', data: { status: 'done', final_text: 'Thanks for shopping.', end: true } }
  }
  const deps: CascadeDeps = {
    startTurn: async (request): Promise<TurnStart> => {
      log.push(request.greet ? 'TURN greet' : 'TURN')
      return { ok: true, events: answer() }
    },
    cancelTurn: () => {},
    audio: () => new FakeAudio(),
    now: () => Date.now(),
    utcOffsetMinutes: () => 0,
  }
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AssistantProvider voiceFirst={{ prepare: async () => true }} cascadeDeps={deps}>
        <Send />
        <StoreRepSection />
      </AssistantProvider>
    </QueryClientProvider>,
  )
}

describe('StoreRepSection', () => {
  test('"Talk to the store rep" switches the visit and talks in the same tap; the greeting waits for the switch', async () => {
    const log: string[] = []
    serve(STATE, log)
    mount(log)
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Talk to the store rep' }))
      await new Promise((resolve) => setTimeout(resolve, 150))
      await settle()
    })
    const switched = log.indexOf('scenario switched')
    const greeted = log.indexOf('TURN greet')
    expect(switched).toBeGreaterThanOrEqual(0)
    expect(greeted).toBeGreaterThan(switched)
    // On: the way back shows.
    expect(await screen.findByRole('button', { name: 'Back to the LEGION guide' })).toBeInTheDocument()
  })

  test('a store-rep conversation that ends shows its after-call card in the section', async () => {
    const log: string[] = []
    serve({ ...STATE, active: true }, log)
    mount(log)
    await screen.findByRole('button', { name: 'Back to the LEGION guide' })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'send' }))
      await settle()
    })
    expect(await screen.findByRole('heading', { name: 'After the call' })).toBeInTheDocument()
    expect(screen.getByTestId('call-card')).toHaveTextContent('No sale')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test("the shop's mark is drawn; a caller with no visit gets the mark and a line, no controls", async () => {
    vi.stubGlobal('fetch', async () => new Response('{"detail":"no"}', { status: 403 }))
    mount([])
    await act(settle)
    expect(screen.getByRole('img', { name: 'Larkspur & Pine, a made-up shop' })).toHaveAttribute('data-mark', 'larkspur')
    expect(screen.queryByRole('button', { name: /store rep/i })).not.toBeInTheDocument()
    expect(screen.getByText(/runs on a demo visit/)).toBeInTheDocument()
  })
})
