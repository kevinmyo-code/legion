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

import { DOCK_AT, TRAVEL_MS, UNDOCK_AT, VoiceStage } from './voice-stage'

/**
 * The browser run (2026-11-17): an answer that opened a diagram covered the orb, so a tap to stop
 * Alfred never landed. The dialog carries its own control for that, present whenever he is
 * thinking or speaking; the orb is back as soon as the dialog closes (backdrop, Esc, X).
 */

afterEach(() => {
  window.history.replaceState({}, '')
})

function stage(withAudio: boolean, ends = false) {
  const audio = new FakeAudio()
  const cancels: string[] = []
  async function* answer(): AsyncGenerator<SseEvent> {
    yield { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } }
    yield { type: 'tool', data: { name: 'show_diagram', ok: true, text: 'Shown.', card: DIAGRAM_CARD } }
    yield { type: 'sentence', data: { text: 'Here is how it fits together.' } }
    if (withAudio) yield { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array(4800)), rate: 24000 } }
    if (ends) {
      yield { type: 'done', data: { status: 'done', final_text: 'Here is how it fits together.' } }
      return
    }
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
      fireEvent.change(screen.getByRole('textbox', { name: /^Type to / }), { target: { value: 'How is LEGION built?' } })
      fireEvent.click(screen.getByRole('button', { name: 'Send' }))
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
 * Kevin, 2026-11-18: "when users scroll down past the orb, make the orb follow along at the bottom
 * of the page as a footer"; 2026-11-19: "the orb should like move from the location in the centre to
 * the bottom ... it should also be a little larger". Once the orb is mostly off screen it travels into
 * a dock at the foot, which carries it (60 px), the status line and the chips, in every state. Its
 * tap is the orb's tap (start, interrupt, stop listening), never a path of its own. The dock stays
 * mounted (shown by `data-docked`); the page keeps room for it at its foot whether docked or not.
 */
describe('the docked orb when the orb is off screen', () => {
  type Entry = { isIntersecting: boolean; intersectionRatio?: number }
  type Callback = (entries: Entry[]) => void
  const observers: { cb: Callback; options?: IntersectionObserverInit }[] = []
  const realIO = (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver
  const realAnimate = (HTMLElement.prototype as { animate?: unknown }).animate
  const realMatchMedia = window.matchMedia
  afterEach(() => {
    ;(globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = realIO
    ;(HTMLElement.prototype as { animate?: unknown }).animate = realAnimate
    window.matchMedia = realMatchMedia
    observers.length = 0
    document.body.style.paddingBottom = ''
  })
  function fakeIO() {
    ;(globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = class {
      constructor(cb: Callback, options?: IntersectionObserverInit) {
        observers.push({ cb, options })
      }
      observe() {}
      disconnect() {}
      unobserve() {}
    }
  }
  const deliver = (...seen: boolean[]) =>
    act(() => observers.forEach(({ cb }) => cb(seen.map((isIntersecting) => ({ isIntersecting })))))
  /** The orb's visible share, as the browser reports it. */
  const shown = (ratio: number) =>
    act(() => observers.forEach(({ cb }) => cb([{ isIntersecting: ratio > 0, intersectionRatio: ratio }])))
  const dock = () => document.querySelector<HTMLElement>('[data-stage="dock"]')!
  const isDocked = () => dock().getAttribute('data-docked') === 'true'
  const dockOrb = () => document.querySelector<HTMLButtonElement>('[data-stage="dock-orb"]')
  const orb = () => document.querySelector<HTMLButtonElement>('[data-stage="orb"]')!

  async function tapOrbAndCloseCard() {
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^Talk to / }))
      await settle()
    })
    // The answer's card dialog is closed for this check: the page under it is what scrolls.
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    await act(async () => settle())
  }

  test('docks in every state while the orb is off screen, with the status line, and leaves when it is back', async () => {
    fakeIO()
    stage(false, true)
    expect(isDocked()).toBe(false)
    expect(dock()).toHaveAttribute('aria-hidden', 'true')
    deliver(false)
    // Idle too: the docked orb is how a visitor far down the page starts talking.
    expect(isDocked()).toBe(true)
    expect(dock()).toHaveTextContent('Tap and ask me about Kevin')
    expect(dock().className).toContain('rc-voice-dock')
    // The stage's orb has handed over: no second tap target, no tab stop.
    expect(orb()).toHaveAttribute('data-docked', 'true')
    expect(orb()).toHaveAttribute('tabindex', '-1')
    deliver(true)
    expect(isDocked()).toBe(false)
  })

  test('the dock is one element for the whole visit: scrolling never mounts or unmounts it', () => {
    fakeIO()
    stage(false, true)
    const first = dock()
    deliver(false)
    deliver(true)
    deliver(false)
    expect(dock()).toBe(first)
  })

  test('hysteresis: it docks at a third shown and comes back at two thirds, so it can never flip back and forth', () => {
    fakeIO()
    stage(false, true)
    shown(0.5)
    expect(isDocked()).toBe(false)
    shown(0.34)
    expect(isDocked()).toBe(true)
    // Between the two marks nothing changes, whichever way the page moves.
    for (const ratio of [0.4, 0.6, 0.45, 0.55, 0.36, 0.64]) {
      shown(ratio)
      expect(isDocked()).toBe(true)
    }
    shown(0.66)
    expect(isDocked()).toBe(false)
    for (const ratio of [0.6, 0.4, 0.5, 0.36]) {
      shown(ratio)
      expect(isDocked()).toBe(false)
    }
    const marks = observers.map(({ options }) => options?.threshold)
    expect(marks.every((t) => Array.isArray(t) && t.includes(DOCK_AT) && t.includes(UNDOCK_AT))).toBe(true)
  })

  test('the docked orb is larger than before (60 px) and travels from the stage orb into place', () => {
    fakeIO()
    const calls: { keyframes: Keyframe[]; options: KeyframeAnimationOptions }[] = []
    ;(HTMLElement.prototype as { animate?: unknown }).animate = function (keyframes: Keyframe[], options: KeyframeAnimationOptions) {
      calls.push({ keyframes, options })
      return { cancel() {} }
    }
    // jsdom lays nothing out: the two orbs get boxes so the travel has somewhere to go.
    const box = (el: Element, x: number, y: number, size: number) =>
      (el.getBoundingClientRect = () => ({ left: x, top: y, width: size, height: size, right: x + size, bottom: y + size, x, y, toJSON() {} }) as DOMRect)
    stage(false, true)
    box(orb(), 75, -150, 240)
    box(dockOrb()!, 16, 620, 60)
    expect(dockOrb()!.className).toContain('size-15')
    deliver(false)
    expect(calls).toHaveLength(1)
    expect(calls[0].options.duration).toBe(TRAVEL_MS)
    expect(calls[0].keyframes[0].transform).toBe('translate(149px, -680px) scale(4.000)')
    expect(calls[0].keyframes.at(-1)!.transform).toBe('none')
    deliver(true)
    expect(calls).toHaveLength(2)
    // Home again: it ends where the stage orb is, faded, and stays so until the dock hides.
    expect(calls[1].keyframes.at(-1)!.transform).toBe('translate(149px, -680px) scale(4.000)')
    expect(calls[1].options.fill).toBe('forwards')
  })

  test('reduced motion: no travel (the stylesheet cross-fades)', () => {
    fakeIO()
    const calls: unknown[] = []
    ;(HTMLElement.prototype as { animate?: unknown }).animate = function () {
      calls.push(1)
      return { cancel() {} }
    }
    window.matchMedia = ((query: string) => ({
      matches: query.includes('reduce'),
      addEventListener() {},
      removeEventListener() {},
    })) as unknown as typeof window.matchMedia
    stage(false, true)
    deliver(false)
    expect(isDocked()).toBe(true)
    expect(calls).toHaveLength(0)
    expect(indexCss).toMatch(/\.recruiter \.rc-voice-dock-panel,[\s\S]*?transition-duration: 200ms !important/)
  })

  test('its tap is the orb tap: idle it starts the conversation', async () => {
    fakeIO()
    const { audio } = stage(false, true)
    deliver(false)
    await act(async () => {
      fireEvent.click(dockOrb()!)
      await settle()
    })
    // The greeting was asked for, through the same start path (the notice plays in it).
    expect(audio.played.length + Number(audio.micOpen)).toBeGreaterThan(0)
  })

  test('while he speaks its tap interrupts and cancels the turn, and the keyboard lands on the docked orb', async () => {
    fakeIO()
    const { audio, cancels } = stage(true)
    await tapOrbAndCloseCard()
    deliver(false)
    expect(dockOrb()).toHaveAccessibleName('Interrupt the assistant')
    const flushes = audio.flushes
    await act(async () => {
      fireEvent.click(dockOrb()!)
      await settle()
    })
    expect(cancels).toHaveLength(1)
    expect(audio.flushes).toBe(flushes + 1)
    expect(document.activeElement).toBe(dockOrb())
  })

  test('the old Interrupt control is gone (the dock replaces it)', async () => {
    fakeIO()
    stage(true)
    await tapOrbAndCloseCard()
    deliver(false)
    expect(document.querySelector('[data-stage="stop"]')).toBeNull()
  })

  test('the page keeps room for the dock at its foot whether docked or not, so docking never moves it', async () => {
    fakeIO()
    stage(false, true)
    const room = document.body.style.paddingBottom
    expect(room).not.toBe('')
    deliver(false)
    expect(document.body.style.paddingBottom).toBe(room)
    deliver(true)
    expect(document.body.style.paddingBottom).toBe(room)
    // A focused field scrolls clear of it.
    expect(document.documentElement.style.scrollPaddingBottom).toBe(room)
  })

  test('a batch that ends with the orb leaving the screen docks it: the last entry wins', async () => {
    fakeIO()
    stage(true)
    deliver(true, false)
    expect(isDocked()).toBe(true)
    deliver(false, true)
    expect(isDocked()).toBe(false)
    expect(orb()).not.toBeNull()
  })
})

/**
 * Demo stage 2 (item 1): the chips are the engine's, checked for this visit and sent after the
 * greeting; the page writes none. One row that scrolls sideways (the 390 px width is asserted in
 * the real-browser check, `tests/realrun_browser_voice_first.mjs` vf-chips).
 */
describe('the suggestion chips on the stage', () => {
  const CHIPS = [
    { label: 'About Kevin', question: 'Tell me about Kevin.', token: 'chip-1' },
    { label: 'How LEGION is built', question: 'Show me how LEGION is built.', token: 'chip-2' },
  ]

  function chipStage() {
    const audio = new FakeAudio()
    const requests: Parameters<CascadeDeps['startTurn']>[0][] = []
    async function* greeting(): AsyncGenerator<SseEvent> {
      yield { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } }
      yield { type: 'sentence', data: { text: 'Hi. Chats are recorded.' } }
      yield { type: 'done', data: { status: 'greeting', final_text: 'Hi.', offer: 'offer-0' } }
    }
    async function* answer(): AsyncGenerator<SseEvent> {
      yield { type: 'turn', data: { conversation_id: 'c-1', turn: 1 } }
      yield { type: 'sentence', data: { text: 'He studies.' } }
      yield { type: 'done', data: { status: 'done', final_text: 'He studies.' } }
    }
    const deps: CascadeDeps = {
      startTurn: async (request): Promise<TurnStart> => {
        requests.push(request)
        return { ok: true, events: request.greet ? greeting() : answer() }
      },
      chips: async () => CHIPS,
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
    return { requests }
  }

  test('none before the greeting; the engine chips in one sideways row after it; a tap asks with its token', async () => {
    const { requests } = chipStage()
    expect(screen.queryByRole('group', { name: 'Suggested questions' })).toBeNull()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^Talk to / }))
      await settle()
    })
    const row = await screen.findByRole('group', { name: 'Suggested questions' })
    expect(row.className).toContain('flex-nowrap')
    expect(row.className).toContain('overflow-x-auto')
    expect(within(row).getAllByRole('button').map((b) => b.textContent)).toEqual(['About Kevin', 'How LEGION is built'])
    await act(async () => {
      fireEvent.click(within(row).getByRole('button', { name: 'About Kevin' }))
      await settle()
    })
    expect(requests.at(-1)).toMatchObject({ text: 'Tell me about Kevin.', chip: 'chip-1' })
  })
})
