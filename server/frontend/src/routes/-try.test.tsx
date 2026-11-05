import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { AudioError } from '@/assistant/audio'
import { setAudioFactory } from '@/assistant/deps'
import { createEngine, type EngineOptions } from '@/test/engine'
import { SEED_FAILED, SAMPLE_SVG, TOO_MANY, WRONG_CODE, sampleContent } from '@/test/engine-demo'
import { FakeAudio, FakeLiveSocket, settle } from '@/test/fake-live'
import { DAY_CARD, SPEND_CARD } from '@/test/cards-seed'
import { renderApp } from '@/test/render-app'
import { OPENING_TURN, VISITOR_SESSION_ENDED } from '@/screens/try'

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
    expect(await screen.findByRole('heading', { name: 'What to look for' })).toBeInTheDocument()
    expect(engine.demo.enterBodies).toEqual([{ code: 'OPEN-SESAME', ref: 'acme' }])
  })

  test('a direct link enters once, and the code leaves the address bar; ref stays', async () => {
    const engine = createEngine({ signedIn: false })
    const { router } = renderApp('/try?code=OPEN-SESAME&ref=acme', engine, 'family')
    expect(await screen.findByRole('heading', { name: 'What to look for' })).toBeInTheDocument()
    expect(engine.demo.enterBodies).toEqual([{ code: 'OPEN-SESAME', ref: 'acme' }])
    expect(router.state.location.search).toEqual({ ref: 'acme' })
    expect(router.state.location.href).not.toContain('OPEN-SESAME')
    expect(router.history.location.href).not.toContain('code=')
  })

  test('a direct link with a wrong code strips it too and says why in words', async () => {
    const engine = createEngine({ signedIn: false })
    const { router } = renderApp('/try?code=stale', engine, 'family')
    expect(await screen.findByRole('alert')).toHaveTextContent(WRONG_CODE)
    expect(router.history.location.href).not.toContain('stale')
    expect(screen.getByLabelText('Access code')).toBeInTheDocument()
  })

  test('a visit renders every section of the page from the engine', async () => {
    const engine = visitor()
    const content = sampleContent()
    const { container } = renderApp('/try', engine, 'family')

    expect(await screen.findByRole('heading', { level: 1, name: content.hero.title })).toBeInTheDocument()
    expect(screen.getByText(expiryLine(engine.demo.session!.expires_at))).toBeInTheDocument()
    expect(screen.getByText('Houston, open to relocation')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'What to look for' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'What I built at Quanex' })).toBeInTheDocument()
    expect(screen.getByText('~770')).toBeInTheDocument()

    // The diagram is an image of the SVG, never markup injected into the page.
    const diagram = screen.getByRole('img', { name: content.work.diagram_caption })
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
    await screen.findByRole('heading', { name: 'What to look for' })
    // Kevin, 2026-11-03: "remove the talk to my assistant chat window in the middle".
    expect(screen.queryByRole('region', { name: 'Talk to my assistant' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Talk' })).not.toBeInTheDocument()
    expect(screen.queryByRole('search')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('log')).not.toBeInTheDocument()
    // Exactly one entry point: the bar, never also the members' floating orb.
    const entries = screen.getAllByRole('button', { name: /^(Ask|Talk to|Open the conversation with) / })
    expect(entries).toHaveLength(1)
    expect(entries[0].className).not.toMatch(/fixed/)

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
    await screen.findByRole('heading', { name: 'What to look for' })
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
    expect(dock.parentElement?.firstElementChild).toContainElement(screen.getByRole('heading', { level: 1 }))
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
    await screen.findByRole('heading', { name: 'What to look for' })
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
    await screen.findByRole('heading', { name: 'What to look for' })
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
    await screen.findByRole('heading', { name: 'What to look for' })
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

  test('the visitor page has a clear way into the app (ticket 13)', async () => {
    const { router } = renderApp('/try', visitor(), 'family')
    // Two ways in, both to the app: the top line's nav, and the hero's access note,
    // which says what the visitor's copy of the household is.
    const hero = await screen.findByRole('region', { name: sampleContent().hero.title })
    expect(within(hero).getByText(/deleted when the visit ends/)).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'On this page' })
    expect(within(nav).getByRole('link', { name: 'Open the app' })).toHaveAttribute('href', '/')
    fireEvent.click(within(hero).getByRole('link', { name: 'Open the app' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
    expect(await screen.findByRole('note', { name: 'Demo visit' })).toBeInTheDocument()
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
