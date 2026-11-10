import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { RecordEvent, RecordTransport } from './conversation-record'
import { recordForm, recordTransport } from './deps'
import { ENDED_CLOSED, LiveClient, isVoiced, type LiveDeps, type SessionResult, type ToolResult } from './live-client'
import { FakeAudio, FakeLiveSocket, settle } from '../test/fake-live'

/**
 * The browser's half of the conversation record (Kevin, 2026-11-03: conversations "may be
 * recorded to improve" the assistant): what it sends, when, and the beacon when the page goes.
 */

const SESSION: SessionResult = {
  ok: true,
  session: {
    token: 'auth_tokens/t',
    model: 'gemini-3.8-live',
    wsUrl: 'wss://live.example.test/ws',
    connectBy: Date.now() + 60_000,
    expiresAt: Date.now() + 30 * 60_000,
    companionName: 'Dorothy',
    inputAudioMime: 'audio/pcm;rate=16000',
    outputAudioRate: 24000,
    conversationId: 'conv-1',
  },
}

class FakeTransport implements RecordTransport {
  uploads: { id: string; events: RecordEvent[] }[] = []
  beacons: { id: string; events: RecordEvent[] }[] = []
  ok = true
  async upload(id: string, events: RecordEvent[]) {
    this.uploads.push({ id, events })
    return this.ok
  }
  beacon(id: string, events: RecordEvent[]) {
    this.beacons.push({ id, events })
    return true
  }
}

function build(options: { sessionLimit?: string } = {}) {
  let clock = 1_000_000
  const transport = new FakeTransport()
  const runTool = vi.fn(
    async (name: string): Promise<ToolResult> => ({
      success: true,
      text: `ran ${name}`,
      forward: { success: true, message: `ran ${name}` },
    }),
  )
  const audio = new FakeAudio()
  const deps: LiveDeps = {
    startSession: async () => SESSION,
    runTool,
    openSocket: (url) => new FakeLiveSocket(url),
    audio: () => audio,
    now: () => clock,
    utcOffsetMinutes: () => -300,
    record: transport,
  }
  const client = new LiveClient(deps, {
    endOnHidden: () => false,
    sessionLimit: options.sessionLimit ? () => options.sessionLimit ?? null : undefined,
  })
  client.attach()
  return { client, transport, runTool, audio, tick: (ms: number) => (clock += ms) }
}

async function live(client: LiveClient, tick: (ms: number) => number, text = 'What lists do we have?') {
  client.send(text)
  await settle()
  tick(400)
  FakeLiveSocket.last.open()
  FakeLiveSocket.last.serve({ setupComplete: {} })
  await settle()
  return FakeLiveSocket.last
}

beforeEach(() => FakeLiveSocket.reset())
afterEach(() => vi.unstubAllGlobals())

async function liveVoice(client: LiveClient, tick: (ms: number) => number) {
  await client.startVoice()
  await settle()
  tick(400)
  FakeLiveSocket.last.open()
  FakeLiveSocket.last.serve({ setupComplete: {} })
  await settle()
  return FakeLiveSocket.last
}

const SPEECH = new Int16Array(320).fill(3000)
const QUIET = new Int16Array(320).fill(20)
const DEMO_ENDED = 'Demo sessions last 5 minutes. Start another any time.'

describe('reply latency and how a demo conversation ends', () => {
  it('tells speech from a quiet room', () => {
    expect(isVoiced(SPEECH)).toBe(true)
    expect(isVoiced(QUIET)).toBe(false)
    expect(isVoiced(new Int16Array(0))).toBe(false)
  })

  it('times a spoken reply from the end of speech, not from when the transcription arrives', async () => {
    const { client, transport, audio, tick } = build()
    const socket = await liveVoice(client, tick)
    audio.handlers?.onChunk(SPEECH)
    tick(200)
    audio.handlers?.onChunk(SPEECH) // the last word
    tick(100)
    audio.handlers?.onChunk(QUIET)
    tick(900)
    // The transcription arrives at the end of the turn, a few ms before the reply.
    socket.serve({ serverContent: { inputTranscription: { text: 'What is on today?' } } })
    await settle()
    tick(3)
    socket.serve({ serverContent: { outputTranscription: { text: 'Two things.' } } })
    socket.serve({ serverContent: { turnComplete: true } })
    await settle()
    const replies = transport.uploads.flatMap((u) => u.events).filter((e) => e.kind === 'latency' && e.metric === 'reply')
    expect(replies).toEqual([{ kind: 'latency', metric: 'reply', ms: 1003, at_ms: expect.any(Number) }])
  })

  it('starts the clock at the first reply audio, even before its words', async () => {
    const { client, transport, audio, tick } = build()
    const socket = await liveVoice(client, tick)
    audio.handlers?.onChunk(SPEECH)
    tick(600)
    socket.serve({ serverContent: { modelTurn: { parts: [{ inlineData: { mimeType: 'audio/pcm;rate=24000', data: 'AAAA' } }] } } })
    await settle()
    tick(500)
    socket.serve({ serverContent: { outputTranscription: { text: 'Yes.' }, turnComplete: true } })
    await settle()
    const replies = transport.uploads.flatMap((u) => u.events).filter((e) => e.kind === 'latency' && e.metric === 'reply')
    expect(replies.map((e) => (e.kind === 'latency' ? e.ms : null))).toEqual([600])
  })

  it('records no reply time when no speech was heard (a greeting), rather than a made-up one', async () => {
    const { client, transport, audio, tick } = build()
    const socket = await liveVoice(client, tick)
    audio.handlers?.onChunk(QUIET)
    tick(50)
    socket.serve({ serverContent: { outputTranscription: { text: 'Good afternoon.' }, turnComplete: true } })
    await settle()
    const kinds = transport.uploads.flatMap((u) => u.events).map((e) => (e.kind === 'latency' ? e.metric : e.kind))
    expect(kinds).not.toContain('reply')
  })

  it("a demo conversation closed at token expiry ends in the demo's words, not the server's", async () => {
    const { client, transport, tick } = build({ sessionLimit: DEMO_ENDED })
    const socket = await live(client, tick)
    socket.drop(1008, 'auth token has expired')
    await settle()
    expect(client.getState().ended).toBe(DEMO_ENDED)
    const all = JSON.stringify([...transport.uploads, ...transport.beacons])
    expect(all).not.toContain('auth token has expired')
    expect(all).toContain(DEMO_ENDED)
  })

  it('so does a demo conversation the server warns is going away', async () => {
    const { client, tick } = build({ sessionLimit: DEMO_ENDED })
    const socket = await live(client, tick)
    socket.serve({ goAway: { timeLeft: '1s' } })
    await settle()
    expect(client.getState().ended).toBe(DEMO_ENDED)
  })

  it("a member's conversation keeps the server's reason", async () => {
    const { client, tick } = build()
    const socket = await live(client, tick)
    socket.drop(1011, 'internal error')
    await settle()
    expect(client.getState().ended).toBe('The conversation closed: internal error. Start a new one.')
    expect(ENDED_CLOSED).toBe('The conversation closed. Start a new one.')
  })
})

describe('the conversation record', () => {
  it('sends the words, the turn end and the timings after each turn, to the minted conversation', async () => {
    const { client, transport, tick } = build()
    const socket = await live(client, tick)
    tick(700)
    socket.serve({ serverContent: { outputTranscription: { text: 'You have ' } } })
    socket.serve({ serverContent: { outputTranscription: { text: 'a Groceries list.' } } })
    socket.serve({ serverContent: { turnComplete: true } })
    await settle()

    expect(transport.uploads).toHaveLength(1)
    const { id, events } = transport.uploads[0]
    expect(id).toBe('conv-1')
    expect(events).toEqual([
      { kind: 'user', text: 'What lists do we have?', typed: true, at_ms: 1_000_000 },
      { kind: 'latency', metric: 'cold_start', ms: 400, at_ms: 1_000_400 },
      { kind: 'latency', metric: 'reply', ms: 1100, at_ms: 1_001_100 },
      { kind: 'assistant', text: 'You have a Groceries list.', at_ms: 1_001_100 },
      { kind: 'turn_complete', at_ms: 1_001_100 },
    ])
    // Nothing but words and timings: no audio field anywhere.
    expect(JSON.stringify(events)).not.toMatch(/audio|inlineData|pcm/i)
  })

  it('sends only what is new, and resends what a failed upload kept', async () => {
    const { client, transport, tick } = build()
    const socket = await live(client, tick)
    transport.ok = false
    socket.serve({ serverContent: { outputTranscription: { text: 'One.' }, turnComplete: true } })
    await settle()
    transport.ok = true
    socket.serve({ serverContent: { outputTranscription: { text: 'Two.' }, turnComplete: true } })
    await settle()
    expect(transport.uploads).toHaveLength(2)
    expect(transport.uploads[1].events.map((e) => e.kind)).toEqual([
      'user', 'latency', 'latency', 'assistant', 'turn_complete', 'assistant', 'turn_complete',
    ])  // prettier-ignore
    socket.serve({ serverContent: { outputTranscription: { text: 'Three.' }, turnComplete: true } })
    await settle()
    expect(transport.uploads[2].events.map((e) => e.kind)).toEqual(['assistant', 'turn_complete'])
  })

  it('passes the conversation and the call id with every tool call', async () => {
    const { client, runTool, tick } = build()
    const socket = await live(client, tick)
    socket.serve({ toolCall: { functionCalls: [{ id: 'fc-9', name: 'list_checklists', args: {} }] } })
    await settle()
    expect(runTool).toHaveBeenCalledWith('list_checklists', {}, { conversationId: 'conv-1', callId: 'fc-9', turn: 0 })
  })

  it('counts the model turns, so the engine knows which turn a tool call is in', async () => {
    const { client, runTool, tick } = build()
    const socket = await live(client, tick)
    socket.serve({ toolCall: { functionCalls: [{ id: 'fc-1', name: 'web_search', args: {} }] } })
    await settle()
    socket.serve({ serverContent: { outputTranscription: { text: 'It fails at 60,000.' }, turnComplete: true } })
    socket.serve({ toolCall: { functionCalls: [{ id: 'fc-2', name: 'remember', args: { text: 'x' } }] } })
    await settle()
    const calls = runTool.mock.calls as unknown as [string, unknown, { turn?: number }][]
    const turns = calls.map(([name, , context]) => [name, context.turn])
    expect(turns).toEqual([['web_search', 0], ['remember', 1]])
  })

  it('records a cancelled tool call, an interruption and the end', async () => {
    const { client, transport, tick } = build()
    const socket = await live(client, tick)
    socket.serve({ toolCall: { functionCalls: [{ id: 'fc-1', name: 'list_events', args: {} }] } })
    socket.serve({ toolCallCancellation: { ids: ['fc-1'] } })
    socket.serve({ serverContent: { outputTranscription: { text: 'Let me' } } })
    socket.serve({ serverContent: { interrupted: true } })
    socket.serve({ goAway: {} })
    await settle()
    const sent = transport.uploads.flatMap((u) => u.events)
    expect(sent).toContainEqual({ kind: 'tool_cancelled', id: 'fc-1', name: 'list_events', at_ms: expect.any(Number) })
    expect(sent).toContainEqual({ kind: 'assistant', text: 'Let me', at_ms: expect.any(Number) })
    expect(sent).toContainEqual({ kind: 'interrupted', reason: 'server', at_ms: expect.any(Number) })
    expect(sent.at(-1)).toMatchObject({ kind: 'session_end', reason: expect.stringContaining('time limit') })
  })

  it('hands what is not yet stored to a beacon when the page is hidden away', async () => {
    const { client, transport, tick } = build()
    const socket = await live(client, tick)
    transport.ok = false
    socket.serve({ serverContent: { outputTranscription: { text: 'Hello.' }, turnComplete: true } })
    await settle()
    window.dispatchEvent(new Event('pagehide'))
    expect(transport.beacons).toHaveLength(1)
    expect(transport.beacons[0].id).toBe('conv-1')
    expect(transport.beacons[0].events.map((e) => e.kind)).toContain('assistant')
    // A second pagehide has nothing left to send.
    window.dispatchEvent(new Event('pagehide'))
    expect(transport.beacons).toHaveLength(1)
  })

  it('keeps nothing when the mint never answered with a conversation', async () => {
    const transport = new FakeTransport()
    const client = new LiveClient(
      {
        startSession: async () => ({ ok: false, notice: { kind: 'no-key', text: 'No key.' } }),
        runTool: vi.fn(),
        openSocket: (url) => new FakeLiveSocket(url),
        audio: () => new FakeAudio(),
        now: () => 1,
        utcOffsetMinutes: () => 0,
        record: transport,
      },
      { endOnHidden: () => false },
    )
    client.attach()
    client.send('hi')
    await settle()
    window.dispatchEvent(new Event('pagehide'))
    expect(transport.uploads).toEqual([])
    expect(transport.beacons).toEqual([])
  })
})

describe('the real transport', () => {
  it('builds the form with the events as JSON, and the CSRF token only for a beacon', () => {
    document.cookie = 'csrftoken=tok-123'
    const events: RecordEvent[] = [{ kind: 'turn_complete', at_ms: 5 }]
    const plain = recordForm('conv-1', events, false)
    expect(plain.get('conversation_id')).toBe('conv-1')
    expect(JSON.parse(String(plain.get('events')))).toEqual(events)
    expect(plain.get('csrfmiddlewaretoken')).toBeNull()
    expect(recordForm('conv-1', events, true).get('csrfmiddlewaretoken')).toBe('tok-123')
  })

  it('sends the beacon to the conversation door with the token in the form', () => {
    document.cookie = 'csrftoken=tok-456'
    const sendBeacon = vi.fn(() => true)
    vi.stubGlobal('navigator', { ...navigator, sendBeacon })
    expect(recordTransport.beacon('conv-2', [{ kind: 'session_end', reason: 'The page closed.', at_ms: 9 }])).toBe(
      true,
    )
    expect(sendBeacon).toHaveBeenCalledTimes(1)
    const [url, form] = sendBeacon.mock.calls[0] as unknown as [string, FormData]
    expect(url).toBe('/api/assistant/conversation')
    expect(form.get('conversation_id')).toBe('conv-2')
    expect(form.get('csrfmiddlewaretoken')).toBe('tok-456')
  })

  it('uploads through the generated client as multipart', async () => {
    const seen: Request[] = []
    vi.stubGlobal('fetch', async (request: Request) => {
      seen.push(request)
      return new Response(JSON.stringify({ stored_lines: 1, detail: 'Stored.' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    expect(await recordTransport.upload('conv-3', [{ kind: 'turn_complete', at_ms: 1 }])).toBe(true)
    expect(seen[0].url).toMatch(/\/api\/assistant\/conversation$/)
    expect(seen[0].headers.get('Content-Type')).toMatch(/^multipart\/form-data/)
    const form = await seen[0].formData()
    expect(form.get('conversation_id')).toBe('conv-3')
  })
})
