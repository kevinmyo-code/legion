import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { AssistantProvider } from '@/assistant/assistant-context'
import type { CascadeDeps, TurnStart } from '@/assistant/cascade-client'
import type { SseEvent } from '@/assistant/sse'
import { FakeAudio, settle } from '@/test/fake-live'

import { resetOrbPlace, setOrbPlace } from './scroll-story'
import { StoreRepSection } from './store-rep/store-rep-section'
import { VoiceStage } from './voice-stage'

/**
 * Kevin's ruling, 2026-11-22: beside the store-rep section (the orb wears the shop's mark) the
 * orb's tap opens the rep, as the section's own button does; elsewhere it is the guide; a
 * conversation in progress is never switched by scrolling. Invented shop.
 */

const STATE = { active: false, shop_name: 'Larkspur & Pine', products: [], orders: [], callback: 'off' }

afterEach(() => {
  vi.unstubAllGlobals()
  act(() => resetOrbPlace())
})

function serve(active: boolean, log: string[]) {
  let current = { ...STATE, active }
  vi.stubGlobal('fetch', async (input: Request) => {
    const { pathname } = new URL(input.url)
    const json = (body: unknown) =>
      new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    if (pathname === '/api/demo/shop/state') return json(current)
    if (pathname === '/api/demo/shop/scenario') {
      const body = (await input.json()) as { active: boolean }
      log.push(`scenario ${body.active ? 'rep' : 'guide'}`)
      current = { ...current, active: body.active }
      return json(current)
    }
    return new Response('{}', { status: 501 })
  })
}

function mount(log: string[], hang = false) {
  const audio = new FakeAudio()
  async function* answer(): AsyncGenerator<SseEvent> {
    yield { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } }
    // Still thinking: the answer never comes.
    if (hang) await new Promise(() => {})
    yield { type: 'sentence', data: { text: 'Hello.' } }
    yield { type: 'done', data: { status: 'done', final_text: 'Hello.' } }
  }
  const deps: CascadeDeps = {
    startTurn: async (request): Promise<TurnStart> => {
      log.push(request.greet ? 'TURN greet' : 'TURN')
      return { ok: true, events: answer() }
    },
    cancelTurn: () => {},
    audio: () => audio,
    now: () => Date.now(),
    utcOffsetMinutes: () => 0,
  }
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AssistantProvider voiceFirst={{ prepare: async () => true }} cascadeDeps={deps}>
        <VoiceStage />
        <StoreRepSection />
      </AssistantProvider>
    </QueryClientProvider>,
  )
}

const stageOrb = () => document.querySelector<HTMLButtonElement>('[data-stage="orb"]')!

async function tapOrb() {
  await act(async () => {
    fireEvent.click(stageOrb())
    await new Promise((resolve) => setTimeout(resolve, 50))
    await settle()
  })
}

describe('the orb beside the store-rep section', () => {
  test('a tap there starts the store rep, as the section button does', async () => {
    const log: string[] = []
    serve(false, log)
    mount(log)
    await screen.findByRole('button', { name: 'Talk to the store rep' })
    act(() => setOrbPlace({ mark: 'larkspur' }))
    expect(await screen.findAllByRole('button', { name: 'Talk to the store rep' })).toHaveLength(2)
    expect(stageOrb()).toHaveAttribute('aria-label', 'Talk to the store rep')
    await tapOrb()
    expect(log[0]).toBe('scenario rep')
    expect(log).toContain('TURN greet')
  })

  test('elsewhere the orb is the guide, the guide scenario sent though the cache says it is on', async () => {
    const log: string[] = []
    serve(false, log)
    mount(log)
    await screen.findByRole('button', { name: 'Talk to the store rep' })
    expect(stageOrb()).not.toHaveAttribute('aria-label', 'Talk to the store rep')
    await tapOrb()
    // Never trusted from the cache (another tab may have switched the visit): sent, then greeted.
    expect(log).toEqual(['scenario guide', 'TURN greet'])
  })

  test('elsewhere, after the rep was on, the next tap switches the visit back to the guide', async () => {
    const log: string[] = []
    serve(true, log)
    mount(log)
    await screen.findByRole('button', { name: 'Back to the LEGION guide' })
    await tapOrb()
    expect(log[0]).toBe('scenario guide')
    expect(log).toContain('TURN greet')
  })

  test('after a typed question has been answered, a tap beside the section is the rep', async () => {
    const log: string[] = []
    serve(false, log)
    mount(log)
    await screen.findByRole('button', { name: 'Talk to the store rep' })
    await act(async () => {
      fireEvent.change(screen.getByRole('textbox', { name: /^Type to / }), { target: { value: 'Hi there' } })
      fireEvent.click(screen.getByRole('button', { name: 'Send' }))
      await new Promise((resolve) => setTimeout(resolve, 50))
      await settle()
    })
    expect(log.filter((line) => line.startsWith('scenario'))).toEqual([])
    act(() => setOrbPlace({ mark: 'larkspur' }))
    expect(stageOrb()).toHaveAttribute('aria-label', 'Talk to the store rep')
    await tapOrb()
    expect(log.filter((line) => line.startsWith('scenario'))).toEqual(['scenario rep'])
    expect(log[log.length - 1]).toBe('TURN greet')
  })

  test('a rep conversation, then out of the section: the next tap is the guide again', async () => {
    const log: string[] = []
    serve(false, log)
    mount(log)
    await screen.findByRole('button', { name: 'Talk to the store rep' })
    act(() => setOrbPlace({ mark: 'larkspur' }))
    await tapOrb()
    // Listening to the rep: a tap stops it (never a switch).
    await tapOrb()
    expect(log.filter((line) => line.startsWith('scenario'))).toEqual(['scenario rep'])
    act(() => setOrbPlace({ mark: null }))
    expect(stageOrb()).not.toHaveAttribute('aria-label', 'Talk to the store rep')
    await tapOrb()
    expect(log.filter((line) => line.startsWith('scenario'))).toEqual(['scenario rep', 'scenario guide'])
    expect(log[log.length - 1]).toBe('TURN greet')
  })

  test('scrolling into the section mid-conversation does not switch it; the next one is the rep', async () => {
    const log: string[] = []
    serve(false, log)
    mount(log)
    await screen.findByRole('button', { name: 'Talk to the store rep' })
    await tapOrb()
    expect(log).toEqual(['scenario guide', 'TURN greet'])
    act(() => setOrbPlace({ mark: 'larkspur' }))
    await act(async () => {
      await settle()
    })
    // The guide's conversation runs on: no switch, and the orb is still that conversation's control.
    expect(log.filter((line) => line.startsWith('scenario'))).toEqual(['scenario guide'])
    expect(stageOrb()).not.toHaveAttribute('aria-label', 'Talk to the store rep')
    // The visitor closes it (the orb's tap while listening); the next tap is the rep.
    await tapOrb()
    expect(stageOrb()).toHaveAttribute('aria-label', 'Talk to the store rep')
    await tapOrb()
    expect(log.filter((line) => line.startsWith('scenario'))).toEqual(['scenario guide', 'scenario rep'])
  })

  test.each([
    [true, /^The store rep is thinking/],
    [false, /^(?!The store rep)\S.* is thinking/],
  ])('while thinking, the line names who is talking (rep on: %s)', async (active, line) => {
    const log: string[] = []
    serve(active, log)
    mount(log, true)
    await screen.findByRole('button', { name: active ? 'Back to the LEGION guide' : 'Talk to the store rep' })
    await act(async () => {
      fireEvent.change(screen.getByRole('textbox', { name: /^Type to / }), { target: { value: 'Hi there' } })
      fireEvent.click(screen.getByRole('button', { name: 'Send' }))
      await new Promise((resolve) => setTimeout(resolve, 50))
      await settle()
    })
    expect(document.querySelector('[data-stage="line"]')!.textContent).toMatch(line)
  })
})
