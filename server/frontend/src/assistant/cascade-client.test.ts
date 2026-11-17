import { describe, expect, it, vi } from 'vitest'

import { CascadeClient, type CascadeDeps, type TurnRequest, type TurnStart } from './cascade-client'
import type { AssistantClient } from './client'
import type { RecordEvent, RecordTransport } from './conversation-record'
import { MAX_UTTERANCE_MS, NO_SPEECH_MS, SILENCE_MS } from './endpointer'
import { pcm16ToBase64 } from './pcm'
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

function build(answers: TurnStart[] = [], options: { sessionMinutes?: number | null } = {}) {
  const audio = new FakeAudio()
  const requests: TurnRequest[] = []
  const signals: AbortSignal[] = []
  const recorded: RecordEvent[] = []
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
  const deps: CascadeDeps = { startTurn, audio: () => audio, now: () => Date.now(), utcOffsetMinutes: () => -300, record }
  const client = new CascadeClient(deps, {
    companionName: 'Alfred',
    sessionMinutes: options.sessionMinutes ?? null,
    endOnHidden: () => true,
    openingTurn: () => '(The visitor just joined the demo.)',
    continueTurn: () => '(The visitor started another conversation on the same visit.)',
    sessionLimit: () => 'Demo sessions last 5 minutes.',
  })
  client.attach()
  return { client, audio, requests, signals, startTurn, recorded }
}

function feed(audio: FakeAudio, chunk: Int16Array, ms: number) {
  for (let t = 0; t < ms; t += 20) audio.handlers?.onChunk(chunk)
}

describe('CascadeClient', () => {
  it('is an AssistantClient', () => {
    const { client } = build()
    const typed: AssistantClient = client
    expect(typed.getState().companion).toBe('Alfred')
  })

  it('answers a typed turn in text, with the opener ahead of the first turn only', async () => {
    const { client, requests, audio } = build([
      { ok: true, events: stream(reply('Nothing is on.', 'Anything else?')) },
    ])
    expect(client.send('What is on today?')).toBe(true)
    await settle()
    expect(requests[0].text).toBe('What is on today?')
    expect(requests[0].audio).toBeUndefined()
    expect(requests[0].preface).toBe('(The visitor just joined the demo.)')
    const lines = client.getState().lines
    expect(lines.map((l) => l.kind)).toEqual(['me', 'ai'])
    expect(lines[1]).toMatchObject({ text: 'Nothing is on. Anything else?', via: 'typed', open: false })
    expect(audio.played).toEqual([])
    client.send('And tomorrow?')
    await settle()
    expect(requests[1].conversationId).toBe('c-1')
    expect(requests[1].preface).toBeUndefined()
  })

  it('records an utterance, ends it after the silence window, and plays the reply', async () => {
    const pcm = new Int16Array([5, -5, 7])
    const { client, audio, requests } = build([
      {
        ok: true,
        events: stream([
          { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } },
          { type: 'heard', data: { text: 'Add milk.' } },
          { type: 'tool', data: { name: 'add_checklist_item', ok: true, outcome: 'ok', text: 'Added milk.' } },
          { type: 'sentence', data: { text: 'Added.' } },
          { type: 'audio', data: { pcm: pcm16ToBase64(pcm), rate: 24000 } },
          { type: 'done', data: { status: 'done' } },
        ]),
      },
    ])
    await client.startVoice()
    expect(client.getState().mic).toBe('on')
    feed(audio, LOUD, 400)
    feed(audio, QUIET, SILENCE_MS - 40)
    expect(requests).toHaveLength(0)
    feed(audio, QUIET, 60)
    expect(client.getState().mic).toBe('off')
    await settle()
    expect(requests[0].audio?.length).toBeGreaterThan(0)
    const kinds = client.getState().lines.map((l) => l.kind)
    expect(kinds).toEqual(['me', 'tool', 'ai'])
    expect(client.getState().lines[1]).toMatchObject({ ok: true, text: 'Done: Added milk.' })
    expect(Array.from(audio.played[0].pcm)).toEqual([5, -5, 7])
  })

  it('sends nothing when nothing was said', async () => {
    const { client, audio, requests } = build()
    await client.startVoice()
    feed(audio, QUIET, NO_SPEECH_MS + 20)
    await settle()
    expect(requests).toHaveLength(0)
    expect(client.getState().mic).toBe('off')
    expect(client.getState().lines.at(-1)?.kind).toBe('sys')
  })

  it('stops at the longest utterance and sends it', async () => {
    const { client, audio, requests } = build()
    await client.startVoice()
    feed(audio, LOUD, MAX_UTTERANCE_MS + 20)
    await settle()
    expect(requests).toHaveLength(1)
  })

  it('a second tap sends what was said so far', async () => {
    const { client, audio, requests } = build()
    await client.startVoice()
    feed(audio, LOUD, 200)
    client.stopVoice()
    await settle()
    expect(requests).toHaveLength(1)
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

  it('a tap during the reply interrupts it and aborts the turn', async () => {
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    async function* slow(): AsyncGenerator<SseEvent> {
      yield { type: 'sentence', data: { text: 'A long answer.' } }
      await held
      yield { type: 'sentence', data: { text: 'Never shown.' } }
    }
    const { client, audio, signals, recorded } = build([{ ok: true, events: slow() }])
    client.send('Tell me everything.')
    await settle()
    audio.handlers?.onPlaying(true)
    client.interrupt()
    expect(signals[0].aborted).toBe(true)
    expect(audio.flushes).toBeGreaterThan(0)
    release()
    await settle()
    const ai = client.getState().lines.filter((l) => l.kind === 'ai')
    expect(ai).toHaveLength(1)
    expect(ai[0]).toMatchObject({ cut: true, open: false })
    void recorded
  })

  it('a time-limit refusal ends the conversation in the page words', async () => {
    const { client } = build([
      { ok: true, events: stream(reply('Hi.')) },
      { ok: false, status: 409, detail: 'Nothing was answered. This demo conversation reached its time limit.' },
    ])
    client.send('Hi.')
    await settle()
    client.send('Still there?')
    await settle()
    expect(client.getState().phase).toBe('ended')
    expect(client.getState().ended).toBe('Demo sessions last 5 minutes.')
  })

  it('a later conversation on the page carries the continue line', async () => {
    const { client, requests } = build()
    client.send('First question.')
    await settle()
    client.reset()
    client.send('Second.')
    await settle()
    expect(requests[1].conversationId).toBeNull()
    expect(requests[1].preface).toContain('another conversation')
    expect(requests[1].preface).toContain('First question.')
  })

  it('a budget refusal shows the engine words and asks nothing more', async () => {
    const { client } = build([{ ok: false, status: 429, detail: "Today's demo budget is used up." }])
    client.send('Hi.')
    await settle()
    expect(client.getState().notice).toEqual({ kind: 'throttled', text: "Today's demo budget is used up." })
  })

  it('sends the browser record only what the engine cannot know', async () => {
    const { client, audio, recorded } = build()
    await client.startVoice()
    feed(audio, LOUD, 200)
    feed(audio, QUIET, SILENCE_MS + 20)
    await settle()
    await settle()
    const kinds = recorded.map((e) => e.kind)
    expect(kinds).toContain('mic_open')
    expect(kinds).toContain('mic_closed')
    expect(kinds).not.toContain('user')
    expect(kinds).not.toContain('assistant')
  })
})
