import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { setAudioFactory } from '@/assistant/deps'
import { createEngine, type EngineOptions } from '@/test/engine'
import { NO_DEMO_HOUSEHOLD, SAMPLE_SVG, TOO_MANY, WRONG_CODE, sampleContent } from '@/test/engine-demo'
import { FakeAudio, FakeLiveSocket, settle } from '@/test/fake-live'
import { renderApp } from '@/test/render-app'
import { OPENING_TURN } from '@/screens/try'

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
    [503, NO_DEMO_HOUSEHOLD],
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
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
    expect(screen.queryByRole('heading', { name: 'Talk to my assistant' })).not.toBeInTheDocument()
    expect(screen.queryByRole('log')).not.toBeInTheDocument()
  })

  test('typing the code on /try opens the page in place', async () => {
    const engine = createEngine({ signedIn: false })
    renderApp('/try?ref=acme', engine, 'family')
    fireEvent.change(await screen.findByLabelText('Access code'), { target: { value: 'OPEN-SESAME' } })
    fireEvent.click(screen.getByRole('button', { name: 'Open the demo' }))
    expect(await screen.findByRole('heading', { name: 'Talk to my assistant' })).toBeInTheDocument()
    expect(engine.demo.enterBodies).toEqual([{ code: 'OPEN-SESAME', ref: 'acme' }])
  })

  test('a direct link enters once, and the code leaves the address bar; ref stays', async () => {
    const engine = createEngine({ signedIn: false })
    const { router } = renderApp('/try?code=OPEN-SESAME&ref=acme', engine, 'family')
    expect(await screen.findByRole('heading', { name: 'Talk to my assistant' })).toBeInTheDocument()
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

    expect(screen.getByRole('link', { name: 'Download resume (PDF)' })).toHaveAttribute('href', '/static/demo/resume.pdf')
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
    expect(screen.queryByRole('link', { name: 'Download resume (PDF)' })).not.toBeInTheDocument()
  })

  test('the assistant is the member app chat; a voice start sends the hidden opening turn', async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    const engine = visitor()
    renderApp('/try', engine, 'family')

    // The companion read is refused for a visit (ADR 0068), so the chat says
    // "Assistant" until the first mint names it, exactly as the member app does.
    const chat = await screen.findByRole('log', { name: 'Conversation with Assistant' })
    expect(chat).toBeInTheDocument()
    expect(screen.getByRole('button', { name: "What's Kevin's experience with Power BI?" })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Close\. This clears/ })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Talk to Assistant' }))
    const socket = await connect()
    expect(engine.assistant.minted).toBe(1)
    expect(socket.sentOf('clientContent')).toEqual([
      { clientContent: { turns: [{ role: 'user', parts: [{ text: OPENING_TURN }] }], turnComplete: true } },
    ])
    expect(screen.queryByText(OPENING_TURN)).not.toBeInTheDocument()
    // Named by the mint now.
    expect(await screen.findByRole('log', { name: 'Conversation with Dorothy' })).toBeInTheDocument()
  })

  test('a spent demo budget is shown in the engine words', async () => {
    const engine = visitor({ assistant: { mint: { status: 429, body: { detail: BUDGET } } } })
    renderApp('/try', engine, 'family')
    const box = await screen.findByRole('textbox', { name: 'Message Assistant' })
    fireEvent.change(box, { target: { value: 'hello' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(BUDGET)
    expect(alert).not.toHaveTextContent('in a little while')
  })

  test('a page with no words yet says so', async () => {
    const engine = visitor()
    engine.demo.content = null
    renderApp('/try', engine, 'family')
    expect(await screen.findByRole('heading', { name: "This page isn't set up yet" })).toBeInTheDocument()
  })

  test('a signed-in member sees a preview, and the assistant is still theirs', async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    const engine = createEngine({ assistant: { companionName: 'Mabel' } })
    renderApp('/try', engine, 'family')
    expect(await screen.findByText(/Preview\. This is what a visitor/)).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 1, name: sampleContent().hero.title })).toBeInTheDocument()
    expect(screen.queryByText(/Your demo access lasts/)).not.toBeInTheDocument()

    await screen.findByRole('log', { name: 'Conversation with Mabel' })
    fireEvent.click(screen.getByRole('button', { name: 'Talk to Mabel' }))
    const socket = await connect()
    expect(socket.sentOf('clientContent')).toEqual([])
  })

  test('a recruiter who wanders into the app lands back on /try, not on a broken page', async () => {
    const { router } = renderApp('/', visitor(), 'family')
    expect(await screen.findByRole('heading', { name: 'Talk to my assistant' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/try')
  })

  test('someone with no session who opens the app still goes to sign in', async () => {
    const { router } = renderApp('/', createEngine({ signedIn: false }), 'family')
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })
})
