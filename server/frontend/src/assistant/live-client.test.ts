import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AudioError } from './audio'
import {
  ENDED_CLOSED,
  ENDED_LOCKED,
  ENDED_TIME_LIMIT,
  LiveClient,
  type LiveDeps,
  type Notice,
  type SessionResult,
  type ToolResult,
} from './live-client'
import { pcm16ToBase64 } from './pcm'
import { FakeAudio, FakeLiveSocket, settle } from '../test/fake-live'

const SESSION: SessionResult = {
  ok: true,
  session: {
    token: 'auth_tokens/abc def',
    model: 'gemini-3.8-live',
    wsUrl: 'wss://live.example.test/ws',
    connectBy: Date.now() + 60_000,
    companionName: 'Dorothy',
    inputAudioMime: 'audio/pcm;rate=16000',
    outputAudioRate: 24000,
  },
}

function build(overrides: Partial<LiveDeps> = {}, endOnHidden = true) {
  const audio = new FakeAudio()
  const startSession = vi.fn(async (_offset: number): Promise<SessionResult> => SESSION)
  const runTool = vi.fn(
    async (name: string, _args: Record<string, unknown>): Promise<ToolResult> => ({
      success: true,
      text: `ran ${name}`,
      forward: { success: true, message: `ran ${name}` },
    }),
  )
  const deps: LiveDeps = {
    startSession,
    runTool,
    openSocket: (url) => new FakeLiveSocket(url),
    audio: () => audio,
    now: () => Date.now(),
    utcOffsetMinutes: () => -300,
    ...overrides,
  }
  const client = new LiveClient(deps, { endOnHidden: () => endOnHidden })
  client.attach()
  return { client, audio, startSession, runTool }
}

/** Get a client to the live phase through a typed first turn. */
async function live(client: LiveClient, text = 'hello') {
  client.send(text)
  await settle()
  FakeLiveSocket.last.open()
  FakeLiveSocket.last.serve({ setupComplete: {} })
  await settle()
  return FakeLiveSocket.last
}

/** A voice conversation brought to the live phase. */
async function liveVoice(client: LiveClient) {
  await client.startVoice()
  await settle()
  const socket = FakeLiveSocket.last
  socket.open()
  socket.serve({ setupComplete: {} })
  await settle()
  return socket
}

function audioPart(samples: number[], rate = 24000) {
  return { inlineData: { mimeType: `audio/pcm;rate=${rate}`, data: pcm16ToBase64(new Int16Array(samples)) } }
}

function text(client: LiveClient) {
  return client.getState().lines.map((line) => `${line.kind}:${'text' in line ? line.text : ''}`)
}

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => (hidden ? 'hidden' : 'visible') })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => FakeLiveSocket.reset())
afterEach(() => setHidden(false))

describe('starting a conversation by typing', () => {
  it('mints on the first send with the local UTC offset, connects with the token, and delivers the queued text', async () => {
    const { client, startSession } = build()
    expect(client.send('When did we last buy shampoo?')).toBe(true)
    expect(client.getState().phase).toBe('connecting')
    await settle()
    expect(startSession).toHaveBeenCalledWith(-300)
    const socket = FakeLiveSocket.last
    expect(socket.url).toBe('wss://live.example.test/ws?access_token=auth_tokens%2Fabc%20def')
    socket.open()
    expect(socket.sent).toEqual([{ setup: { model: 'models/gemini-3.8-live' } }])
    // Nothing else goes out until Google says the setup is done.
    socket.serve({ setupComplete: {} })
    await settle()
    expect(client.getState().phase).toBe('live')
    expect(socket.sent[1]).toEqual({
      clientContent: {
        turns: [{ role: 'user', parts: [{ text: 'When did we last buy shampoo?' }] }],
        turnComplete: true,
      },
    })
    expect(client.getState().companion).toBe('Dorothy')
  })

  it('ignores a blank message and mints nothing', () => {
    const { client, startSession } = build()
    expect(client.send('   ')).toBe(false)
    expect(startSession).not.toHaveBeenCalled()
  })

  it('shows the reply as text, assembled from transcription chunks, and plays none of it', async () => {
    const { client, audio } = build()
    const socket = await live(client, 'hi')
    socket.serve({
      serverContent: { modelTurn: { parts: [audioPart([0, 0, 0, 0])] }, outputTranscription: { text: 'Good ' } },
    })
    socket.serve({ serverContent: { outputTranscription: { text: 'evening.' } } })
    socket.serve({ serverContent: { turnComplete: true } })
    await settle()
    expect(text(client)).toEqual(['me:hi', 'ai:Good evening.'])
    const reply = client.getState().lines[1]
    expect(reply.kind === 'ai' && reply.via).toBe('typed')
    expect(audio.played).toHaveLength(0)
    expect(client.getState().activity).toBe('idle')
  })

  it('reads a reply that arrives as a binary frame, in order', async () => {
    const { client } = build()
    const socket = await live(client, 'hi')
    const frame = (value: unknown) => new TextEncoder().encode(JSON.stringify(value)).buffer
    socket.onmessage?.({ data: frame({ serverContent: { outputTranscription: { text: 'One ' } } }) } as MessageEvent)
    socket.onmessage?.({ data: frame({ serverContent: { outputTranscription: { text: 'two.' } } }) } as MessageEvent)
    await settle()
    expect(text(client)).toEqual(['me:hi', 'ai:One two.'])
  })
})

describe('tool calls', () => {
  const call = { toolCall: { functionCalls: [{ id: 'c1', name: 'search_purchases', args: { query: 'shampoo' } }] } }

  it('runs them through the engine and answers Gemini with the engine response; Done only after success', async () => {
    const { client, runTool } = build()
    const socket = await live(client)
    socket.serve(call)
    await settle()
    expect(runTool).toHaveBeenCalledWith('search_purchases', { query: 'shampoo' })
    expect(text(client)).toContain('tool:Done: ran search_purchases')
    expect(socket.sentOf('toolResponse')[0]).toEqual({
      toolResponse: {
        functionResponses: [
          { id: 'c1', name: 'search_purchases', response: { success: true, message: 'ran search_purchases' } },
        ],
      },
    })
  })

  it('says "Did not run" in the engine words when it refuses, and never "Done"', async () => {
    const refusal = "That's only on Kevin's phone."
    const { client } = build({
      runTool: async () => ({ success: false, text: refusal, forward: { success: false, message: refusal } }),
    })
    const socket = await live(client)
    socket.serve({ toolCall: { functionCalls: [{ id: 'c9', name: 'play_music', args: {} }] } })
    await settle()
    const lines = text(client)
    expect(lines).toContain(`tool:Did not run: ${refusal}`)
    expect(lines.some((line) => line.includes('Done'))).toBe(false)
    const line = client.getState().lines.find((l) => l.kind === 'tool')
    expect(line && line.kind === 'tool' && line.ok).toBe(false)
    expect(socket.sentOf('toolResponse')[0]).toEqual({
      toolResponse: {
        functionResponses: [{ id: 'c9', name: 'play_music', response: { success: false, message: refusal } }],
      },
    })
  })

  it('treats a tool call that throws as unreached: nothing read or written', async () => {
    const { client } = build({
      runTool: async () => {
        throw new Error('network')
      },
    })
    const socket = await live(client)
    socket.serve(call)
    await settle()
    expect(text(client)).toContain('tool:Did not run: Nothing was read or written: the engine could not be reached.')
    expect(socket.sentOf('toolResponse')).toHaveLength(1)
  })

  it('answers several calls in one message with one response', async () => {
    const { client } = build()
    const socket = await live(client)
    socket.serve({
      toolCall: {
        functionCalls: [
          { id: 'a', name: 'one', args: {} },
          { id: 'b', name: 'two' },
        ],
      },
    })
    await settle()
    const responses = socket.sentOf('toolResponse')
    expect(responses).toHaveLength(1)
    const sent = (responses[0].toolResponse as { functionResponses: { id: string }[] }).functionResponses
    expect(sent.map((r) => r.id)).toEqual(['a', 'b'])
  })

  it('still shows what the engine did for a cancelled call, but does not answer it', async () => {
    let release: (value: ToolResult) => void = () => {}
    const { client } = build({ runTool: () => new Promise<ToolResult>((resolve) => (release = resolve)) })
    const socket = await live(client)
    socket.serve(call)
    await settle()
    expect(client.getState().activity).toBe('thinking')
    socket.serve({ toolCallCancellation: { ids: ['c1'] } })
    release({ success: true, text: 'added eggs', forward: { success: true, message: 'added eggs' } })
    await settle()
    expect(text(client)).toContain('tool:Done: added eggs')
    expect(socket.sentOf('toolResponse')).toHaveLength(0)
  })
})

describe('live voice', () => {
  it('a blocked microphone is a sentence, mints nothing, and leaves typing working', async () => {
    const { client, audio, startSession } = build()
    audio.failWith = new AudioError('denied', 'blocked')
    await client.startVoice()
    const notice = client.getState().notice as Notice
    expect(notice.kind).toBe('mic-denied')
    expect(notice.text).toContain('Typing still works')
    expect(client.getState().mic).toBe('off')
    expect(startSession).not.toHaveBeenCalled()
    expect(client.send('hello')).toBe(true)
    expect(startSession).toHaveBeenCalled()
  })

  it('a missing microphone says so', async () => {
    const { client, audio } = build()
    audio.failWith = new AudioError('unavailable', 'none')
    await client.startVoice()
    expect(client.getState().notice?.kind).toBe('mic-unavailable')
  })

  it('asks for the mic before any network, then streams 16 kHz chunks only once live', async () => {
    const { client, audio, startSession } = build()
    audio.hold = true
    const started = client.startVoice()
    // The mic was asked for in the same tick as the tap; nothing was minted yet.
    expect(audio.handlers).not.toBeNull()
    expect(startSession).not.toHaveBeenCalled()
    expect(client.getState().mic).toBe('starting')
    audio.answer()
    await started
    await settle()
    expect(client.getState().mic).toBe('on')
    const socket = FakeLiveSocket.last
    audio.chunk()
    socket.open()
    audio.chunk()
    expect(socket.sentOf('realtimeInput')).toHaveLength(0)
    socket.serve({ setupComplete: {} })
    await settle()
    audio.chunk()
    const sent = socket.sentOf('realtimeInput')
    expect(sent).toHaveLength(1)
    const audioMessage = (sent[0].realtimeInput as { audio: { mimeType: string; data: string } }).audio
    expect(audioMessage.mimeType).toBe('audio/pcm;rate=16000')
    expect(atob(audioMessage.data)).toHaveLength(640)
    expect(client.getState().activity).toBe('listening')
  })

  it('plays spoken replies, tags them, and shows the user words as said', async () => {
    const { client, audio } = build()
    const socket = await liveVoice(client)
    socket.serve({ serverContent: { inputTranscription: { text: 'what is on today' } } })
    socket.serve({
      serverContent: { modelTurn: { parts: [audioPart([1, 2, 3])] }, outputTranscription: { text: 'Two things.' } },
    })
    await settle()
    expect(audio.played).toHaveLength(1)
    expect(audio.played[0].rate).toBe(24000)
    expect(Array.from(audio.played[0].pcm)).toEqual([1, 2, 3])
    expect(client.getState().activity).toBe('speaking')
    const [me, ai] = client.getState().lines
    expect(me.kind === 'me' && me.via).toBe('spoken')
    expect(ai.kind === 'ai' && ai.via).toBe('spoken')
  })

  it('flushes playback on an interruption and marks the cut-off line', async () => {
    const { client, audio } = build()
    const socket = await liveVoice(client)
    socket.serve({
      serverContent: { modelTurn: { parts: [audioPart([0, 0])] }, outputTranscription: { text: 'You logged toothpaste on' } },
    })
    await settle()
    const before = audio.flushes
    socket.serve({ serverContent: { interrupted: true } })
    await settle()
    expect(audio.flushes).toBeGreaterThan(before)
    const ai = client.getState().lines[0]
    expect(ai.kind === 'ai' && ai.cut).toBe(true)
    expect(client.getState().activity).toBe('listening')
  })

  it('typing over speech stops it, says so, and shows the typed reply without playing it', async () => {
    const { client, audio } = build()
    const socket = await liveVoice(client)
    socket.serve({
      serverContent: { modelTurn: { parts: [audioPart([0, 0])] }, outputTranscription: { text: 'Before that,' } },
    })
    await settle()
    client.send('Add eggs to the list')
    // The server interrupts the old generation AFTER the typed turn went out.
    socket.serve({ serverContent: { interrupted: true } })
    socket.serve({
      serverContent: { modelTurn: { parts: [audioPart([0, 0])] }, outputTranscription: { text: 'Added eggs.' } },
    })
    await settle()
    expect(text(client)).toEqual([
      'ai:Before that,',
      'me:Add eggs to the list',
      'sys:Stopped speaking because you typed.',
      'ai:Added eggs.',
    ])
    expect(audio.played).toHaveLength(1)
    expect(socket.sentOf('clientContent')).toHaveLength(1)
  })

  it('stopping voice keeps the conversation for typing', async () => {
    const { client, audio } = build()
    await liveVoice(client)
    client.stopVoice()
    expect(client.getState()).toMatchObject({ mic: 'off', phase: 'live' })
    expect(audio.micOpen).toBe(false)
  })

  it('a microphone that stops by itself is said in words', async () => {
    const { client, audio } = build()
    await liveVoice(client)
    audio.handlers?.onMicLost()
    expect(client.getState().notice?.kind).toBe('mic-lost')
    expect(client.getState().mic).toBe('off')
  })
})

describe('a conversation ends in words', () => {
  it('goAway: the time limit, transcript kept, typing refused', async () => {
    const { client } = build()
    const socket = await live(client, 'hi')
    socket.serve({ goAway: { timeLeft: '50s' } })
    await settle()
    expect(client.getState()).toMatchObject({ phase: 'ended', ended: ENDED_TIME_LIMIT })
    expect(text(client)).toEqual(['me:hi'])
    expect(socket.closed).toBe(true)
    expect(client.send('more')).toBe(false)
  })

  it('a close mid-conversation says it closed (with the reason when Google gave one)', async () => {
    const { client } = build()
    const socket = await live(client)
    socket.drop(1011, 'Deadline expired')
    expect(client.getState().ended).toBe('The conversation closed: Deadline expired. Start a new one.')
    FakeLiveSocket.reset()
    const second = build()
    const other = await live(second.client)
    other.drop(1006, '')
    expect(second.client.getState().ended).toBe(ENDED_CLOSED)
  })

  it('a hidden page ends it on a phone and stops the mic', async () => {
    const { client, audio } = build()
    await liveVoice(client)
    setHidden(true)
    expect(client.getState()).toMatchObject({ phase: 'ended', ended: ENDED_LOCKED, mic: 'off' })
    expect(audio.micOpen).toBe(false)
  })

  it('a hidden page does not end it where the caller says it should not (the desk)', async () => {
    const { client } = build({}, false)
    await live(client)
    setHidden(true)
    expect(client.getState().phase).toBe('live')
  })

  it('a page hidden while the token is still being minted ends it and ignores the late token', async () => {
    let finish: (result: SessionResult) => void = () => {}
    const { client } = build({ startSession: () => new Promise<SessionResult>((resolve) => (finish = resolve)) })
    client.send('hi')
    setHidden(true)
    expect(client.getState().ended).toBe(ENDED_LOCKED)
    finish(SESSION)
    await settle()
    expect(FakeLiveSocket.instances).toHaveLength(0)
  })

  it('New conversation clears the lines and mints a fresh token on the next send', async () => {
    const { client, startSession } = build()
    const socket = await live(client, 'hi')
    socket.serve({ goAway: {} })
    await settle()
    client.reset()
    expect(client.getState()).toMatchObject({ phase: 'idle', lines: [], ended: null, notice: null })
    client.send('again')
    await settle()
    expect(startSession).toHaveBeenCalledTimes(2)
    expect(FakeLiveSocket.instances).toHaveLength(2)
  })

  it('a reset during a mint drops the late token', async () => {
    let finish: (result: SessionResult) => void = () => {}
    const { client } = build({ startSession: () => new Promise<SessionResult>((resolve) => (finish = resolve)) })
    client.send('hi')
    client.reset()
    finish(SESSION)
    await settle()
    expect(FakeLiveSocket.instances).toHaveLength(0)
    expect(client.getState().phase).toBe('idle')
  })
})

describe('failures before the conversation starts', () => {
  const failing = (notice: Notice) => ({ startSession: async (): Promise<SessionResult> => ({ ok: false, notice }) })

  it('a refused mint is shown in the engine words and the typed message is marked not delivered', async () => {
    const notice: Notice = { kind: 'no-key', text: "The assistant isn't set up on this server. Nothing was started." }
    const { client } = build(failing(notice))
    client.send('hello')
    await settle()
    expect(client.getState().notice).toEqual(notice)
    expect(client.getState().phase).toBe('idle')
    expect(text(client)).toEqual(['me:hello', 'sys:Your message was not delivered. Nothing was sent.'])
  })

  it('a mint that throws is "can not reach the engine"', async () => {
    const { client } = build({
      startSession: async () => {
        throw new Error('offline')
      },
    })
    client.send('hello')
    await settle()
    expect(client.getState().notice?.kind).toBe('engine-unreachable')
  })

  it('a socket that closes before setup is a failed connect, not a conversation', async () => {
    const { client } = build()
    client.send('hello')
    await settle()
    FakeLiveSocket.last.drop(1007, 'Missing or malformed auth token')
    expect(client.getState().notice?.kind).toBe('connect-failed')
    expect(client.getState().notice?.text).toContain('Missing or malformed auth token')
    expect(client.getState().phase).toBe('idle')
  })

  it('a socket that never finishes setup times out', async () => {
    vi.useFakeTimers()
    try {
      const { client } = build()
      client.send('hello')
      await vi.advanceTimersByTimeAsync(0)
      FakeLiveSocket.last.open()
      await vi.advanceTimersByTimeAsync(16_000)
      expect(client.getState().notice?.kind).toBe('connect-failed')
    } finally {
      vi.useRealTimers()
    }
  })

  it('a failed start after the mic opened releases the mic', async () => {
    const { client, audio } = build(failing({ kind: 'throttled', text: 'Too many. Nothing was started.' }))
    await client.startVoice()
    await settle()
    expect(audio.micOpen).toBe(false)
    expect(client.getState()).toMatchObject({ mic: 'off', phase: 'idle' })
  })
})
