import { describe, expect, it, vi } from 'vitest'

import { DEMO_FULL, STILL_BUSY } from './cascade-words'
import { CONVERSATION_CLOSED, CascadeClient, ECHO_TAIL_MS, type CascadeDeps, type TurnRequest, type TurnStart } from './cascade-client'
import type { AssistantClient } from './client'
import type { RecordEvent, RecordTransport } from './conversation-record'
import { MAX_UTTERANCE_MS, SILENCE_MS, WINDOW_MS } from './endpointer'
import { pcm16ToBase64 } from './pcm'
import { FIRST_EVENT_MS, TURN_MS } from './turn-deadline'
import { MAX_UTTERANCE_SAMPLES } from './utterance'
import type { SseEvent } from './sse'
import { FakeAudio, settle } from '../test/fake-live'

const LOUD = new Int16Array(320).fill(3000)
const QUIET = new Int16Array(320)

async function* stream(events: SseEvent[]): AsyncGenerator<SseEvent> {
  for (const event of events) {
    await Promise.resolve()
    yield event
  }
}

function reply(...sentences: string[]): SseEvent[] {
  return [
    { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } },
    ...sentences.map((text) => ({ type: 'sentence', data: { text } })),
    { type: 'done', data: { status: 'done', final_text: sentences.join(' ') } },
  ]
}

function greeting(text = 'Essay still waiting, huh?', kind = 'due'): TurnStart {
  return {
    ok: true,
    events: stream([
      { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } },
      { type: 'greeting', data: { kind, text } },
      { type: 'sentence', data: { text } },
      { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([1, 2])), rate: 24000 } },
      { type: 'done', data: { status: 'greeting' } },
    ]),
  }
}

function build(
  answers: TurnStart[] = [],
  options: { visitor?: boolean; speakTyped?: boolean; onEngineLive?: () => void } = {},
) {
  const audio = new FakeAudio()
  const requests: TurnRequest[] = []
  const signals: AbortSignal[] = []
  const recorded: RecordEvent[] = []
  const store: { value: string | null } = { value: null }
  const cancels: string[] = []
  const record: RecordTransport = {
    upload: async (_id, events) => {
      recorded.push(...events)
      return true
    },
    beacon: () => true,
  }
  const startTurn = vi.fn(async (request: TurnRequest, signal: AbortSignal): Promise<TurnStart> => {
    requests.push(request)
    signals.push(signal)
    return answers.shift() ?? { ok: true, events: stream(reply('Fine.')) }
  })
  const deps: CascadeDeps = {
    startTurn,
    cancelTurn: (id) => cancels.push(id),
    audio: () => audio,
    now: () => Date.now(),
    utcOffsetMinutes: () => -300,
    record,
    greetingStore: { load: () => store.value, save: (v) => (store.value = v) },
  }
  const client = new CascadeClient(deps, {
    companionName: 'Alfred',
    sessionMinutes: null,
    endOnHidden: () => true,
    ...(options.speakTyped ? { speakTyped: true } : {}),
    ...(options.onEngineLive ? { onEngineLive: options.onEngineLive } : {}),
    ...(options.visitor
      ? {
          openingTurn: () => '(The visitor just joined the demo.)',
          continueTurn: () => '(The visitor started another conversation on the same visit.)',
          sessionLimit: () => 'Demo sessions last 5 minutes.',
        }
      : {}),
  })
  client.attach()
  return { client, audio, requests, signals, startTurn, recorded, store, cancels }
}

function feed(audio: FakeAudio, chunk: Int16Array, ms: number) {
  for (let t = 0; t < ms; t += 20) audio.handlers?.onChunk(chunk)
}

/** The reply has played to its end. */
function drained(audio: FakeAudio) {
  audio.handlers?.onPlaying(false)
}

/** Open a voice conversation: the tap, the greeting, its playback ending. */
async function open(client: CascadeClient, audio: FakeAudio) {
  await client.startVoice()
  await settle()
  drained(audio)
  await settle()
  // The greeting's tail in the room, which the page does not listen to.
  feed(audio, QUIET, ECHO_TAIL_MS)
}

describe('CascadeClient', () => {
  it('is an AssistantClient', () => {
    const { client } = build()
    const typed: AssistantClient = client
    expect(typed.getState().companion).toBe('Alfred')
  })

  it('greets out loud in the opening tap, then listens', async () => {
    const { client, audio, requests, store } = build([greeting()])
    const tapped = client.startVoice()
    // The greeting request is already on its way inside the tap.
    expect(requests).toHaveLength(1)
    expect(requests[0].greet).toBe(true)
    expect(requests[0].greetingHistory).toEqual({ today: [], last: null, hellos: [] })
    await tapped
    await settle()
    expect(audio.played).toHaveLength(1)
    expect(client.getState().mic).toBe('off')
    drained(audio)
    expect(client.getState().mic).toBe('on')
    expect(client.getState().activity).toBe('listening')
    // The lead kind is remembered for the next greeting's caps, never the facts.
    expect(JSON.parse(store.value ?? '{}').history).toEqual({ today: ['due'], last: 'due', hellos: [] })
  })

  it('hears nothing of its own voice while it speaks (half-duplex)', async () => {
    const { client, audio, requests } = build([greeting()])
    await client.startVoice()
    await settle()
    feed(audio, LOUD, 2000)
    feed(audio, QUIET, SILENCE_MS + 100)
    expect(requests).toHaveLength(1)
  })

  it('sends an utterance after the silence window and listens again after the answer', async () => {
    const spoken: SseEvent[] = [
      { type: 'turn', data: { conversation_id: 'c-1', turn: 1 } },
      { type: 'sentence', data: { text: 'Added.' } },
      { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([4])), rate: 24000 } },
      { type: 'done', data: { status: 'done' } },
    ]
    const { client, audio, requests } = build([greeting(), { ok: true, events: stream(spoken) }])
    await open(client, audio)
    feed(audio, QUIET, 400)
    feed(audio, LOUD, 400)
    feed(audio, QUIET, SILENCE_MS - 40)
    expect(requests).toHaveLength(1)
    feed(audio, QUIET, 60)
    await settle()
    expect(requests).toHaveLength(2)
    // Pre-roll only: the leading quiet is not all sent.
    expect(requests[1].audio!.length).toBeLessThan(16 * (400 + 400 + SILENCE_MS))
    expect(client.getState().mic).toBe('off')
    drained(audio)
    expect(client.getState().mic).toBe('on')
  })

  it('closes with the tone and the note when the follow-up window lapses', async () => {
    const { client, audio } = build([greeting()])
    await open(client, audio)
    const before = audio.played.length
    feed(audio, QUIET, WINDOW_MS + 40)
    expect(client.getState().mic).toBe('off')
    expect(audio.played.length).toBe(before + 1)
    expect(audio.played.at(-1)!.pcm.length).toBe(3600)
    expect(client.getState().lines.at(-1)).toMatchObject({ kind: 'sys', text: CONVERSATION_CLOSED })
  })

  it('a late sound runs the window again', async () => {
    const { client, audio } = build([greeting()])
    await open(client, audio)
    feed(audio, QUIET, WINDOW_MS - 600)
    audio.handlers?.onChunk(LOUD)
    feed(audio, QUIET, 700)
    expect(client.getState().mic).toBe('on')
  })

  it('a tap while listening closes the conversation', async () => {
    const { client, audio } = build([greeting()])
    await open(client, audio)
    client.stopVoice()
    expect(client.getState().mic).toBe('off')
    expect(client.getState().lines.at(-1)).toMatchObject({ kind: 'sys', text: CONVERSATION_CLOSED })
  })

  it('end_conversation closes after the sign-off has played', async () => {
    const { client, audio } = build([
      greeting(),
      {
        ok: true,
        events: stream([
          { type: 'tool', data: { name: 'end_conversation', ok: true, outcome: 'ok', text: 'x' } },
          { type: 'sentence', data: { text: 'Very well.' } },
          { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([3])), rate: 24000 } },
          { type: 'done', data: { status: 'done', end: true } },
        ]),
      },
    ])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    expect(client.getState().lines.some((l) => l.kind === 'tool')).toBe(false)
    expect(client.getState().lines.at(-1)?.kind).toBe('ai')
    drained(audio)
    expect(client.getState().lines.at(-1)).toMatchObject({ kind: 'sys', text: CONVERSATION_CLOSED })
  })

  it('a tap while it speaks interrupts, aborts the turn and listens again', async () => {
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    async function* slow(): AsyncGenerator<SseEvent> {
      yield { type: 'sentence', data: { text: 'A long answer.' } }
      yield { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([1])), rate: 24000 } }
      await held
      yield { type: 'sentence', data: { text: 'Never shown.' } }
    }
    const { client, audio, signals } = build([greeting(), { ok: true, events: slow() }])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    client.interrupt()
    expect(signals[1].aborted).toBe(true)
    expect(client.getState().mic).toBe('on')
    release()
    await settle()
    const ai = client.getState().lines.filter((l) => l.kind === 'ai')
    expect(ai.at(-1)).toMatchObject({ text: 'A long answer.', cut: true })
  })

  it.each([
    ['a tap', 'tap'],
    ['typing', 'typed'],
  ] as const)('the record says %s cut the reply, not a barge-in', async (_how, reason) => {
    async function* slow(): AsyncGenerator<SseEvent> {
      yield { type: 'sentence', data: { text: 'A long answer.' } }
      yield { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([1])), rate: 24000 } }
      await new Promise(() => {})
    }
    const { client, audio, recorded } = build([greeting(), { ok: true, events: slow() }])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    if (reason === 'tap') client.interrupt()
    else client.send('Something else.')
    await settle()
    client.reset()
    await settle()
    const cuts = recorded.filter((e) => e.kind === 'interrupted')
    expect(cuts).toEqual([{ kind: 'interrupted', reason, at_ms: expect.any(Number) }])
  })

  it('nothing heard is a caption and listening resumes', async () => {
    const { client, audio } = build([
      greeting(),
      {
        ok: true,
        events: stream([
          { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } },
          { type: 'heard', data: { text: '' } },
          { type: 'done', data: { status: 'nothing_heard', words: 'Nothing heard, so nothing was asked.' } },
        ]),
      },
    ])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    expect(client.getState().lines.at(-1)).toMatchObject({ kind: 'sys' })
    expect(client.getState().mic).toBe('on')
  })

  it('stops a long utterance at the cap and sends it', async () => {
    const { client, audio, requests } = build([greeting()])
    await open(client, audio)
    feed(audio, LOUD, MAX_UTTERANCE_MS + 40)
    await settle()
    expect(requests).toHaveLength(2)
  })

  it('an utterance at the cap is sent within the engine cap, its start (the pre-roll) dropped', async () => {
    const { client, audio, requests } = build([greeting()])
    await open(client, audio)
    feed(audio, QUIET, 400)
    feed(audio, LOUD, MAX_UTTERANCE_MS + 40)
    await settle()
    const sent = requests[1].audio!
    expect(sent.length).toBe(MAX_UTTERANCE_SAMPLES)
    // The quiet lead-in went; the speech, to its last chunk, is all there.
    expect(sent[0]).toBe(3000)
    expect(sent[sent.length - 1]).toBe(3000)
  })

  it('answers a typed turn in text, with the opener ahead of a visitor first message', async () => {
    const { client, requests, audio } = build([{ ok: true, events: stream(reply('Nothing is on.', 'Anything else?')) }], {
      visitor: true,
    })
    client.send('What is on today?')
    await settle()
    expect(requests[0].text).toBe('What is on today?')
    expect(requests[0].spoken).toBeUndefined()
    expect(requests[0].preface).toBe('(The visitor just joined the demo.)')
    const lines = client.getState().lines
    expect(lines[1]).toMatchObject({ text: 'Nothing is on. Anything else?', via: 'typed', open: false })
    expect(audio.played).toEqual([])
    expect(client.getState().mic).toBe('off')
  })

  it('a turn typed into an open voice conversation is answered aloud, and listening carries on', async () => {
    const said: SseEvent[] = [
      { type: 'turn', data: { conversation_id: 'c-1', turn: 1 } },
      { type: 'heard', data: { text: 'What is on today?' } },
      { type: 'sentence', data: { text: 'Nothing is on.' } },
      { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([5])), rate: 24000 } },
      { type: 'done', data: { status: 'done' } },
    ]
    const { client, audio, requests } = build([greeting(), { ok: true, events: stream(said) }])
    await open(client, audio)
    client.send('What is on today?')
    await settle()
    expect(requests[1]).toMatchObject({ text: 'What is on today?', spoken: true })
    expect(audio.played).toHaveLength(2)
    // The typed line once: the engine's `heard` of it draws no second one.
    const mine = client.getState().lines.filter((l) => l.kind === 'me')
    expect(mine).toEqual([expect.objectContaining({ text: 'What is on today?', via: 'typed' })])
    drained(audio)
    expect(client.getState().mic).toBe('on')
    expect(client.getState().activity).toBe('listening')
  })

  it('on the voice stage (speakTyped) a typed turn is answered aloud with no conversation open', async () => {
    const said: SseEvent[] = [
      { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } },
      { type: 'sentence', data: { text: 'He builds things.' } },
      { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([5])), rate: 24000 } },
      { type: 'done', data: { status: 'done' } },
    ]
    const { client, audio, requests } = build([{ ok: true, events: stream(said) }], { speakTyped: true })
    client.send('Who is he?')
    await settle()
    expect(requests[0]).toMatchObject({ text: 'Who is he?', spoken: true })
    expect(audio.played).toHaveLength(1)
    // The microphone was never opened by typing.
    expect(audio.micOpen).toBe(false)
  })

  it("a visitor's later conversation greets with the continue line", async () => {
    const { client, audio, requests } = build([greeting('Hi.', 'scripted')], { visitor: true })
    await open(client, audio)
    client.stopVoice()
    await client.startVoice()
    await settle()
    expect(requests[0].preface).toBeUndefined()
    expect(requests[1].greet).toBe(true)
    expect(requests[1].preface).toContain('another conversation')
  })

  it('a failed tool says Did not run in the engine words', async () => {
    const { client } = build([
      {
        ok: true,
        events: stream([
          { type: 'tool', data: { name: 'remember', ok: false, outcome: 'refused', text: 'Not on this visit.' } },
          { type: 'done', data: { status: 'done' } },
        ]),
      },
    ])
    client.send('Remember this.')
    await settle()
    expect(client.getState().lines[1]).toMatchObject({ kind: 'tool', ok: false, text: 'Did not run: Not on this visit.' })
  })

  it('a time-limit refusal ends the conversation in the page words', async () => {
    const { client } = build(
      [
        { ok: true, events: stream(reply('Hi.')) },
        { ok: false, status: 409, detail: 'Nothing was answered. This demo conversation reached its time limit.' },
      ],
      { visitor: true },
    )
    client.send('Hi.')
    await settle()
    client.send('Still there?')
    await settle()
    expect(client.getState().phase).toBe('ended')
    expect(client.getState().ended).toBe('Demo sessions last 5 minutes.')
  })

  it('a budget refusal shows the engine words', async () => {
    const { client } = build([{ ok: false, status: 429, detail: "Today's demo budget is used up." }])
    client.send('Hi.')
    await settle()
    expect(client.getState().notice).toEqual({ kind: 'throttled', text: "Today's demo budget is used up." })
  })

  it.each([
    [409, 'Refused here.'],
    [429, 'Refused here.'],
    [503, 'Refused here.'],
    [401, 'You are signed out, so nothing was asked. Sign in again.'],
    [403, 'You are signed out, so nothing was asked. Sign in again.'],
  ])('a %i refusal settles: mic let go, the tone, the words on screen, and a tap starts again', async (status, words) => {
    const { client, audio, requests } = build([{ ok: false, status, detail: 'Refused here.' }])
    await client.startVoice()
    await settle()
    expect(audio.micOpen).toBe(false)
    expect(client.getState().mic).toBe('off')
    expect(audio.played.at(-1)!.pcm.length).toBe(3600)
    // Once the closing tone has played, nothing is going on.
    drained(audio)
    expect(client.getState().activity).toBe('idle')
    expect(client.getState().notice?.text).toBe(words)
    await client.startVoice()
    await settle()
    expect(requests).toHaveLength(2)
    expect(requests[1].greet).toBe(true)
    expect(audio.micOpen).toBe(true)
  })

  it('a refusal mid-conversation lets the mic go too', async () => {
    const { client, audio } = build([greeting(), { ok: false, status: 429, detail: "Today's demo budget is used up." }])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    expect(audio.micOpen).toBe(false)
    expect(client.getState().notice?.text).toBe("Today's demo budget is used up.")
  })

  it.each([
    ['no first event', [] as SseEvent[], FIRST_EVENT_MS],
    ['a stream that stops', [{ type: 'turn', data: { conversation_id: 'c-1', turn: 1 } }] as SseEvent[], TURN_MS],
  ])('a stalled turn (%s) is stopped: aborted, the words on screen, the mic let go', async (_name, before, ms) => {
    async function* stalled(): AsyncGenerator<SseEvent> {
      yield* before
      await new Promise(() => {})
    }
    const { client, audio, signals } = build([greeting(), { ok: true, events: stalled() }])
    await open(client, audio)
    vi.useFakeTimers()
    try {
      feed(audio, LOUD, 300)
      feed(audio, QUIET, SILENCE_MS + 20)
      await vi.advanceTimersByTimeAsync(ms - 100)
      expect(client.getState().activity).toBe('thinking')
      expect(signals[1].aborted).toBe(false)
      await vi.advanceTimersByTimeAsync(200)
      expect(signals[1].aborted).toBe(true)
      expect(audio.micOpen).toBe(false)
      expect(audio.played.at(-1)!.pcm.length).toBe(3600)
      drained(audio)
      expect(client.getState().activity).toBe('idle')
      expect(client.getState().notice?.kind).toBe('engine-unreachable')
    } finally {
      vi.useRealTimers()
    }
  })

  it('a stalled tapped question with nothing open is stopped too, not left thinking', async () => {
    async function* silent(): AsyncGenerator<SseEvent> {
      await new Promise(() => {})
    }
    const { client, signals } = build([{ ok: true, events: silent() }])
    vi.useFakeTimers()
    try {
      client.ask('Tell me about Kevin.')
      await vi.advanceTimersByTimeAsync(FIRST_EVENT_MS + 100)
      expect(signals[0].aborted).toBe(true)
      expect(client.getState().activity).not.toBe('thinking')
      expect(client.getState().notice?.kind).toBe('engine-unreachable')
    } finally {
      vi.useRealTimers()
    }
  })

  const busy = (retryMs?: number): TurnStart => ({
    ok: false,
    status: 409,
    detail: 'A turn is still running.',
    code: 'busy',
    ...(retryMs !== undefined ? { retryMs } : {}),
  })

  it('busy, then answered: the same turn is posted again after retry_ms, still thinking meanwhile', async () => {
    const { client, requests } = build([busy(100), { ok: true, events: stream(reply('Fine.')) }])
    vi.useFakeTimers()
    try {
      client.send('Hi.')
      await vi.advanceTimersByTimeAsync(50)
      expect(requests).toHaveLength(1)
      expect(client.getState().activity).toBe('thinking')
      await vi.advanceTimersByTimeAsync(100)
      expect(requests).toHaveLength(2)
      expect(requests[1]).toBe(requests[0])
      expect(client.getState().lines.at(-1)).toMatchObject({ kind: 'ai', text: 'Fine.' })
      expect(client.getState().phase).not.toBe('ended')
    } finally {
      vi.useRealTimers()
    }
  })

  it('busy every time: gives up within the tries, says so, lets the mic go, and keeps the conversation', async () => {
    const { client, audio, requests } = build([greeting(), busy(), busy(), busy(), busy(), busy(), busy()])
    await open(client, audio)
    vi.useFakeTimers()
    try {
      feed(audio, LOUD, 300)
      feed(audio, QUIET, SILENCE_MS + 20)
      await vi.advanceTimersByTimeAsync(7_000)
    } finally {
      vi.useRealTimers()
    }
    expect(requests).toHaveLength(1 + 5)
    expect(client.getState().phase).not.toBe('ended')
    expect(client.getState().ended).toBeNull()
    expect(client.getState().lines.at(-1)).toMatchObject({ kind: 'sys', text: STILL_BUSY })
    expect(audio.micOpen).toBe(false)
    expect(client.getState().activity).toBe('idle')
    // The next tap listens in the same conversation, without a new greeting.
    await client.startVoice()
    await settle()
    expect(requests).toHaveLength(6)
    expect(client.getState().mic).toBe('on')
  })

  it('a 409 coded time_limit still ends the conversation as the time limit', async () => {
    const { client } = build(
      [
        { ok: true, events: stream(reply('Hi.')) },
        { ok: false, status: 409, detail: 'Over.', code: 'time_limit' },
      ],
      { visitor: true },
    )
    client.send('Hi.')
    await settle()
    client.send('Still there?')
    await settle()
    expect(client.getState().phase).toBe('ended')
    expect(client.getState().ended).toBe('Demo sessions last 5 minutes.')
  })

  const lost: TurnStart = { ok: false, status: 404, detail: 'No conversation with that id is yours.', code: 'no_conversation' }

  it('a conversation the engine lost: the same words go again as a new conversation, once', async () => {
    const { client, audio, requests } = build([greeting(), lost, { ok: true, events: stream(reply('Fine.')) }])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    expect(requests).toHaveLength(3)
    expect(requests[1].conversationId).toBe('c-1')
    expect(requests[2].conversationId).toBeNull()
    expect(requests[2].audio).toBe(requests[1].audio)
    expect(client.getState().lines.at(-1)).toMatchObject({ kind: 'ai', text: 'Fine.' })
    drained(audio)
    expect(client.getState().mic).toBe('on')
  })

  it('lost twice: closes with words instead of looping', async () => {
    const { client, audio, requests } = build([greeting(), lost, lost])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    expect(requests).toHaveLength(3)
    expect(audio.micOpen).toBe(false)
    expect(client.getState().notice?.text).toBe('No conversation with that id is yours.')
  })

  const utter = async (audio: FakeAudio) => {
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
  }

  it('duplicate: already answered, so no words, no retry, and the conversation listens again', async () => {
    const { client, audio, requests } = build([greeting(), { ok: false, status: 409, detail: 'Already answered.', code: 'duplicate' }])
    await open(client, audio)
    const lines = client.getState().lines.length
    await utter(audio)
    expect(requests).toHaveLength(2)
    expect(client.getState().lines).toHaveLength(lines)
    expect(client.getState().notice).toBeNull()
    expect(client.getState().mic).toBe('on')
  })

  it('live_engine: the page is told to ask the engine again, the mic goes, and nothing greets into it', async () => {
    const onEngineLive = vi.fn()
    const { client, audio, requests } = build([greeting(), { ok: false, status: 409, detail: 'Live here.', code: 'live_engine' }], {
      onEngineLive,
    })
    await open(client, audio)
    await utter(audio)
    expect(onEngineLive).toHaveBeenCalledTimes(1)
    expect(audio.micOpen).toBe(false)
    expect(requests).toHaveLength(2)
  })

  const full: TurnStart = { ok: false, status: 429, detail: 'The demo is full.', code: 'demo_full' }

  it('demo_full: waits and posts again 1.5 s apart, still thinking, and is answered', async () => {
    const { client, audio, requests } = build([greeting(), full, { ok: true, events: stream(reply('Fine.')) }])
    await open(client, audio)
    vi.useFakeTimers()
    try {
      feed(audio, LOUD, 300)
      feed(audio, QUIET, SILENCE_MS + 20)
      await vi.advanceTimersByTimeAsync(1_000)
      expect(requests).toHaveLength(2)
      expect(client.getState().activity).toBe('thinking')
      await vi.advanceTimersByTimeAsync(600)
      expect(requests).toHaveLength(3)
      expect(client.getState().lines.at(-1)).toMatchObject({ kind: 'ai', text: 'Fine.' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('demo_full three times: says so, lets the mic go, keeps the conversation (no close)', async () => {
    const { client, audio, requests } = build([greeting(), full, full, full, full])
    await open(client, audio)
    vi.useFakeTimers()
    try {
      feed(audio, LOUD, 300)
      feed(audio, QUIET, SILENCE_MS + 20)
      await vi.advanceTimersByTimeAsync(5_000)
    } finally {
      vi.useRealTimers()
    }
    expect(requests).toHaveLength(1 + 3)
    expect(client.getState().lines.at(-1)).toMatchObject({ kind: 'sys', text: DEMO_FULL })
    expect(client.getState().lines.some((l) => l.kind === 'sys' && l.text === CONVERSATION_CLOSED)).toBe(false)
    expect(audio.micOpen).toBe(false)
    await client.startVoice()
    await settle()
    expect(requests).toHaveLength(4)
    expect(client.getState().mic).toBe('on')
  })

  it('a tap during the closing tone starts a new conversation at once', async () => {
    const { client, audio, requests } = build([greeting(), greeting()])
    await open(client, audio)
    feed(audio, QUIET, WINDOW_MS + 40)
    expect(client.getState().activity).toBe('speaking')
    client.interrupt()
    await settle()
    expect(requests).toHaveLength(2)
    expect(requests[1].greet).toBe(true)
    expect(audio.micOpen).toBe(true)
  })

  it('an interrupt tells the engine once, with the aborted turn id', async () => {
    async function* slow(): AsyncGenerator<SseEvent> {
      yield { type: 'sentence', data: { text: 'A long answer.' } }
      yield { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([1])), rate: 24000 } }
      await new Promise(() => {})
    }
    const { client, audio, requests, cancels } = build([greeting(), { ok: true, events: slow() }])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    client.interrupt()
    client.interrupt()
    expect(cancels).toEqual([requests[1].turnId])
    expect(requests[1].turnId).not.toBe(requests[0].turnId)
  })

  it('a turn that has finished is never cancelled', async () => {
    const { client, audio, cancels } = build([greeting()])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    drained(audio)
    client.stopVoice()
    client.dispose()
    expect(cancels).toEqual([])
  })

  it('a disposed client tells the engine its turn is abandoned, and asks nothing else', async () => {
    async function* stalled(): AsyncGenerator<SseEvent> {
      await new Promise(() => {})
    }
    const { client, audio, requests, cancels } = build([greeting(), { ok: true, events: stalled() }])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    client.dispose()
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    expect(cancels).toEqual([requests[1].turnId])
    expect(requests).toHaveLength(2)
  })

  it('speech_stopped: the rest is on screen, the words keep coming, and the turn ends as usual', async () => {
    const said: SseEvent[] = [
      { type: 'turn', data: { conversation_id: 'c-1', turn: 1 } },
      { type: 'sentence', data: { text: 'First part.' } },
      { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([5])), rate: 24000 } },
      { type: 'speech_stopped', data: { reason: 'speech_budget' } },
      { type: 'sentence', data: { text: 'Second part.' } },
      { type: 'done', data: { status: 'done' } },
    ]
    const { client, audio } = build([greeting(), { ok: true, events: stream(said) }])
    await open(client, audio)
    feed(audio, LOUD, 300)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    const lines = client.getState().lines
    expect(lines.filter((l) => l.kind === 'sys').map((l) => l.text)).toContain('The rest is on screen.')
    expect(lines.filter((l) => l.kind === 'ai').at(-1)).toMatchObject({ text: 'First part. Second part.' })
    drained(audio)
    expect(client.getState().mic).toBe('on')
  })

  it('sends the browser record only what the engine cannot know', async () => {
    const { client, audio, recorded } = build([greeting()])
    await open(client, audio)
    feed(audio, LOUD, 200)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    await settle()
    const kinds = recorded.map((e) => e.kind)
    expect(kinds).toContain('mic_open')
    expect(kinds).not.toContain('user')
    expect(kinds).not.toContain('assistant')
  })
})
