import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { AudioError } from '@/assistant/audio'
import { setAudioFactory } from '@/assistant/deps'
import { TOOL_FAILED } from '@/components/assistant/chat'
import { createEngine, seedHousehold, type EngineOptions } from '@/test/engine'
import { FakeAudio, FakeLiveSocket, settle } from '@/test/fake-live'
import { SPEND_CARD } from '@/test/cards-seed'
import { renderApp } from '@/test/render-app'
import type { Surface } from '@/lib/surface'

/**
 * The assistant, whole: the real app against the fake engine (mint and tool
 * door) and a scripted Google socket (web-assistant 08). Nothing here calls
 * Google, and nothing mocks a hook or a component.
 */

beforeEach(() => {
  FakeLiveSocket.reset()
  vi.stubGlobal('WebSocket', FakeLiveSocket)
})

afterEach(() => {
  setAudioFactory(null)
  vi.unstubAllGlobals()
})

function engineWith(options: EngineOptions = {}) {
  return createEngine({ ...seedHousehold(), ...options })
}

async function openFamilyChat(name = 'Dorothy') {
  fireEvent.click(await screen.findByRole('button', { name: `Ask ${name}` }))
  return screen.findByRole('dialog', { name: `Conversation with ${name}` })
}

function typeAndSend(box: HTMLElement, text: string) {
  fireEvent.change(box, { target: { value: text } })
  fireEvent.click(screen.getByRole('button', { name: 'Send' }))
}

async function connect() {
  await waitFor(() => expect(FakeLiveSocket.instances).toHaveLength(1))
  FakeLiveSocket.last.open()
  FakeLiveSocket.last.serve({ setupComplete: {} })
  await act(settle)
  return FakeLiveSocket.last
}

describe('the family surface', () => {
  test('the orb opens a sheet with the message box focused, named for the engine companion', async () => {
    renderApp('/', engineWith({ assistant: { companionName: 'Mabel' } }), 'family')
    const sheet = await openFamilyChat('Mabel')
    const box = within(sheet).getByRole('textbox', { name: 'Message Mabel' })
    expect(document.activeElement).toBe(box)
    expect(within(sheet).getByText(/clears it from the screen/)).toBeInTheDocument()
    expect(
      within(sheet).getByText('Conversations with Mabel may be recorded to improve the assistant.'),
    ).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Talk to Mabel' })).toBeInTheDocument()
  })

  test('a typed turn mints, connects with the token, runs a tool through the engine, and shows the reply as text', async () => {
    const engine = engineWith({
      assistant: { tools: { search_purchases: { text: 'Shampoo: 3 matches, last bought Sep 28.' } } },
    })
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'When did we last buy shampoo?')

    const socket = await connect()
    expect(engine.assistant.minted).toBe(1)
    expect(engine.assistant.mintBodies[0]).toEqual({
      utc_offset_minutes: expect.any(Number),
      client_now_ms: expect.any(Number),
    })
    expect(socket.url).toContain('?access_token=auth_tokens%2Ffake-token-for-tests')
    expect(socket.sent[0]).toEqual({ setup: { model: 'models/gemini-3.8-live' } })
    expect(socket.sentOf('clientContent')).toHaveLength(1)

    socket.serve({ toolCall: { functionCalls: [{ id: 't1', name: 'search_purchases', args: { query: 'shampoo' } }] } })
    await waitFor(() => expect(engine.assistant.toolCalls).toEqual([{ name: 'search_purchases', args: { query: 'shampoo' } }]))
    await waitFor(() => expect(socket.sentOf('toolResponse')).toHaveLength(1))
    // The tool's name, its arguments and the engine's text for the model are never on screen.
    expect(within(sheet).queryByText(/search_purchases|shampoo"|Shampoo: 3 matches|Done:/)).not.toBeInTheDocument()
    // The tool call names the conversation, so the engine writes it into the record.
    expect(engine.assistant.toolRecordIds[0]).toEqual({
      conversation_id: '00000000-0000-4000-8000-00000000c0de',
      call_id: expect.any(String),
    })
    expect(socket.sentOf('toolResponse')).toHaveLength(1)

    socket.serve({ serverContent: { outputTranscription: { text: 'Three, the last on Sep 28.' }, turnComplete: true } })
    expect(await within(sheet).findByText('Three, the last on Sep 28.')).toBeInTheDocument()
    expect(within(sheet).getByText('When did we last buy shampoo?')).toBeInTheDocument()
  })

  test('a tool result in JSON never reaches the screen, and the conversation shows no code', async () => {
    const json = '{"rows": [{"merchant": "Costco", "amount": "84.12"}], "count": 1}'
    const engine = engineWith({ assistant: { tools: { query_rows: { text: json } } } })
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'Groceries last month?')
    const socket = await connect()
    socket.serve({ toolCall: { functionCalls: [{ id: 'q1', name: 'query_rows', args: { table: 'transactions' } }] } })
    await waitFor(() => expect(socket.sentOf('toolResponse')).toHaveLength(1))
    socket.serve({ serverContent: { outputTranscription: { text: 'One trip, $84 at Costco.' }, turnComplete: true } })
    await within(sheet).findByText('One trip, $84 at Costco.')
    const log = within(sheet).getByRole('log')
    expect(log.textContent).not.toMatch(/query_rows|transactions|\{|"rows"|Done:/)
    expect(within(log).queryByText(TOOL_FAILED)).not.toBeInTheDocument()
    // The member's own side holds only what they typed.
    const mine = within(log).getByText('Groceries last month?')
    expect(mine.className).toContain('self-end')
  })

  test("a tool's card is drawn where it ran, and only `response` goes back to Gemini", async () => {
    const engine = engineWith({
      assistant: { tools: { monthly_spend: { text: 'Categorised spend for 2026-10 is 2516.81 USD.', card: SPEND_CARD } } },
    })
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'What did we spend last month?')
    const socket = await connect()
    socket.serve({ toolCall: { functionCalls: [{ id: 's1', name: 'monthly_spend', args: { month: '2026-10' } }] } })
    const log = within(sheet).getByRole('log')
    const card = await within(log).findByRole('region', { name: 'Spending by category' })
    expect(card).toHaveTextContent('Groceries$412.36unverified')
    await waitFor(() => expect(socket.sentOf('toolResponse')).toHaveLength(1))
    expect(socket.sentOf('toolResponse')[0]).toEqual({
      toolResponse: {
        functionResponses: [
          {
            id: 's1',
            name: 'monthly_spend',
            response: { success: true, message: 'Categorised spend for 2026-10 is 2516.81 USD.' },
          },
        ],
      },
    })
    expect(log.textContent).not.toMatch(/monthly_spend|2026-10"|Categorised spend|Done:/)
  })

  test('a refused tool shows one plain sentence: no tool name, no engine words', async () => {
    const engine = engineWith()
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'Play some music')
    const socket = await connect()
    socket.serve({ toolCall: { functionCalls: [{ id: 'm1', name: 'play_music', args: {} }] } })
    // The engine's refusal names the tool; the screen says one plain sentence instead.
    expect(await within(sheet).findByText(TOOL_FAILED)).toBeInTheDocument()
    expect(within(sheet).queryByText(/play_music|Did not run|Nothing was read|Done:/)).not.toBeInTheDocument()
  })

  test('a server with no key says so in words and the message is marked not delivered', async () => {
    const engine = engineWith({
      assistant: { mint: { status: 503, body: { detail: "The assistant isn't set up on this server." } } },
    })
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'hello')
    const alert = await within(sheet).findByRole('alert')
    expect(alert).toHaveTextContent("The assistant isn't set up on this server. Nothing was started.")
    expect(within(sheet).getByText('Your message was not delivered. Nothing was sent.')).toBeInTheDocument()
    expect(FakeLiveSocket.instances).toHaveLength(0)
  })

  test('a throttled start says so in the engine words, and nothing is added to them', async () => {
    const detail = 'Nothing was read, written or started. Too many conversations started; try again in a minute.'
    const engine = engineWith({ assistant: { mint: { status: 429, body: { detail } } } })
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'hello')
    const alert = await within(sheet).findByRole('alert')
    expect(alert).toHaveTextContent(detail)
    expect(alert).not.toHaveTextContent('in a little while')
  })

  test('a throttled start with no words from the engine still says what to do', async () => {
    const engine = engineWith({ assistant: { mint: { status: 429, body: {} } } })
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'hello')
    expect(await within(sheet).findByRole('alert')).toHaveTextContent(
      'Too many conversations were started. Try again in a little while.',
    )
  })

  test('an unreachable engine says nothing was started', async () => {
    const engine = engineWith()
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    engine.down = true
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'hello')
    expect(await within(sheet).findByRole('alert')).toHaveTextContent("Can't reach the engine, so nothing was started.")
  })

  test('a blocked microphone is a sentence and typing still works', async () => {
    const audio = new FakeAudio()
    audio.failWith = new AudioError('denied', 'blocked')
    setAudioFactory(() => audio)
    const engine = engineWith()
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Talk to Dorothy' }))
    const alert = await within(sheet).findByRole('alert')
    expect(alert).toHaveTextContent('The microphone is blocked for this app')
    expect(alert).toHaveTextContent('Typing still works')
    expect(engine.assistant.minted).toBe(0)
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'still here')
    await connect()
    expect(engine.assistant.minted).toBe(1)
  })

  test('voice: the mic tap opens a session, shows listening, and the minimised orb keeps the status', async () => {
    const audio = new FakeAudio()
    setAudioFactory(() => audio)
    renderApp('/', engineWith(), 'family')
    const sheet = await openFamilyChat()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Talk to Dorothy' }))
    const socket = await connect()
    expect(await within(sheet).findByText('Listening')).toBeInTheDocument()
    audio.chunk()
    expect(socket.sentOf('realtimeInput')).toHaveLength(1)

    socket.serve({ serverContent: { inputTranscription: { text: 'what is on today' } } })
    // Once as the transcript line, once as the live caption under the status.
    expect(await within(sheet).findAllByText('what is on today')).toHaveLength(2)

    fireEvent.click(within(sheet).getByRole('button', { name: /Minimise/ }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(await screen.findByText('Listening. Tap to see.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Open the conversation with Dorothy' }))
    const again = await screen.findByRole('dialog', { name: 'Conversation with Dorothy' })
    expect(within(again).getAllByText('what is on today').length).toBeGreaterThan(0)
  })

  test('Google ending the session says it ended and offers a new conversation, which clears the lines', async () => {
    const engine = engineWith()
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'hi there')
    const socket = await connect()
    socket.serve({ goAway: { timeLeft: '30s' } })
    expect(await within(sheet).findByText('The conversation reached its time limit. Start a new one.')).toBeInTheDocument()
    expect(within(sheet).getByRole('textbox', { name: 'Message Dorothy' })).toBeDisabled()
    expect(within(sheet).getByText('hi there')).toBeInTheDocument()

    const fresh = within(sheet).getAllByRole('button', { name: 'New conversation' })
    fireEvent.click(fresh[fresh.length - 1])
    expect(within(sheet).queryByText('hi there')).not.toBeInTheDocument()
    expect(within(sheet).getByRole('textbox', { name: 'Message Dorothy' })).toBeEnabled()
  })

  test('a screen lock ends the conversation in the words the ruling gives', async () => {
    renderApp('/', engineWith(), 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'hi')
    await connect()
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
    expect(await within(sheet).findByText('Conversation ended when the screen locked.')).toBeInTheDocument()
  })

  test('closing clears the conversation; reopening is blank', async () => {
    renderApp('/', engineWith(), 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'secret plans')
    await connect()
    fireEvent.click(within(sheet).getByRole('button', { name: /Close\. This clears/ }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const reopened = await openFamilyChat()
    expect(within(reopened).queryByText('secret plans')).not.toBeInTheDocument()
    expect(window.localStorage.length).toBe(0)
  })
})

describe('the workbench', () => {
  const desk: Surface = 'workbench'

  test('the dock is open on load beside the page; the rail closes and reopens it', async () => {
    renderApp('/', engineWith({ assistant: { companionName: 'Mabel' } }), desk)
    // Kevin, 2026-11-03: one place to type, and on the desk it is the dock, open by default.
    const panel = await screen.findByRole('complementary', { name: 'Conversation with Mabel' })
    expect(within(panel).getByRole('textbox', { name: 'Message Mabel' })).toBeInTheDocument()
    // Opening on load does not grab focus (a focused field would hold back a deploy's reload).
    expect(document.activeElement).toBe(document.body)
    // The page itself is still there.
    expect(screen.getByRole('navigation', { name: 'Sections' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()

    const entry = screen.getByRole('button', { name: 'Mabel' })
    fireEvent.click(entry)
    expect(screen.queryByRole('complementary', { name: 'Conversation with Mabel' })).not.toBeInTheDocument()
    fireEvent.click(entry)
    const reopened = await screen.findByRole('complementary', { name: 'Conversation with Mabel' })
    const box = within(reopened).getByRole('textbox', { name: 'Message Mabel' })
    await waitFor(() => expect(document.activeElement).toBe(box))
  })

  test('"/" opens the panel and focuses the box, but does not steal a slash typed in a field', async () => {
    renderApp('/', engineWith(), desk)
    await screen.findByRole('button', { name: 'Dorothy' })
    fireEvent.keyDown(document.body, { key: '/' })
    const panel = await screen.findByRole('complementary', { name: 'Conversation with Dorothy' })
    const box = within(panel).getByRole('textbox', { name: 'Message Dorothy' })
    await waitFor(() => expect(document.activeElement).toBe(box))
    fireEvent.change(box, { target: { value: 'a' } })
    fireEvent.keyDown(box, { key: '/' })
    expect(box).toHaveValue('a')
  })

  test('a desk conversation survives the tab being hidden', async () => {
    renderApp('/', engineWith(), desk)
    const panel = await screen.findByRole('complementary', { name: 'Conversation with Dorothy' })
    typeAndSend(within(panel).getByRole('textbox', { name: 'Message Dorothy' }), 'hi')
    await connect()
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
    expect(within(panel).queryByText(/ended/)).not.toBeInTheDocument()
  })
})
