import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { AudioError } from '@/assistant/audio'
import { setAudioFactory } from '@/assistant/deps'
import { createEngine, seedHousehold, type EngineOptions } from '@/test/engine'
import { FakeAudio, FakeLiveSocket, settle } from '@/test/fake-live'
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
    expect(within(sheet).getByText(/Not saved/)).toBeInTheDocument()
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
    expect(engine.assistant.mintBodies[0]).toEqual({ utc_offset_minutes: expect.any(Number) })
    expect(socket.url).toContain('?access_token=auth_tokens%2Ffake-token-for-tests')
    expect(socket.sent[0]).toEqual({ setup: { model: 'models/gemini-3.8-live' } })
    expect(socket.sentOf('clientContent')).toHaveLength(1)

    socket.serve({ toolCall: { functionCalls: [{ id: 't1', name: 'search_purchases', args: { query: 'shampoo' } }] } })
    expect(await within(sheet).findByText('Done: Shampoo: 3 matches, last bought Sep 28.')).toBeInTheDocument()
    expect(engine.assistant.toolCalls).toEqual([{ name: 'search_purchases', args: { query: 'shampoo' } }])
    expect(socket.sentOf('toolResponse')).toHaveLength(1)

    socket.serve({ serverContent: { outputTranscription: { text: 'Three, the last on Sep 28.' }, turnComplete: true } })
    expect(await within(sheet).findByText('Three, the last on Sep 28.')).toBeInTheDocument()
    expect(within(sheet).getByText('When did we last buy shampoo?')).toBeInTheDocument()
  })

  test('a refused tool says "Did not run" in the engine words, and Done appears nowhere', async () => {
    const engine = engineWith()
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'Play some music')
    const socket = await connect()
    socket.serve({ toolCall: { functionCalls: [{ id: 'm1', name: 'play_music', args: {} }] } })
    const refusal = 'Nothing was read or written. play_music is not a tool the web assistant has.'
    expect(await within(sheet).findByText(`Did not run: ${refusal}`)).toBeInTheDocument()
    expect(within(sheet).queryByText(/Done:/)).not.toBeInTheDocument()
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

  test('a throttled start says to try again later, in the engine words', async () => {
    const engine = engineWith({ assistant: { mint: { status: 429, body: { detail: 'Too many conversations started.' } } } })
    renderApp('/', engine, 'family')
    const sheet = await openFamilyChat()
    typeAndSend(within(sheet).getByRole('textbox', { name: 'Message Dorothy' }), 'hello')
    expect(await within(sheet).findByRole('alert')).toHaveTextContent('Too many conversations started. Try again in a little while.')
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

  test('the rail names the companion, opens a docked panel, and the page stays beside it', async () => {
    renderApp('/', engineWith({ assistant: { companionName: 'Mabel' } }), desk)
    const entry = await screen.findByRole('button', { name: 'Mabel' })
    expect(screen.queryByRole('complementary', { name: 'Conversation with Mabel' })).not.toBeInTheDocument()
    fireEvent.click(entry)
    const panel = await screen.findByRole('complementary', { name: 'Conversation with Mabel' })
    const box = within(panel).getByRole('textbox', { name: 'Message Mabel' })
    await waitFor(() => expect(document.activeElement).toBe(box))
    // The page itself is still there.
    expect(screen.getByRole('navigation', { name: 'Sections' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
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
    fireEvent.click(await screen.findByRole('button', { name: 'Dorothy' }))
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
