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

import { VoiceStage } from './voice-stage'

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
 * of the page as a footer". Once the orb is (mostly) off screen, a docked bar at the bottom carries
 * a small orb and the status line, in every state. It replaces the small Interrupt control and keeps
 * its job: its tap is the orb's tap (start, interrupt, stop listening), never a path of its own. The
 * page gets room at the bottom while it shows, so it never covers the content.
 */
describe('the docked orb when the orb is off screen', () => {
  type Callback = (entries: { isIntersecting: boolean }[]) => void
  const observers: { cb: Callback; options?: IntersectionObserverInit }[] = []
  const realIO = (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver
  afterEach(() => {
    ;(globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = realIO
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
  const dock = () => document.querySelector<HTMLElement>('[data-stage="dock"]')
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
    expect(dock()).toBeNull()
    deliver(false)
    // Idle too: the docked orb is how a visitor far down the page starts talking.
    expect(dock()).not.toBeNull()
    expect(dock()).toHaveTextContent('Tap and ask me about Kevin')
    expect(dock()!.className).toContain('rc-voice-dock')
    deliver(true)
    expect(dock()).toBeNull()
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

  test('the page gets room at the bottom while it shows, so it covers nothing', async () => {
    fakeIO()
    stage(false, true)
    deliver(false)
    expect(document.body.style.paddingBottom).not.toBe('')
    deliver(true)
    expect(document.body.style.paddingBottom).toBe('')
  })

  test('a batch that ends with the orb leaving the screen docks it: the last entry wins', async () => {
    fakeIO()
    stage(true)
    deliver(true, false)
    expect(dock()).not.toBeNull()
    deliver(false, true)
    expect(dock()).toBeNull()
    expect(orb()).not.toBeNull()
  })

  test('a sliver of orb is not a reachable orb: it counts as seen only at half visible', () => {
    fakeIO()
    stage(true)
    expect(observers.length).toBeGreaterThan(0)
    expect(observers.every(({ options }) => options?.threshold === 0.5)).toBe(true)
  })
})

/**
 * Kevin, 2026-11-19 ("B hide the whole strip"): the live strip (tools with their times, the turn's
 * cost, "Sounded robotic") is his tester visit's only. The engine decides: only that visit's turns
 * end with `cost_usd`, so a recruiter's page never shows the strip, even while tools run.
 */
describe('the live strip', () => {
  function stripStage(done: Record<string, unknown>) {
    async function* answer(): AsyncGenerator<SseEvent> {
      yield { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } }
      yield { type: 'filler', data: { tool: 'next_service', text: 'Checking the records.' } }
      yield { type: 'tool', data: { name: 'next_service', ok: true, text: 'Due in May.', ms: 120 } }
      yield { type: 'sentence', data: { text: 'Due in May.' } }
      yield { type: 'done', data: { status: 'done', final_text: 'Due in May.', ...done } }
    }
    const deps: CascadeDeps = {
      startTurn: async (): Promise<TurnStart> => ({ ok: true, events: answer() }),
      audio: () => new FakeAudio(),
      now: () => Date.now(),
      utcOffsetMinutes: () => 0,
    }
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AssistantProvider voiceFirst={{ prepare: async () => true }} cascadeDeps={deps}>
          <VoiceStage />
        </AssistantProvider>
      </QueryClientProvider>,
    )
  }
  async function ask() {
    await act(async () => {
      fireEvent.change(screen.getByRole('textbox', { name: /^Type to / }), { target: { value: 'When is the car due?' } })
      fireEvent.click(screen.getByRole('button', { name: 'Send' }))
      await settle()
    })
  }

  test('a recruiter visit (no cost from the engine) never shows it, though a tool ran', async () => {
    stripStage({})
    await ask()
    expect(document.querySelector('[data-stage="strip"]')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Sounded robotic' })).toBeNull()
  })

  test("the tester visit (the engine sent the turn's cost) shows it with the button", async () => {
    stripStage({ cost_usd: 0.0021, tools_ms: [['next_service', 120]] })
    await ask()
    expect(document.querySelector('[data-stage="strip"]')).toHaveTextContent('next_service 120 ms')
    expect(screen.getByRole('button', { name: 'Sounded robotic' })).toBeInTheDocument()
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
    expect(row.className).toContain('rc-chips')
    // Directly under the orb.
    expect(document.querySelector('[data-stage="orb"]')!.nextElementSibling).toContainElement(row)
    expect(within(row).getAllByRole('button').map((b) => b.textContent)).toEqual(['About Kevin', 'How LEGION is built'])
    await act(async () => {
      fireEvent.click(within(row).getByRole('button', { name: 'About Kevin' }))
      await settle()
    })
    expect(requests.at(-1)).toMatchObject({ text: 'Tell me about Kevin.', chip: 'chip-1' })
  })

  test('the pills: page colours only, a 44 px tap target, one line, an edge fade, an entrance', () => {
    const chip = [...indexCss.matchAll(/\.recruiter \.rc-chip \{([^}]*)\}/g)].map((m) => m[1]).find((b) => b.includes('min-height')) ?? ''
    expect(chip).toMatch(/min-height: 44px/)
    // A pill despite the page's square-corners rule (unlayered, so the exception is unlayered and after it).
    const square = indexCss.search(/\.recruiter \*::after \{\s*border-radius: 0;/)
    const pill = indexCss.search(/\.recruiter \.rc-chip \{\s*border-radius: 9999px;/)
    expect(square).toBeGreaterThan(-1)
    expect(pill).toBeGreaterThan(square)
    expect(chip).toMatch(/white-space: nowrap/)
    expect(chip).toMatch(/animation: rc-chip-in/)
    // Colours are the page's own tokens, never a new literal.
    expect(chip.match(/#[0-9a-f]{3,8}\b|rgb\(/gi) ?? []).toEqual([])
    const row = /\.recruiter \.rc-chips \{([^}]*)\}/.exec(indexCss)?.[1] ?? ''
    expect(row).toMatch(/flex-wrap: nowrap/)
    expect(row).toMatch(/overflow-x: auto/)
    expect(row).toMatch(/mask-image/)
  })
})
