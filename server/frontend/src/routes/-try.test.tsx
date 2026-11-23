import { focusManager } from '@tanstack/react-query'
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { AudioError } from '@/assistant/audio'
import { setAudioFactory } from '@/assistant/deps'
import { createEngine, type EngineOptions } from '@/test/engine'
import { SEED_FAILED, SAMPLE_SVG, TOO_MANY, WRONG_CODE, sampleContent } from '@/test/engine-demo'
import { FakeAudio, FakeLiveSocket, settle } from '@/test/fake-live'
import { DAY_CARD, SPEND_CARD } from '@/test/cards-seed'
import { renderApp } from '@/test/render-app'
import type { DemoContent, DemoSectionMedia } from '@/api/demo'
import { FIRST_QUESTIONS, OPENING_TURN, VISITOR_SESSION_ENDED } from '@/screens/try'
import { ENTER_WAIT_LINE } from '@/components/demo/enter-wait'
import { RECORDING_NOTICE, STAGE_LINE } from '@/components/demo/voice-stage'
import { ENTRY_TAP_MS } from '@/assistant/turn-deadline'

/**
 * The recruiter section (recruiter-demo ticket 07): the code box on `/login`,
 * and `/try` in its four states (no visit, a visit, a member's preview, a page
 * not set up), against the fake engine. The assistant on `/try` is the member
 * app's own, driven here through the same scripted Google socket as
 * `-assistant.test.tsx`.
 */

beforeEach(() => {
  FakeLiveSocket.reset()
  vi.stubGlobal('WebSocket', FakeLiveSocket)
})

afterEach(() => {
  setAudioFactory(null)
  vi.unstubAllGlobals()
})

const BUDGET = "Today's demo budget is used up. Nothing was started. Text and voice open again tomorrow."

function visitor(options: EngineOptions = {}) {
  const engine = createEngine({ signedIn: false, ...options })
  engine.demo.session = {
    role: 'recruiter',
    expires_at: new Date(Date.now() + 2 * 3_600_000).toISOString(),
    ref: 'acme',
  }
  return engine
}

function expiryLine(iso: string) {
  return `Your demo access lasts until ${new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}.`
}

/**
 * The phone's one entry point, the bar pinned to the bottom ("Talk to <name>"
 * on this voice-first page): opens the sheet. Any companion name by default: the fake engine answers the
 * companion read for a visit, which the real one refuses (ADR 0068).
 */
async function openTryChat(name: string | RegExp = /\w+/) {
  const named = (prefix: string) => (typeof name === 'string' ? `${prefix} ${name}` : new RegExp(`^${prefix} ${name.source}$`))
  fireEvent.click(await screen.findByRole('button', { name: named('Talk to') }))
  return screen.findByRole('dialog', { name: named('Conversation with') })
}

/** The voice-first sheet and dock fold the chat under the orb; unfold it to type. */
function openTyping(panel: HTMLElement) {
  fireEvent.click(within(panel).getByRole('button', { name: 'Type instead' }))
}

async function connect() {
  await waitFor(() => expect(FakeLiveSocket.instances).toHaveLength(1))
  FakeLiveSocket.last.open()
  FakeLiveSocket.last.serve({ setupComplete: {} })
  await act(settle)
  return FakeLiveSocket.last
}

describe('the access code on /login', () => {
  test('sits beside sign-in, and a right code lands on the recruiter page', async () => {
    const engine = createEngine({ signedIn: false })
    const { router } = renderApp('/login', engine, 'family')
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    const box = screen.getByRole('region', { name: 'Have an access code?' })
    fireEvent.change(within(box).getByLabelText('Access code'), { target: { value: ' OPEN-SESAME ' } })
    fireEvent.click(within(box).getByRole('button', { name: 'Open the demo' }))

    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/try')
    expect(engine.demo.enterBodies).toEqual([{ code: 'OPEN-SESAME' }])
  })

  test('says under the code box that conversations may be recorded', async () => {
    renderApp('/login', createEngine({ signedIn: false }), 'family')
    const box = await screen.findByRole('region', { name: 'Have an access code?' })
    expect(
      within(box).getByText('Conversations in the demo may be recorded to improve the assistant.'),
    ).toBeInTheDocument()
  })

  test('a wrong code says so in the engine words and stays on sign-in', async () => {
    const engine = createEngine({ signedIn: false })
    const { router } = renderApp('/login', engine, 'family')
    const box = await screen.findByRole('region', { name: 'Have an access code?' })
    fireEvent.change(within(box).getByLabelText('Access code'), { target: { value: 'nope' } })
    fireEvent.click(within(box).getByRole('button', { name: 'Open the demo' }))
    expect(await within(box).findByRole('alert')).toHaveTextContent(WRONG_CODE)
    expect(router.state.location.pathname).toBe('/login')
    // The wait line goes with the wait: an error is never shown under "setting up".
    expect(within(box).queryByText(ENTER_WAIT_LINE)).not.toBeInTheDocument()
  })

  test('while the code is entered, it says what is happening and about how long', async () => {
    const engine = createEngine({ signedIn: false })
    renderApp('/login', engine, 'family')
    const box = await screen.findByRole('region', { name: 'Have an access code?' })
    const answer = globalThis.fetch
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => (release = resolve))
    vi.stubGlobal('fetch', async (input: Request) => {
      if (new URL(input.url).pathname === '/api/demo/enter') await held
      return answer(input)
    })
    fireEvent.change(within(box).getByLabelText('Access code'), { target: { value: 'nope' } })
    fireEvent.click(within(box).getByRole('button', { name: 'Open the demo' }))
    expect(await within(box).findByText(ENTER_WAIT_LINE)).toBeInTheDocument()
    await act(async () => {
      release()
      await settle()
    })
    expect(await within(box).findByRole('alert')).toHaveTextContent(WRONG_CODE)
    expect(within(box).queryByText(ENTER_WAIT_LINE)).not.toBeInTheDocument()
  })

  test.each([
    [429, TOO_MANY],
    [503, SEED_FAILED],
  ])('a %i says what happened in words', async (status, detail) => {
    const engine = createEngine({ signedIn: false })
    engine.demo.enterReply = { status, body: { detail } }
    renderApp('/login', engine, 'family')
    const box = await screen.findByRole('region', { name: 'Have an access code?' })
    fireEvent.change(within(box).getByLabelText('Access code'), { target: { value: 'OPEN-SESAME' } })
    fireEvent.click(within(box).getByRole('button', { name: 'Open the demo' }))
    expect(await within(box).findByRole('alert')).toHaveTextContent(detail)
  })
})

describe('/try', () => {
  test('with no visit shows only the code box and a way back to sign in', async () => {
    renderApp('/try', createEngine({ signedIn: false }), 'family')
    expect(await screen.findByLabelText('Access code')).toBeInTheDocument()
    expect(
      screen.getByText('Conversations in the demo may be recorded to improve the assistant.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
    expect(screen.queryByRole('heading', { name: 'Talk to my assistant' })).not.toBeInTheDocument()
    expect(screen.queryByRole('log')).not.toBeInTheDocument()
  })

  test('typing the code on /try opens the page in place', async () => {
    const engine = createEngine({ signedIn: false })
    renderApp('/try?ref=acme', engine, 'family')
    fireEvent.change(await screen.findByLabelText('Access code'), { target: { value: 'OPEN-SESAME' } })
    fireEvent.click(screen.getByRole('button', { name: 'Open the demo' }))
    expect(await screen.findByRole('heading', { name: 'Experience' })).toBeInTheDocument()
    expect(engine.demo.enterBodies).toEqual([{ code: 'OPEN-SESAME', ref: 'acme' }])
  })

  test('a direct link opens the full page with no gate, entered once on load; the code leaves the address bar', async () => {
    // Kevin, 2026-11-22: the link shows what a typed code shows. The audio still waits for the tap.
    const engine = createEngine({ signedIn: false, assistant: { engine: 'cascade' } })
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    const { router } = renderApp('/try?code=OPEN-SESAME&ref=acme', engine, 'family')
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    expect(screen.queryByLabelText('Access code')).not.toBeInTheDocument()
    expect(engine.demo.enterBodies).toEqual([{ code: 'OPEN-SESAME', ref: 'acme' }])
    expect(router.state.location.search).toEqual({ ref: 'acme' })
    expect(router.history.location.href).not.toContain('code=')
    expect(screen.getByText(STAGE_LINE)).toBeInTheDocument()
    expect(screen.getByText(RECORDING_NOTICE)).toBeInTheDocument()
    // Nothing is heard or played before the tap.
    expect(audio.micOpen).toBe(false)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^Talk to / }))
      await settle()
    })
    // The tap starts the conversation on the visit already entered: no second entry.
    expect(engine.demo.enterBodies).toHaveLength(1)
    expect(engine.demo.funnel).toEqual(expect.arrayContaining(['page_load:acme', 'orb_tap:acme']))
    // The stage stays above the page: typing and the ask; no tour (Kevin, 2026-11-22). Chips come
    // from the engine after the greeting (`demo/suggestions.py`); the page writes none of its own.
    expect(screen.queryByRole('button', { name: /tour/i })).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /^Type to / })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'His resume (PDF)' })).toBeInTheDocument()
  })

  /**
   * The browser run, 2026-11-19 (`server/tests/realrun_try_orb_flow.mjs`): entering unmounted the
   * whole voice stage and mounted a new one (the orb restarted, the page modal's provider with it).
   * Entering adds the page under the stage; the stage stays.
   */
  test('entering keeps the same stage: nothing above the page is mounted again', async () => {
    const engine = createEngine({ signedIn: false, assistant: { engine: 'cascade' } })
    setAudioFactory(() => new FakeAudio())
    renderApp('/try?code=OPEN-SESAME&ref=acme', engine, 'family')
    const answer = globalThis.fetch
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => (release = resolve))
    vi.stubGlobal('fetch', async (input: Request) => {
      if (new URL(input.url).pathname === '/api/demo/enter') await held
      return answer(input)
    })
    const orb = await screen.findByRole('button', { name: /^Talk to / })
    const stage = screen.getByRole('region', { name: 'Talk to the demo assistant' })
    expect(await screen.findByText(ENTER_WAIT_LINE)).toBeInTheDocument()
    await act(async () => {
      release()
      await settle()
    })
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Talk to the demo assistant' })).toBe(stage)
    expect(document.querySelector('[data-stage="orb"]')).toBe(orb)
  })

  test('a tap during the on-load entry waits for it: one entry, and the tap is not undone', async () => {
    const engine = createEngine({ signedIn: false, assistant: { engine: 'cascade' } })
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    renderApp('/try?code=OPEN-SESAME&ref=acme', engine, 'family')
    const answer = globalThis.fetch
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => (release = resolve))
    vi.stubGlobal('fetch', async (input: Request) => {
      if (new URL(input.url).pathname === '/api/demo/enter') await held
      return answer(input)
    })
    const orb = await screen.findByRole('button', { name: /^Talk to / })
    expect(await screen.findByText(ENTER_WAIT_LINE)).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(orb)
      await settle()
    })
    await act(async () => {
      release()
      await settle()
    })
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    // The conversation the tap opened is the one still running: never disposed, mic still held.
    expect(audio.disposed).toBe(false)
    expect(audio.micOpen).toBe(true)
    expect(engine.demo.enterBodies).toHaveLength(1)
  })

  test('an entry the conversation gave up on is aborted, never touches the page when it answers late, and the next tap enters afresh', async () => {
    const engine = createEngine({ signedIn: false, assistant: { engine: 'cascade' } })
    setAudioFactory(() => new FakeAudio())
    renderApp('/try?code=OPEN-SESAME', engine, 'family')
    const answer = globalThis.fetch
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => (release = resolve))
    const entries: Request[] = []
    vi.stubGlobal('fetch', async (input: Request) => {
      if (new URL(input.url).pathname === '/api/demo/enter') {
        entries.push(input)
        if (entries.length === 1) await held
      }
      return answer(input)
    })
    const orb = await screen.findByRole('button', { name: /^Talk to / })
    await waitFor(() => expect(entries).toHaveLength(1))
    // A tap waits on the entry made on load.
    await act(async () => {
      fireEvent.click(orb)
      await settle()
    })
    // Still entering after two seconds: a tap gives up on it, and its request is aborted.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, ENTRY_TAP_MS + 100))
    })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^(Talk to|Interrupt) / }))
      await settle()
    })
    expect(entries[0].signal.aborted).toBe(true)
    // It answers late, refused: the page does not switch to the code box.
    engine.demo.enterReply = { status: 403, body: { detail: WRONG_CODE } }
    await act(async () => {
      release()
      await settle()
    })
    expect(screen.queryByText(WRONG_CODE)).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Access code')).not.toBeInTheDocument()
    // The next tap makes a fresh request with the link's code, and enters.
    engine.demo.enterReply = null
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^Talk to / }))
      await settle()
    })
    await waitFor(() => expect(entries).toHaveLength(2))
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
  })

  test('a link visit that runs out waits for a tap, which enters again (never the member preview, never on its own)', async () => {
    const engine = createEngine({ signedIn: false, assistant: { engine: 'cascade' } })
    setAudioFactory(() => new FakeAudio())
    renderApp('/try?code=OPEN-SESAME', engine, 'family')
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    expect(engine.demo.enterBodies).toHaveLength(1)
    // The two hours run out: the engine holds no visit, and the page asks again.
    engine.demo.session = null
    engine.demo.ended = true
    await act(async () => {
      focusManager.setFocused(false)
      focusManager.setFocused(true)
      await settle()
    })
    focusManager.setFocused(undefined)
    expect(await screen.findByText('Your demo visit has ended. Tap the orb to start a new one.')).toBeInTheDocument()
    expect(screen.queryByText(/^Preview\./)).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1, name: sampleContent().hero.title })).not.toBeInTheDocument()
    await act(settle)
    expect(engine.demo.enterBodies).toHaveLength(1)
    // A tap enters again with the link's code.
    engine.demo.ended = false
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /^Talk to / }))
      await settle()
    })
    await waitFor(() => expect(engine.demo.enterBodies).toHaveLength(2))
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
  })

  test('a link is not entered while the tab is hidden, and is entered once it is shown', async () => {
    const engine = createEngine({ signedIn: false, assistant: { engine: 'cascade' } })
    setAudioFactory(() => new FakeAudio())
    let visibility: DocumentVisibilityState = 'hidden'
    const spy = vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
    try {
      renderApp('/try?code=OPEN-SESAME', engine, 'family')
      await screen.findByRole('button', { name: /^Talk to / })
      await act(settle)
      expect(engine.demo.enterBodies).toEqual([])
      visibility = 'visible'
      await act(async () => {
        document.dispatchEvent(new Event('visibilitychange'))
        await settle()
      })
      expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
      expect(engine.demo.enterBodies).toEqual([{ code: 'OPEN-SESAME' }])
    } finally {
      spy.mockRestore()
    }
  })

  test('a link opened in a hidden tab: the wait bar starts with the entry, never nearly full before it', async () => {
    const engine = createEngine({ signedIn: false, assistant: { engine: 'cascade' } })
    setAudioFactory(() => new FakeAudio())
    let visibility: DocumentVisibilityState = 'hidden'
    const spy = vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
    const realNow = Date.now
    try {
      renderApp('/try?code=OPEN-SESAME', engine, 'family')
      await screen.findByRole('button', { name: /^Talk to / })
      await act(settle)
      // Hidden: no entry, so no bar and no "about 15 seconds" yet.
      expect(await screen.findByText('Opening the demo…')).toBeInTheDocument()
      expect(document.querySelector('[data-stage="enter-bar"]')).toBeNull()
      // A minute later the visitor looks at the tab; the entry starts and is held.
      const later = realNow() + 60_000
      vi.spyOn(Date, 'now').mockImplementation(() => later)
      const answer = globalThis.fetch
      let release: () => void = () => {}
      const held = new Promise<void>((resolve) => (release = resolve))
      vi.stubGlobal('fetch', async (input: Request) => {
        if (new URL(input.url).pathname === '/api/demo/enter') await held
        return answer(input)
      })
      visibility = 'visible'
      await act(async () => {
        document.dispatchEvent(new Event('visibilitychange'))
        await settle()
      })
      expect(await screen.findByText(ENTER_WAIT_LINE)).toBeInTheDocument()
      const bar = document.querySelector<HTMLElement>('[data-stage="enter-bar"]')!
      expect(Number(/scaleX\(([\d.]+)\)/.exec(bar.style.transform)![1])).toBeLessThan(0.05)
      await act(async () => {
        release()
        await settle()
      })
      expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    } finally {
      spy.mockRestore()
      vi.mocked(Date.now).mockRestore?.()
    }
  })

  test('a link opened over a visit this browser already holds enters nothing new', async () => {
    const engine = visitor({ assistant: { engine: 'cascade' } })
    setAudioFactory(() => new FakeAudio())
    renderApp('/try?code=OPEN-SESAME', engine, 'family')
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    await act(settle)
    expect(engine.demo.enterBodies).toEqual([])
  })

  test("a link opened by a signed-in member enters nothing and keeps the member's preview", async () => {
    const engine = createEngine()
    renderApp('/try?code=OPEN-SESAME', engine, 'family')
    expect(await screen.findByText(/^Preview\./)).toBeInTheDocument()
    await act(settle)
    expect(engine.demo.enterBodies).toEqual([])
  })

  test("a member's preview stays put while the visit question is asked again", async () => {
    const engine = createEngine()
    renderApp('/try', engine, 'family')
    expect(await screen.findByText(/^Preview\./)).toBeInTheDocument()
    const answer = globalThis.fetch
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => (release = resolve))
    let asked = 0
    vi.stubGlobal('fetch', async (input: Request) => {
      if (new URL(input.url).pathname === '/api/demo/session') {
        asked += 1
        await held
      }
      return answer(input)
    })
    // Coming back to the tab asks again (refetch on focus).
    await act(async () => {
      focusManager.setFocused(false)
      focusManager.setFocused(true)
      await settle()
    })
    expect(asked).toBe(1)
    expect(screen.getByText(/^Preview\./)).toBeInTheDocument()
    expect(screen.queryByLabelText('Access code')).not.toBeInTheDocument()
    await act(async () => {
      release()
      await settle()
    })
    focusManager.setFocused(undefined)
  })

  test('a visit reloaded without its link gets the voice stage too', async () => {
    const engine = visitor({ assistant: { engine: 'cascade' } })
    setAudioFactory(() => new FakeAudio())
    renderApp('/try', engine, 'family')
    expect(await screen.findByRole('textbox', { name: /^Type to / })).toBeInTheDocument()
    // The stage runs on the engine's cascade (the client is attached, not only the form drawn).
    await waitFor(() => expect(screen.getByRole('region', { name: 'Talk to the demo assistant' })).toHaveAttribute('data-client', 'cascade'))
    expect(screen.getByText(RECORDING_NOTICE)).toBeInTheDocument()
    // No second chat: the stage is the conversation.
    expect(screen.getAllByRole('button', { name: /^Talk to / })).toHaveLength(1)
  })

  test('a code typed in the box opens the voice stage on the cascade', async () => {
    const engine = createEngine({ signedIn: false, assistant: { engine: 'cascade' } })
    setAudioFactory(() => new FakeAudio())
    renderApp('/try', engine, 'family')
    fireEvent.change(await screen.findByLabelText('Access code'), { target: { value: 'OPEN-SESAME' } })
    fireEvent.click(screen.getByRole('button', { name: 'Open the demo' }))
    expect(await screen.findByRole('textbox', { name: /^Type to / })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('region', { name: 'Talk to the demo assistant' })).toHaveAttribute('data-client', 'cascade'))
  })

  test('when the engine says Live, a link visit gets the Live page, and no cascade turn is asked', async () => {
    const engine = createEngine({ signedIn: false })
    setAudioFactory(() => new FakeAudio())
    renderApp('/try?code=OPEN-SESAME', engine, 'family')
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('textbox', { name: /^Type to / })).not.toBeInTheDocument())
    expect(engine.unhandled).not.toContain('POST /api/assistant/turn')
  })

  test('a direct link with a wrong code says why in words, and falls back to the code box', async () => {
    const engine = createEngine({ signedIn: false })
    setAudioFactory(() => new FakeAudio())
    const { router } = renderApp('/try?code=stale', engine, 'family')
    expect(await screen.findByRole('alert')).toHaveTextContent(WRONG_CODE)
    expect(screen.getByLabelText('Access code')).toBeInTheDocument()
    expect(router.history.location.href).not.toContain('stale')
  })

  test('a visit renders every section of the page from the engine', async () => {
    const engine = visitor()
    const content = sampleContent()
    const { container } = renderApp('/try', engine, 'family')

    expect(await screen.findByRole('heading', { level: 1, name: content.hero.title })).toBeInTheDocument()
    expect(screen.getByText(expiryLine(engine.demo.session!.expires_at))).toBeInTheDocument()
    expect(screen.getByText('Houston, open to relocation')).toBeInTheDocument()
    // No "What to look for" (Kevin, 2026-11-22: out of place).
    expect(screen.queryByRole('heading', { name: 'What to look for' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'What I built at work' })).toBeInTheDocument()
    expect(screen.getByText('~770')).toBeInTheDocument()

    // The diagram is an image of the SVG, never markup injected into the page.
    const diagram = screen.getByRole('img', { name: content.work!.diagram_caption })
    expect(diagram.getAttribute('src')).toBe(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(SAMPLE_SVG)}`)
    expect(container.querySelector('text')).toBeNull()
    expect(container.innerHTML).not.toContain('bronze &amp; silver')

    const report = screen.getByTitle('Report samples')
    expect(report.tagName).toBe('IFRAME')
    expect(report).toHaveAttribute('sandbox', 'allow-scripts')
    expect(report).toHaveAttribute('src', '/static/demo/report.html')

    expect(screen.getByRole('link', { name: 'Download the resume (PDF)' })).toHaveAttribute('href', '/static/demo/resume.pdf')
    expect(screen.getByRole('link', { name: 'LinkedIn' })).toHaveAttribute('href', content.links.linkedin)
    expect(screen.getByRole('link', { name: 'kevin@example.test' })).toHaveAttribute('href', 'mailto:kevin@example.test')
    expect(screen.queryByText(/Preview\./)).not.toBeInTheDocument()
  })

  test('no report and no resume: neither the frame nor the link is drawn', async () => {
    const engine = visitor()
    engine.demo.content = { ...sampleContent(), assets: { report: null, resume: null } }
    renderApp('/try', engine, 'family')
    await screen.findByRole('heading', { name: 'Experience' })
    expect(screen.queryByTitle('Report samples')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Download the resume (PDF)' })).not.toBeInTheDocument()
  })

  test('phone: no chat in the page, one Ask bar at the bottom, no orb; it opens the sheet', async () => {
    renderApp('/try', visitor(), 'family')
    await screen.findByRole('heading', { name: 'Experience' })
    // Kevin, 2026-11-03: "remove the talk to my assistant chat window in the middle".
    expect(screen.queryByRole('region', { name: 'Talk to my assistant' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Talk' })).not.toBeInTheDocument()
    expect(screen.queryByRole('search')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('log')).not.toBeInTheDocument()
    // One chat entry point: the bar, never also the members' floating orb. The hero's Ask
    // (Kevin, 2026-11-05) starts the same conversation and is the only other way in.
    const hero = screen.getByRole('region', { name: sampleContent().hero.title })
    const entries = screen
      .getAllByRole('button', { name: /^(Ask|Talk to|Open the conversation with) / })
      .filter((entry) => !hero.contains(entry))
    expect(entries).toHaveLength(1)
    expect(entries[0].className).not.toMatch(/fixed/)
    expect(within(hero).getAllByRole('button', { name: /^Ask / })).toHaveLength(1)

    const sheet = await openTryChat()
    // Voice first here too (Kevin, 2026-11-04): the orb, the chat folded, no keyboard.
    expect(within(sheet).getByRole('button', { name: /^Talk to / })).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: /^Minimise/ })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: /^Close/ })).toBeInTheDocument()
    openTyping(sheet)
    expect(screen.getAllByRole('log')).toHaveLength(1)
    expect(within(sheet).getByRole('log', { name: /^Conversation with/ })).toBeInTheDocument()
    expect(within(sheet).getByRole('textbox', { name: /^Message / })).toHaveFocus()
    expect(within(sheet).getByText('Demo assistant, made-up data')).toBeInTheDocument()
    // The old section's copy lives in the chat now.
    expect(within(sheet).getByText(/A demo conversation lasts up to 5 minutes\./)).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: "What's Kevin's experience with Power BI?" })).toBeInTheDocument()
    expect(within(sheet).getByText(/may be recorded to improve/)).toBeInTheDocument()
    // While the sheet is up the bar is gone: still one way in.
    expect(document.querySelector('[data-chat="askbar"]')).not.toBeInTheDocument()
  })

  test('desk: the dock is open on load beside the page, with no close and no chat in the middle', async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    const engine = visitor()
    renderApp('/try', engine, 'workbench')
    // Kevin, 2026-11-03: "keep the right panel window open at all times".
    const dock = await screen.findByRole('complementary', { name: /^Conversation with/ })
    expect(dock).toHaveAttribute('id', 'assistant-dock')
    await screen.findByRole('heading', { name: 'Experience' })
    expect(screen.queryByRole('region', { name: 'Talk to my assistant' })).not.toBeInTheDocument()
    expect(screen.queryByRole('search')).not.toBeInTheDocument()
    // Voice is the star (Kevin, 2026-11-04): the orb is the talk button, typing folds under it.
    expect(within(dock).getByRole('button', { name: /^Talk to / })).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    const toggle = within(dock).getByRole('button', { name: 'Type instead' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(within(dock).getByRole('button', { name: 'Hide the chat' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getAllByRole('log')).toHaveLength(1)
    expect(screen.getAllByRole('textbox')).toEqual([within(dock).getByRole('textbox', { name: /^Message / })])
    // No close, and no floating orb or Ask bar beside it.
    expect(within(dock).queryByRole('button', { name: /^Close/ })).not.toBeInTheDocument()
    expect(document.querySelector('[data-chat="askbar"]')).not.toBeInTheDocument()
    // The page column is beside the dock, not under it.
    expect(dock.parentElement?.querySelector('.rc-column')).toContainElement(screen.getByRole('heading', { level: 1 }))
    // Its copy: the 5-minute line, the chips, the recording notice.
    expect(within(dock).getByText(/A demo conversation lasts up to 5 minutes\./)).toBeInTheDocument()
    expect(within(dock).getByRole('button', { name: 'Which card charges are unverified?' })).toBeInTheDocument()
    expect(within(dock).getByText(/may be recorded to improve/)).toBeInTheDocument()

    // A voice start from the dock still sends the hidden opening turn, once.
    fireEvent.click(within(dock).getByRole('button', { name: /^Talk to / }))
    const socket = await connect()
    expect(socket.sentOf('clientContent')).toEqual([
      { clientContent: { turns: [{ role: 'user', parts: [{ text: OPENING_TURN }] }], turnComplete: true } },
    ])
    socket.serve({ serverContent: { outputTranscription: { text: 'Good afternoon.' } } })
    socket.serve({ serverContent: { turnComplete: true } })
    await act(settle)
    expect(socket.sentOf('clientContent')).toHaveLength(1)
    expect(screen.getByRole('complementary', { name: /^Conversation with/ })).toBe(dock)
  })

  test('the assistant is the member app chat; a voice start sends the hidden opening turn', async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    const engine = visitor()
    renderApp('/try', engine, 'family')

    const sheet = await openTryChat()
    openTyping(sheet)
    expect(within(sheet).getByRole('button', { name: "What's Kevin's experience with Power BI?" })).toBeInTheDocument()
    // Kevin, 2026-11-02: no receipt ingestion talk; a chip the recruiter tools can answer instead.
    expect(within(sheet).getByRole('button', { name: 'Which card charges are unverified?' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /receipt/i })).not.toBeInTheDocument()
    // Beside the mic: the recording notice.
    expect(within(sheet).getByText(/may be recorded to improve/)).toBeInTheDocument()

    fireEvent.click(within(sheet).getByRole('button', { name: /^Talk to / }))
    const socket = await connect()
    expect(engine.assistant.minted).toBe(1)
    expect(socket.sentOf('clientContent')).toEqual([
      { clientContent: { turns: [{ role: 'user', parts: [{ text: OPENING_TURN }] }], turnComplete: true } },
    ])
    expect(screen.queryByText(OPENING_TURN)).not.toBeInTheDocument()
    // Named by the mint now.
    expect(await screen.findByRole('log', { name: 'Conversation with Dorothy' })).toBeInTheDocument()
    expect(
      screen.getByText('Conversations with Dorothy may be recorded to improve the assistant.'),
    ).toBeInTheDocument()

    // The greeting's turn ends: the browser sends its half of the record.
    socket.serve({ serverContent: { outputTranscription: { text: 'Good afternoon.' } } })
    socket.serve({ serverContent: { turnComplete: true } })
    await waitFor(() => expect(engine.assistant.recordUploads).toHaveLength(1))
    const kinds = engine.assistant.recordUploads[0].events.map((event) => event.kind)
    expect(kinds).toEqual(['mic_open', 'latency', 'assistant', 'turn_complete'])
    // Still one conversation on the page, and the opening turn went once.
    expect(screen.getAllByRole('log')).toHaveLength(1)
    expect(socket.sentOf('clientContent')).toHaveLength(1)
  })

  test("a visitor's conversation ends when its token does, with the demo's sentence", async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    // The engine caps a recruiter's token at 5 minutes; a short one stands in here.
    const engine = visitor({ assistant: { sessionMs: 1_500 } })
    renderApp('/try', engine, 'family')
    const sheet = await openTryChat()
    fireEvent.click(within(sheet).getByRole('button', { name: /^Talk to / }))
    await connect()
    expect(VISITOR_SESSION_ENDED).toBe('Demo sessions last 5 minutes. Start another any time.')
    expect(await screen.findByText(VISITOR_SESSION_ENDED, {}, { timeout: 4_000 })).toBeInTheDocument()
    expect(audio.micOpen).toBe(false)
    expect(FakeLiveSocket.last.closed).toBe(true)
    openTyping(sheet)
    expect(screen.getByRole('textbox', { name: /Message/ })).toBeDisabled()
  })

  test("a member's preview is not cut off at its token time", async () => {
    const engine = createEngine({ assistant: { companionName: 'Mabel', sessionMs: 1_500 } })
    renderApp('/try', engine, 'family')
    const sheet = await openTryChat('Mabel')
    openTyping(sheet)
    const box = within(sheet).getByRole('textbox', { name: 'Message Mabel' })
    fireEvent.change(box, { target: { value: 'hello' } })
    fireEvent.click(within(sheet).getByRole('button', { name: 'Send' }))
    await connect()
    expect(within(sheet).getByText('hello')).toBeInTheDocument()
    await act(() => new Promise((resolve) => setTimeout(resolve, 2_000)))
    expect(screen.queryByText(VISITOR_SESSION_ENDED)).not.toBeInTheDocument()
    expect(within(sheet).getByRole('textbox', { name: 'Message Mabel' })).not.toBeDisabled()
  })

  test('a spent demo budget is shown in the engine words', async () => {
    const engine = visitor({ assistant: { mint: { status: 429, body: { detail: BUDGET } } } })
    renderApp('/try', engine, 'family')
    const sheet = await openTryChat()
    openTyping(sheet)
    const box = within(sheet).getByRole('textbox', { name: /^Message / })
    fireEvent.change(box, { target: { value: 'hello' } })
    fireEvent.click(within(sheet).getByRole('button', { name: 'Send' }))
    const alert = await within(sheet).findByRole('alert')
    expect(alert).toHaveTextContent(BUDGET)
    expect(alert).not.toHaveTextContent('in a little while')
  })

  test('a member preview gets no access note: no visit, no copy of the household', async () => {
    renderApp('/try', createEngine({ assistant: { companionName: 'Mabel' } }), 'family')
    const hero = await screen.findByRole('region', { name: sampleContent().hero.title })
    expect(within(hero).queryByRole('link', { name: 'Open the app' })).not.toBeInTheDocument()
    expect(screen.queryByText(/deleted when the visit ends/)).not.toBeInTheDocument()
  })

  test('the header names the page owner when the content does, and LEGION when not', async () => {
    const engine = visitor()
    renderApp('/try', engine, 'family')
    const header = (await screen.findByRole('navigation', { name: 'On this page' })).parentElement!
    expect(within(header).getByText('LEGION')).toBeInTheDocument()
    // No phone section in the content: no Phone entry in the nav either.
    expect(within(header).queryByRole('link', { name: 'Phone' })).not.toBeInTheDocument()
  })

  test('the LEGION section draws its parts and a nav entry, and is absent without content', async () => {
    const engine = visitor()
    engine.demo.content = {
      ...sampleContent(),
      legion: {
        title: 'How LEGION is built',
        intro: 'A personal-life ERP a voice agent can query.',
        diagram_svg: '<svg xmlns="http://www.w3.org/2000/svg"/>',
        diagram_caption: 'The engine is the only writer.',
        parts: [{ title: 'The engine', body: 'Django and Postgres.' }],
      },
    }
    renderApp('/try', engine, 'family')
    const section = await screen.findByRole('region', { name: 'How LEGION is built' })
    expect(within(section).getByText('A personal-life ERP a voice agent can query.')).toBeInTheDocument()
    expect(within(section).getByText('The engine.')).toBeInTheDocument()
    expect(within(section).getByRole('img', { name: 'The engine is the only writer.' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'On this page' })
    expect(within(nav).getByRole('link', { name: 'LEGION' })).toHaveAttribute('href', '#legion')
  })

  test('without a legion part there is no LEGION section or nav entry', async () => {
    renderApp('/try', visitor(), 'family')
    const nav = await screen.findByRole('navigation', { name: 'On this page' })
    expect(within(nav).queryByRole('link', { name: 'LEGION' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'How LEGION is built' })).not.toBeInTheDocument()
  })

  test('a version with no work section draws no Work section, nav entry or report', async () => {
    // The LinkedIn version (Kevin, 2026-11-04): no work section, no report, no resume.
    const engine = visitor()
    const { work, ...rest } = sampleContent()
    engine.demo.content = { ...rest, assets: { report: null, resume: null } }
    renderApp('/try', engine, 'family')
    const nav = await screen.findByRole('navigation', { name: 'On this page' })
    expect(within(nav).queryByRole('link', { name: 'Work' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: work!.title })).not.toBeInTheDocument()
    expect(screen.queryByTitle('Report samples')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Download the resume (PDF)' })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Experience' })).toBeInTheDocument()
  })

  test('the header shows content.name', async () => {
    const engine = visitor()
    engine.demo.content = { ...sampleContent(), name: 'Test Person' }
    renderApp('/try', engine, 'family')
    const header = (await screen.findByRole('navigation', { name: 'On this page' })).parentElement!
    expect(within(header).getByText('Test Person')).toBeInTheDocument()
  })

  test('a page with no words yet says so', async () => {
    const engine = visitor()
    engine.demo.content = null
    renderApp('/try', engine, 'family')
    expect(await screen.findByRole('heading', { name: "This page isn't set up yet" })).toBeInTheDocument()
  })

  test('a member preview at desk width gets the same pinned dock', async () => {
    renderApp('/try', createEngine({ assistant: { companionName: 'Mabel' } }), 'workbench')
    expect(await screen.findByText(/Preview\. This is what a visitor/)).toBeInTheDocument()
    const dock = await screen.findByRole('complementary', { name: 'Conversation with Mabel' })
    expect(within(dock).getByText('Your assistant (preview)')).toBeInTheDocument()
    expect(within(dock).queryByRole('button', { name: /^Close/ })).not.toBeInTheDocument()
    expect(within(dock).getByRole('button', { name: 'Talk to Mabel' })).toBeInTheDocument()
    fireEvent.click(within(dock).getByRole('button', { name: 'Type instead' }))
    expect(screen.getAllByRole('textbox')).toHaveLength(1)
    expect(screen.queryByRole('search')).not.toBeInTheDocument()
  })

  test('desk: the orb starts voice and stops it, in words beside it', async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    renderApp('/try', visitor(), 'workbench')
    const dock = await screen.findByRole('complementary', { name: /^Conversation with/ })
    await screen.findByRole('heading', { name: 'Experience' })
    expect(within(dock).getByRole('status')).toHaveTextContent('Tap to talk')

    fireEvent.click(within(dock).getByRole('button', { name: /^Talk to / }))
    const socket = await connect()
    // Live and listening: the orb now stops, and a Stop link says so in words too.
    const stop = await within(dock).findByRole('button', { name: 'Stop talking. Typing continues.' })
    expect(within(dock).getByRole('button', { name: 'Stop' })).toBeInTheDocument()
    // The hidden opening turn is out, so the companion is working on its greeting.
    expect(within(dock).getByRole('status')).toHaveTextContent(/is checking…$/)
    expect(socket.sentOf('clientContent')).toHaveLength(1)
    fireEvent.click(stop)
    await act(settle)
    expect(within(dock).getByRole('button', { name: /^Talk to / })).toBeInTheDocument()
    expect(within(dock).queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument()
  })

  test("desk: a voice-only visitor sees the latest tool card on the stage, and Type instead still opens", async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    const engine = visitor({
      assistant: {
        tools: {
          monthly_spend: { text: 'Categorised spend.', card: SPEND_CARD },
          list_events: { text: 'Events.', card: DAY_CARD },
        },
      },
    })
    renderApp('/try', engine, 'workbench')
    const dock = await screen.findByRole('complementary', { name: /^Conversation with/ })
    await screen.findByRole('heading', { name: 'Experience' })
    fireEvent.click(within(dock).getByRole('button', { name: /^Talk to / }))
    const socket = await connect()

    socket.serve({ toolCall: { functionCalls: [{ id: 'c1', name: 'monthly_spend', args: {} }] } })
    const stage = within(dock).getByRole('region', { name: 'Voice' })
    expect(await within(stage).findByRole('region', { name: 'Spending by category' })).toBeInTheDocument()
    // The stage shows one card: the newest replaces it.
    socket.serve({ toolCall: { functionCalls: [{ id: 'c2', name: 'list_events', args: {} }] } })
    expect(await within(stage).findByRole('region', { name: "What's on" })).toBeInTheDocument()
    expect(within(stage).queryByRole('region', { name: 'Spending by category' })).not.toBeInTheDocument()
    expect(dock.textContent).not.toMatch(/monthly_spend|list_events|Categorised spend/)

    // Type instead: the transcript carries both cards where the tools ran, and the stage none.
    fireEvent.click(within(dock).getByRole('button', { name: 'Type instead' }))
    const log = within(dock).getByRole('log')
    expect(within(log).getByRole('region', { name: 'Spending by category' })).toBeInTheDocument()
    expect(within(log).getByRole('region', { name: "What's on" })).toBeInTheDocument()
    expect(within(stage).queryByRole('region', { name: "What's on" })).not.toBeInTheDocument()
    expect(within(dock).getByRole('textbox', { name: /^Message / })).toBeInTheDocument()

    // Hidden again, a new spoken question clears the stage until its own answer brings a card.
    fireEvent.click(within(dock).getByRole('button', { name: 'Hide the chat' }))
    expect(within(stage).getByRole('region', { name: "What's on" })).toBeInTheDocument()
    socket.serve({ serverContent: { inputTranscription: { text: 'And tomorrow?' } } })
    await waitFor(() => expect(within(stage).queryByRole('region', { name: "What's on" })).not.toBeInTheDocument())
  })

  test('desk: a blocked microphone is said above the orb and the chat opens for typing', async () => {
    const audio = new FakeAudio()
    audio.failWith = new AudioError('denied', 'The microphone is blocked.')
    setAudioFactory(() => audio)
    renderApp('/try', visitor(), 'workbench')
    const dock = await screen.findByRole('complementary', { name: /^Conversation with/ })
    await screen.findByRole('heading', { name: 'Experience' })
    fireEvent.click(within(dock).getByRole('button', { name: /^Talk to / }))
    expect(await within(dock).findByRole('alert')).toHaveTextContent(/microphone is blocked/)
    expect(within(dock).getByRole('textbox', { name: /^Message / })).toBeInTheDocument()
    expect(within(dock).getByRole('button', { name: 'Hide the chat' })).toBeInTheDocument()
  })

  test('a signed-in member sees a preview, and the assistant is still theirs', async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    const engine = createEngine({ assistant: { companionName: 'Mabel' } })
    renderApp('/try', engine, 'family')
    expect(await screen.findByText(/Preview\. This is what a visitor/)).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    expect(screen.queryByText(/Your demo access lasts/)).not.toBeInTheDocument()

    const sheet = await openTryChat('Mabel')
    expect(within(sheet).getByText('Your assistant (preview)')).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Talk to Mabel' }))
    const socket = await connect()
    expect(socket.sentOf('clientContent')).toEqual([])
  })

  test('the visitor page has no way into the members web app (Kevin, 2026-11-22)', async () => {
    renderApp('/try', visitor(), 'family')
    // The hero's access note says what the visitor's copy is, and links nowhere.
    const hero = await screen.findByRole('region', { name: sampleContent().hero.title })
    expect(within(hero).getByText(/deleted when the visit ends/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Open the app' })).not.toBeInTheDocument()
    expect(document.querySelector('a[href="/"]')).toBeNull()
  })

  test('a recruiter whose visit ended lands back on /try, not on a broken page', async () => {
    const engine = createEngine({ signedIn: false })
    engine.demo.ended = true
    const { router } = renderApp('/', engine, 'family')
    expect(await screen.findByRole('heading', { name: 'Try LEGION' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/try')
  })

  test('someone with no session who opens the app still goes to sign in', async () => {
    const { router } = renderApp('/', createEngine({ signedIn: false }), 'family')
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })
})

/** A page with the Projects strip (anchor, anchor, no link) and one project section. Fake words. */
function withProjects(media: DemoSectionMedia = { note: 'Video coming soon.' }): DemoContent {
  return {
    ...sampleContent(),
    legion: { title: 'How LEGION is built', intro: 'A fake intro.', parts: [{ title: 'The engine', body: 'Fake.' }] },
    projects: [
      { title: 'Fake one', line: 'This page, lorem ipsum.', href: '#legion' },
      { title: 'Fake two', line: 'A fake dashboard.', href: '#fake-two' },
      { title: 'Fake three', line: 'In progress, lorem ipsum.' },
    ],
    project_sections: [
      {
        id: 'fake-two',
        title: 'Fake two',
        intro: 'A fake dashboard for a fake team.',
        parts: [{ title: 'Fake refresh', body: 'Every fake minute.' }],
        media,
      },
    ],
  }
}

function heroOf() {
  return screen.findByRole('region', { name: sampleContent().hero.title })
}

function typed(text: string) {
  return { clientContent: { turns: [{ role: 'user', parts: [{ text }] }], turnComplete: true } }
}

function follows(first: Node, second: Node): boolean {
  return (first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0
}

describe('/try: the hero Ask (Kevin, 2026-11-05)', () => {
  test('desk: Ask starts a voice conversation, the same as the orb, with the opening turn once', async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    const engine = visitor()
    renderApp('/try', engine, 'workbench')
    const dock = await screen.findByRole('complementary', { name: /^Conversation with/ })
    const hero = await heroOf()
    // Under the lead, before the dateline: near the top.
    const ask = within(hero).getByRole('button', { name: /^Ask / })
    expect(follows(ask, within(hero).getByText('Houston, open to relocation'))).toBe(true)
    fireEvent.click(ask)
    const socket = await connect()
    expect(audio.micOpen).toBe(true)
    expect(socket.sentOf('clientContent')).toEqual([typed(OPENING_TURN)])
    expect(await within(dock).findByRole('button', { name: 'Stop talking. Typing continues.' })).toBeInTheDocument()
    expect(engine.assistant.minted).toBe(1)
  })

  test('phone: Ask raises the sheet with the orb listening', async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    renderApp('/try', visitor(), 'family')
    fireEvent.click(within(await heroOf()).getByRole('button', { name: /^Ask / }))
    const sheet = await screen.findByRole('dialog', { name: /^Conversation with / })
    await connect()
    expect(audio.micOpen).toBe(true)
    expect(await within(sheet).findByRole('button', { name: 'Stop talking. Typing continues.' })).toBeInTheDocument()
  })

  test.each([
    ['workbench', 0],
    ['family', 1],
  ] as const)('%s: a first question starts the conversation typed, and the chat unfolds to show it', async (surface, index) => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    renderApp('/try', visitor(), surface)
    const question = FIRST_QUESTIONS[index]
    fireEvent.click(within(await heroOf()).getByRole('button', { name: question }))
    const socket = await connect()
    // The question is the first turn; the engine's prompt greets in a sentence, then answers it.
    expect(socket.sentOf('clientContent')).toEqual([typed(question)])
    expect(audio.micOpen).toBe(false)
    const log = await screen.findByRole('log', { name: /^Conversation with/ })
    expect(within(log).getByText(question)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hide the chat' })).toBeInTheDocument()
    if (surface === 'family') expect(screen.getByRole('dialog', { name: /^Conversation with / })).toContainElement(log)
  })

  test('the first questions are the two Kevin approved, and a preview gets the Ask too', async () => {
    expect(FIRST_QUESTIONS).toEqual(['What did this household spend last month?', 'How is LEGION built?'])
    renderApp('/try', createEngine({ assistant: { companionName: 'Mabel' } }), 'workbench')
    const hero = await heroOf()
    expect(await within(hero).findByRole('button', { name: 'Ask Mabel' })).toBeInTheDocument()
    for (const question of FIRST_QUESTIONS) {
      expect(within(hero).getByRole('button', { name: question })).toBeInTheDocument()
    }
  })
})

describe('/try: the Projects strip and project sections (Kevin, 2026-11-05)', () => {
  test('older content (no projects) draws no strip, no Projects nav, no project section', async () => {
    renderApp('/try', visitor(), 'family')
    const nav = await screen.findByRole('navigation', { name: 'On this page' })
    expect(within(nav).queryByRole('link', { name: 'Projects' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Projects' })).not.toBeInTheDocument()
    expect(document.querySelector('#fake-two')).toBeNull()
  })

  test('a project section carries its live demo link (a new tab), its mood and its orb mark (Kevin, 2026-11-22)', async () => {
    const engine = visitor()
    const content = withProjects()
    content.project_sections![0] = {
      ...content.project_sections![0],
      link: { href: 'https://demo.example.test', label: 'Try the fake demo' },
      theme: 'ledger',
      orb_mark: 'q',
    }
    engine.demo.content = content
    renderApp('/try', engine, 'family')
    const section = await screen.findByRole('region', { name: 'Fake two' })
    const link = within(section).getByRole('link', { name: 'Try the fake demo' })
    expect(link).toHaveAttribute('href', 'https://demo.example.test')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(section).toHaveAttribute('data-theme', 'ledger')
    expect(section).toHaveAttribute('data-orb-mark', 'q')
    // The page starts in LEGION's mood; the sections' own come as they reach the middle (a browser).
    expect(section.closest('.recruiter')).toHaveAttribute('data-page-theme', 'legion')
  })

  test("the LEGION section's quality block: figures as tiles, then the checks (Kevin, 2026-11-22)", async () => {
    const engine = visitor()
    const content = withProjects()
    content.legion = {
      ...content.legion!,
      quality: {
        title: 'Fake quality',
        stats: [{ value: '12', label: 'fake cases' }],
        checks: [{ title: 'Fake check', body: 'Checked, in fake words.' }],
      },
    }
    engine.demo.content = content
    renderApp('/try', engine, 'family')
    const legion = await screen.findByRole('region', { name: 'How LEGION is built' })
    expect(within(legion).getByRole('heading', { level: 3, name: 'Fake quality' })).toBeInTheDocument()
    expect(within(legion).getByText('12')).toBeInTheDocument()
    expect(within(legion).getByText('fake cases')).toBeInTheDocument()
    expect(within(legion).getByText('Checked, in fake words.')).toBeInTheDocument()
  })

  test('a project section holding the store rep draws its live demo inside it (Kevin, 2026-11-22)', async () => {
    const engine = visitor()
    const content = withProjects()
    content.project_sections![0] = { ...content.project_sections![0], widget: 'store_rep', theme: 'shop', orb_mark: 'larkspur' }
    engine.demo.content = content
    renderApp('/try', engine, 'family')
    const section = await screen.findByRole('region', { name: 'Fake two' })
    expect(within(section).getByRole('img', { name: 'Larkspur & Pine, a made-up shop' })).toBeInTheDocument()
    expect(section).toHaveAttribute('data-orb-mark', 'larkspur')
    // No floating pill anywhere on the page: the section is the way in.
    expect(screen.queryByRole('button', { name: /Try: store rep|Store rep: on/ })).not.toBeInTheDocument()
  })

  test('the strip links each project to its section, and a project with no link stands alone', async () => {
    const engine = visitor()
    engine.demo.content = withProjects()
    renderApp('/try', engine, 'family')
    const strip = await screen.findByRole('region', { name: 'Projects' })
    expect(within(strip).getByRole('link', { name: 'Fake one.' })).toHaveAttribute('href', '#legion')
    expect(within(strip).getByRole('link', { name: 'Fake two.' })).toHaveAttribute('href', '#fake-two')
    expect(within(strip).getByText('Fake three.').closest('a')).toBeNull()
    expect(within(strip).getByText(/In progress, lorem ipsum\./)).toBeInTheDocument()
    expect(within(strip).getAllByRole('link')).toHaveLength(2)
    const nav = screen.getByRole('navigation', { name: 'On this page' })
    expect(within(nav).getByRole('link', { name: 'Projects' })).toHaveAttribute('href', '#projects')
    // The strip sits right under the hero, before the first section.
    expect(follows(screen.getByRole('region', { name: sampleContent().hero.title }), strip)).toBe(true)

    const section = screen.getByRole('region', { name: 'Fake two' })
    expect(section).toHaveAttribute('id', 'fake-two')
    expect(within(section).getByText('A fake dashboard for a fake team.')).toBeInTheDocument()
    expect(within(section).getByText('Fake refresh.')).toBeInTheDocument()
    // No clip yet: the slot says so in words, and draws no player.
    expect(within(section).getByText('Video coming soon.')).toBeInTheDocument()
    expect(section.querySelector('video')).toBeNull()
    // Below LEGION's section.
    expect(follows(screen.getByRole('region', { name: 'How LEGION is built' }), section)).toBe(true)
  })

  test('a project section with a recorded clip plays it, with captions', async () => {
    const engine = visitor()
    engine.demo.content = withProjects({
      video: '/static/demo/fake.mp4',
      poster: '/static/demo/fake.jpg',
      captions: '/static/demo/fake.vtt',
    })
    renderApp('/try', engine, 'family')
    const section = await screen.findByRole('region', { name: 'Fake two' })
    const video = section.querySelector('video')
    expect(video).toHaveAttribute('src', '/static/demo/fake.mp4')
    expect(video).toHaveAttribute('controls')
    expect(video).not.toHaveAttribute('autoplay')
    expect(video?.querySelector('track')).toHaveAttribute('src', '/static/demo/fake.vtt')
  })

  test('a section clip shows its poster, plays inline on a phone, never by itself, captions on', async () => {
    const engine = visitor()
    engine.demo.content = withProjects({
      video: '/static/demo/fake.webm',
      poster: '/static/demo/fake.jpg',
      captions: '/static/demo/fake.vtt',
      note: 'Recorded on fake data.',
    })
    renderApp('/try', engine, 'family')
    const section = await screen.findByRole('region', { name: 'Fake two' })
    const video = section.querySelector('video')!
    expect(video).toHaveAttribute('poster', '/static/demo/fake.jpg')
    expect(video).toHaveAttribute('playsinline')
    expect(video).toHaveAttribute('preload', 'none')
    expect(video).toHaveAttribute('aria-label', 'Fake two')
    expect(video).not.toHaveAttribute('autoplay')
    expect(video).not.toHaveAttribute('muted')
    expect(video.querySelector('track')).toHaveAttribute('default')
    expect(within(section).getByText('Recorded on fake data.')).toBeInTheDocument()
  })

  test('a section with 3D models draws one viewer each, poster first, after its clip slot', async () => {
    const engine = visitor()
    engine.demo.content = withProjects({
      note: 'Video coming soon.',
      models: [
        { title: 'Fake board', model: '/api/demo/media/board.glb', poster: '/api/demo/media/board.png' },
        { title: 'Fake lever', model: '/api/demo/media/lever.glb', poster: '/api/demo/media/lever.png' },
      ],
    })
    renderApp('/try', engine, 'family')
    const section = await screen.findByRole('region', { name: 'Fake two' })
    const viewers = within(section).getAllByRole('group', { name: /^3D model: / })
    expect(viewers).toHaveLength(2)
    expect(within(viewers[0]).getByRole('img', { name: 'Fake board' })).toHaveAttribute('src', '/api/demo/media/board.png')
    expect(follows(within(section).getByText('Video coming soon.'), viewers[0])).toBe(true)
  })

  test('an empty media slot draws nothing', async () => {
    const engine = visitor()
    engine.demo.content = withProjects({})
    renderApp('/try', engine, 'family')
    const section = await screen.findByRole('region', { name: 'Fake two' })
    expect(section.querySelector('video')).toBeNull()
    expect(within(section).queryByText(/coming soon/)).not.toBeInTheDocument()
  })

  test('the LinkedIn shape (no work section) still draws the strip and the project section', async () => {
    const engine = visitor()
    const { work: _work, ...rest } = withProjects()
    engine.demo.content = { ...rest, assets: { report: null, resume: null } }
    renderApp('/try', engine, 'family')
    expect(await screen.findByRole('region', { name: 'Projects' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Fake two' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'What I built at work' })).not.toBeInTheDocument()
  })
})

describe('/try: a per-company ref (Kevin, 2026-11-05)', () => {
  test.each([
    ['acme', 'acme'],
    ['Acme%20Corp', 'acme-corp'],
    ['acme%26co.', 'acme-co'],
    ['%26%26', undefined],
  ])('?ref=%s enters with %s, so a hand-made link never blocks the visit', async (raw, sent) => {
    const engine = createEngine({ signedIn: false })
    setAudioFactory(() => new FakeAudio())
    renderApp(`/try?code=OPEN-SESAME&ref=${raw}`, engine, 'family')
    const orb = await screen.findByRole('button', { name: /^Talk to / })
    await act(async () => {
      fireEvent.click(orb)
      await settle()
    })
    expect(await screen.findByRole('heading', { name: 'Experience' })).toBeInTheDocument()
    expect(engine.demo.enterBodies).toEqual([sent ? { code: 'OPEN-SESAME', ref: sent } : { code: 'OPEN-SESAME' }])
  })
})
