import { describe, expect, it, vi } from 'vitest'

import { CONVERSATION_CLOSED, CascadeClient, type CascadeDeps, type TurnRequest, type TurnStart } from './cascade-client'
import type { AssistantClient } from './client'
import type { RecordEvent, RecordTransport } from './conversation-record'
import { MAX_UTTERANCE_MS, SILENCE_MS, WINDOW_MS } from './endpointer'
import { pcm16ToBase64 } from './pcm'
import { FIRST_EVENT_MS, TURN_MS } from './turn-deadline'
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

function build(answers: TurnStart[] = [], options: { visitor?: boolean } = {}) {
  const audio = new FakeAudio()
  const requests: TurnRequest[] = []
  const signals: AbortSignal[] = []
  const recorded: RecordEvent[] = []
  const store: { value: string | null } = { value: null }
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
    ...(options.visitor
      ? {
          openingTurn: () => '(The visitor just joined the demo.)',
          continueTurn: () => '(The visitor started another conversation on the same visit.)',
          sessionLimit: () => 'Demo sessions last 5 minutes.',
        }
      : {}),
  })
  client.attach()
  return { client, audio, requests, signals, startTurn, recorded, store }
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

  it('answers a typed turn in text, with the opener ahead of a visitor first message', async () => {
    const { client, requests, audio } = build([{ ok: true, events: stream(reply('Nothing is on.', 'Anything else?')) }], {
      visitor: true,
    })
    client.send('What is on today?')
    await settle()
    expect(requests[0].text).toBe('What is on today?')
    expect(requests[0].preface).toBe('(The visitor just joined the demo.)')
    const lines = client.getState().lines
    expect(lines[1]).toMatchObject({ text: 'Nothing is on. Anything else?', via: 'typed', open: false })
    expect(audio.played).toEqual([])
    expect(client.getState().mic).toBe('off')
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
    expect(client.getState().activity).toBe('idle')
    expect(audio.played.at(-1)!.pcm.length).toBe(3600)
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
      expect(client.getState().activity).toBe('idle')
      expect(client.getState().notice?.kind).toBe('engine-unreachable')
    } finally {
      vi.useRealTimers()
    }
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
