import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, test } from 'vitest'

import { AssistantProvider } from '@/assistant/assistant-context'
import type { CascadeDeps, TurnStart } from '@/assistant/cascade-client'
import { pcm16ToBase64 } from '@/assistant/pcm'
import type { SseEvent } from '@/assistant/sse'
import { AssistantModals } from '@/components/assistant/assistant-modals'
import { DIAGRAM_CARD } from '@/test/cards-seed'
import { FakeAudio, settle } from '@/test/fake-live'

import indexCss from '../../index.css?raw'

import { QUESTION_CHIPS, VoiceStage } from './voice-stage'

/**
 * The browser run (2026-11-17): an answer that opened a diagram covered the orb, so a tap to stop
 * Alfred never landed. The dialog carries its own control for that, present whenever he is
 * thinking or speaking; the orb is back as soon as the dialog closes (backdrop, Esc, X).
 */

afterEach(() => {
  window.history.replaceState({}, '')
})

function stage(withAudio: boolean) {
  const audio = new FakeAudio()
  const cancels: string[] = []
  async function* answer(): AsyncGenerator<SseEvent> {
    yield { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } }
    yield { type: 'tool', data: { name: 'show_diagram', ok: true, text: 'Shown.', card: DIAGRAM_CARD } }
    yield { type: 'sentence', data: { text: 'Here is how it fits together.' } }
    if (withAudio) yield { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array(4800)), rate: 24000 } }
    await new Promise(() => {})
  }
  const deps: CascadeDeps = {
    startTurn: async (): Promise<TurnStart> => ({ ok: true, events: answer() }),
    cancelTurn: (id) => cancels.push(id),
    audio: () => audio,
    now: () => Date.now(),
    utcOffsetMinutes: () => 0,
  }
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AssistantProvider voiceFirst={{ prepare: async () => true }} cascadeDeps={deps}>
        <AssistantModals>
          <VoiceStage />
        </AssistantModals>
      </AssistantProvider>
    </QueryClientProvider>,
  )
  return { audio, cancels }
}

describe('the voice stage with a card open over it', () => {
  test.each([
    ['thinking', false],
    ['speaking', true],
  ])('while %s, the dialog has a control that stops him in one tap and cancels the turn', async (_when, withAudio) => {
    const { audio, cancels } = stage(withAudio)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: QUESTION_CHIPS[1] }))
      await settle()
    })
    const dialog = await screen.findByRole('dialog')
    const flushes = audio.flushes
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Interrupt' }))
      await settle()
    })
    expect(cancels).toHaveLength(1)
    expect(audio.flushes).toBe(flushes + 1)
    expect(within(dialog).queryByRole('button', { name: 'Interrupt' })).not.toBeInTheDocument()
    // The keyboard stays in the dialog, on its Close.
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Close' }))
  })
})

/**
 * Live demo, 2026-11-18 (Kevin, Chrome): "the orb and the top section scrolls with it so it overlaps
 * on the other content below". The stage borrowed `rc-stage`, the phone section's sticky class, so
 * it stayed pinned with no background over the captions, cards and notice scrolling under it. The
 * stage scrolls away with the page: none of its own classes may be pinned by the stylesheet.
 */
function pinnedClasses(css: string): Set<string> {
  const pinned = new Set<string>()
  for (const match of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (!/position\s*:\s*(sticky|fixed)/.test(match[2])) continue
    for (const cls of match[1].matchAll(/\.([A-Za-z][\w-]*)/g)) pinned.add(cls[1])
  }
  return pinned
}

describe('the voice stage on the page', () => {
  test('is not pinned by the stylesheet, so it never covers the content scrolling below it', () => {
    stage(false)
    const section = screen.getByRole('region', { name: 'Talk to the demo assistant' })
    const pinned = pinnedClasses(indexCss)
    expect(pinned.size).toBeGreaterThan(0)
    const own = [section, ...Array.from(section.querySelectorAll('*'))].flatMap((el) => Array.from(el.classList))
    expect(own.filter((cls) => pinned.has(cls))).toEqual([])
  })
})

/**
 * Blind review of the overlap fix (2026-11-18): once the stage scrolls away on a phone there is no
 * way to stop him while he speaks. A small fixed Stop appears only while a reply plays and the orb
 * is off screen, and does what the orb's tap does.
 */
describe('the Stop control when the orb is off screen', () => {
  type Callback = (entries: { isIntersecting: boolean }[]) => void
  const observers: Callback[] = []
  const realIO = (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver
  afterEach(() => {
    ;(globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = realIO
    observers.length = 0
  })
  function fakeIO() {
    ;(globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = class {
      constructor(cb: Callback) {
        observers.push(cb)
      }
      observe() {}
      disconnect() {}
      unobserve() {}
    }
  }
  const orbSeen = (seen: boolean) => act(() => observers.forEach((cb) => cb([{ isIntersecting: seen }])))

  test('appears while he speaks with the orb scrolled away, and stops him in one tap', async () => {
    fakeIO()
    const { audio, cancels } = stage(true)
    // A spoken conversation: the tap opens the mic, and the reply's audio plays.
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^Talk to / }))
      await settle()
    })
    // The answer's card dialog is closed for this check: the page under it is what scrolls.
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    await act(async () => settle())
    expect(screen.queryByRole('button', { name: /^Stop / })).not.toBeInTheDocument()
    orbSeen(false)
    const stop = await screen.findByRole('button', { name: /^Stop / })
    const flushes = audio.flushes
    await act(async () => {
      fireEvent.click(stop)
      await settle()
    })
    // The same as the orb's tap: the reply stops and the engine's turn is cancelled.
    expect(cancels).toHaveLength(1)
    expect(audio.flushes).toBe(flushes + 1)
  })

  test('is not there while the orb is on screen', async () => {
    fakeIO()
    stage(true)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^Talk to / }))
      await settle()
    })
    orbSeen(true)
    expect(screen.queryByRole('button', { name: /^Stop / })).not.toBeInTheDocument()
  })
})
