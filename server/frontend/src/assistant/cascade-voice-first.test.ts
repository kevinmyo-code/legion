import { describe, expect, it, vi } from 'vitest'

import { CONVERSATION_CLOSED, CascadeClient, ECHO_TAIL_MS, type CascadeDeps, type FunnelEvent, type TurnRequest, type TurnStart } from './cascade-client'
import { EARLY_TRANSCRIPT, createCascadeDeps } from './deps'
import { MAX_UTTERANCE_MS, WINDOW_MS } from './endpointer'
import { MAX_UTTERANCE_SAMPLES } from './utterance'
import { ENTRY_STALLED } from './cascade-words'
import { ENTRY_MS, ENTRY_TAP_MS } from './turn-deadline'
import { HIDDEN_CLOSE_MS } from './backstop'
import { pcm16ToBase64 } from './pcm'
import type { SseEvent } from './sse'
import { BargeInGate } from './turn/barge-in-gate'
import type { TurnDetector, TurnHandlers } from './turn/detector'
import { FakeAudio, settle } from '../test/fake-live'

// Fails fast, with its name, if anything hangs.
vi.setConfig({ testTimeout: 10_000 })

/**
 * The browser's turn-taking wired into the conversation (the phone's barge-in, self-trigger guard,
 * speculation), and the demo's tour, funnel, prepare-in-the-tap and live strip. The detector is a
 * stand-in whose handlers the test calls; `turn/detector.test.ts` covers the detector itself.
 */

async function* stream(events: SseEvent[]): AsyncGenerator<SseEvent> {
  for (const event of events) {
    await Promise.resolve()
    yield event
  }
}

/** A turn still streaming: its audio has started and the engine is still at work. */
async function* hanging(events: SseEvent[]): AsyncGenerator<SseEvent> {
  yield* stream(events)
  await new Promise(() => {})
}

const AUDIO: SseEvent = { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array([1, 2])), rate: 24000 } }

function spoken(text: string, extra: Record<string, unknown> = {}, before: SseEvent[] = []): TurnStart {
  return {
    ok: true,
    events: stream([
      { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } },
      ...before,
      { type: 'sentence', data: { text } },
      AUDIO,
      { type: 'done', data: { status: 'done', ...extra } },
    ]),
  }
}

class StubDetector implements TurnDetector {
  readonly kind = 'models' as const
  readonly smartAvailable = true
  readonly loadMs = 10
  readonly sileroMs = 1
  readonly smartMs = 100
  readonly gate = new BargeInGate()
  handlers: TurnHandlers | null = null
  pushes: { listening: boolean; playing: boolean }[] = []
  replies = { started: 0, ended: 0 }
  resets = 0
  setHandlers(h: TurnHandlers | null) {
    this.handlers = h
  }
  push(_chunk: Int16Array, listening: boolean, playing: boolean) {
    this.pushes.push({ listening, playing })
  }
  replyStarted() {
    this.replies.started++
    // As the real detector: the gate learns each reply (and counts one barge-in per reply).
    this.gate.onReplyStart()
  }
  replyEnded() {
    this.replies.ended++
  }
  reset() {
    this.resets++
  }
  disposed = 0
  dispose() {
    this.disposed++
  }
  fire() {
    this.handlers?.onBargeIn({ type: 'fire', prob: 0.9, level: 20000, echoFloor: 5000, threshold: 7500, sustainMs: 256 })
  }
  /** The gate's verdict on the last firing: its own echo (`self`) or a person. */
  verdict(self: boolean) {
    const nowDisabled = self ? this.gate.countSelfInterrupt() : this.gate.disabled
    this.handlers?.onVerdict({
      type: 'verdict',
      selfInterrupt: self,
      speechMsAfter: self ? 0 : 600,
      fireLevel: 20000,
      fireThreshold: 7500,
      selfInterrupts: this.gate.selfInterrupts,
      nowDisabled,
    })
  }
}

function build(
  answers: TurnStart[],
  options: {
    prepare?: () => Promise<boolean>
    transcript?: string | null
    early?: boolean
    loadModels?: () => Promise<TurnDetector | null>
  } = {},
) {
  const audio = new FakeAudio()
  const requests: TurnRequest[] = []
  const signals: AbortSignal[] = []
  const funnel: FunnelEvent[] = []
  const transcribeSignals: AbortSignal[] = []
  const detector = new StubDetector()
  const transcribe = vi.fn(async (_audio: Int16Array, signal: AbortSignal) => {
    transcribeSignals.push(signal)
    return options.transcript === undefined ? 'How is it built' : options.transcript
  })
  const deps: CascadeDeps = {
    startTurn: vi.fn(async (request: TurnRequest, signal: AbortSignal) => {
      requests.push(request)
      signals.push(signal)
      return answers.shift() ?? spoken('Fine.')
    }),
    audio: () => audio,
    now: () => Date.now(),
    utcOffsetMinutes: () => 0,
    turnDetector: options.loadModels ?? (async () => detector),
    // Early transcripts are off on the page (`deps.ts` EARLY_TRANSCRIPT); the tests of the code
    // behind the switch turn it on here.
    ...(options.early ? { transcribe } : {}),
    funnel: (event) => funnel.push(event),
  }
  const client = new CascadeClient(deps, {
    companionName: 'Alfred',
    sessionMinutes: null,
    endOnHidden: () => false,
    ...(options.prepare ? { prepare: options.prepare } : {}),
  })
  client.attach()
  return { client, audio, requests, signals, funnel, detector, transcribeSignals, transcribe, deps }
}

/** Open: tap, greeting played and drained, listening with the models loaded. */
async function open(t: ReturnType<typeof build>) {
  await t.client.startVoice()
  await settle()
  t.audio.handlers?.onPlaying(false)
  await settle()
  // The greeting's tail in the room, which the page does not listen to.
  for (let ms = 0; ms < ECHO_TAIL_MS; ms += 20) t.audio.handlers?.onChunk(new Int16Array(320))
}

const UTTERANCE = new Int16Array(16000)

describe('the models in the conversation', () => {
  it('loads the models after the first tap and tells the gate when a reply plays', async () => {
    const t = build([spoken('Hello.')])
    await t.client.startVoice()
    await settle()
    expect(t.detector.handlers).not.toBeNull()
    expect(t.client.fullDuplex).toBe(true)
    expect(t.detector.replies.started).toBe(1)
    t.audio.handlers?.onPlaying(false)
    expect(t.detector.replies.ended).toBe(1)
    // The reply's tail in the room reaches the models, not yet as a turn.
    for (let ms = 0; ms < ECHO_TAIL_MS; ms += 20) t.audio.handlers?.onChunk(new Int16Array(320))
    expect(t.detector.pushes.at(-1)).toEqual({ listening: false, playing: false })
    // Every chunk reaches the models; while the reply plays it is marked as playback.
    t.audio.handlers?.onPlaying(true)
    t.audio.handlers?.onChunk(new Int16Array(320))
    expect(t.detector.pushes.at(-1)).toEqual({ listening: true, playing: true })
  })

  it('the models stop with the client: its workers end when the page lets go of it', async () => {
    const t = build([spoken('Hello.')])
    await open(t)
    t.client.dispose()
    expect(t.detector.disposed).toBe(1)
  })

  it('models that finish loading after the page let go are stopped, not attached', async () => {
    let loaded: (d: TurnDetector) => void = () => {}
    const late = new StubDetector()
    const t = build([spoken('Hello.')], { loadModels: () => new Promise((resolve) => (loaded = resolve)) })
    await t.client.startVoice()
    await settle()
    t.client.dispose()
    loaded(late)
    await settle()
    expect(late.disposed).toBe(1)
    expect(late.handlers).toBeNull()
  })

  it('a barge-in pauses the reply; a person (the verdict) cuts it, cancels the turn, and listens', async () => {
    const t = build([spoken('Hello.'), { ok: true, events: hanging([{ type: 'turn', data: { conversation_id: 'c-1', turn: 1 } }, AUDIO]) }])
    await open(t)
    // A turn is in flight and its audio playing.
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.requests).toHaveLength(2)
    const flushes = t.audio.flushes
    t.detector.fire()
    expect(t.audio.paused).toBe(true)
    expect(t.audio.flushes).toBe(flushes)
    expect(t.signals[1].aborted).toBe(false)
    t.detector.verdict(false)
    expect(t.audio.flushes).toBe(flushes + 1)
    expect(t.signals[1].aborted).toBe(true)
    expect(t.client.getState().mic).toBe('on')
    expect(t.client.bargeIns).toBe(1)
    // What they said over it is the next turn.
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.requests).toHaveLength(3)
  })

  it('its own echo (the verdict): nothing is posted and the answer plays on; twice, and it is half-duplex', async () => {
    const t = build([spoken('Hello.'), spoken('A long answer.'), spoken('Another long answer.')])
    await open(t)
    for (let i = 0; i < 2; i++) {
      t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
      await settle()
      t.detector.fire()
      // The echo's "turn" ends before the verdict: held, never sent.
      t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
      await settle()
      t.detector.verdict(true)
      expect(t.audio.paused).toBe(false)
      expect(t.audio.flushes).toBe(0)
      t.audio.handlers?.onPlaying(false)
      await settle()
    }
    expect(t.requests).toHaveLength(3)
    expect(t.client.bargeIns).toBe(0)
    expect(t.client.fullDuplex).toBe(false)
    const said = t.client.getState().lines.filter((l) => l.kind === 'sys').map((l) => l.text)
    expect(said.join(' ')).not.toMatch(/duplex|interrupt/i)
    // Talked over now: nothing happens.
    t.audio.handlers?.onPlaying(true)
    t.detector.fire()
    expect(t.audio.paused).toBe(false)
  })

  it('the reply ends while paused, then its echo verdict: the conversation listens again (not stuck idle)', async () => {
    const t = build([spoken('Hello.'), spoken('A long answer.')])
    await open(t)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    t.detector.fire()
    // The answer runs out while paused (its stream already ended: it re-listens), then the verdict.
    t.audio.handlers?.onPlaying(false)
    t.detector.verdict(true)
    expect(t.client.getState().mic).toBe('on')
    expect(t.client.getState().activity).toBe('listening')
  })

  it('a barge-in turn posts barge_in; the next ordinary turn does not; a busy re-post keeps it', async () => {
    const busy: TurnStart = { ok: false, status: 409, detail: 'busy', code: 'busy', retryMs: 0 }
    const t = build([spoken('Hello.'), { ok: true, events: hanging([{ type: 'turn', data: { conversation_id: 'c-1', turn: 1 } }, AUDIO]) }, busy, spoken('Sure.'), spoken('Fine.')])
    await open(t)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    t.detector.fire()
    t.detector.verdict(false)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    await settle()
    expect(t.requests[2].bargeIn).toBe(true)
    expect(t.requests[3]).toBe(t.requests[2])
    t.audio.handlers?.onPlaying(false)
    await settle()
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.requests[4].bargeIn).toBeUndefined()
    expect(t.requests[1].bargeIn).toBeUndefined()
  })

  it('half-duplex lasts one conversation: the next one has barge-in again', async () => {
    const t = build([spoken('Hello.')])
    await open(t)
    t.detector.gate.disable()
    expect(t.client.fullDuplex).toBe(false)
    t.client.stopVoice()
    await t.client.startVoice()
    expect(t.client.fullDuplex).toBe(true)
    expect(t.detector.gate.selfInterrupts).toBe(0)
  })

  it('a real question after a barge-in is not counted against it', async () => {
    const t = build([
      spoken('Hello.'),
      spoken('A long answer.'),
      { ok: true, events: stream([{ type: 'turn', data: { conversation_id: 'c-1', turn: 2 } }, { type: 'heard', data: { text: 'What about the car?' } }, { type: 'done', data: { status: 'done' } }]) },
    ])
    await open(t)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    t.audio.handlers?.onPlaying(true)
    t.detector.fire()
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    t.detector.verdict(false)
    await settle()
    expect(t.requests).toHaveLength(3)
    expect(t.detector.gate.selfInterrupts).toBe(0)
    expect(t.client.fullDuplex).toBe(true)
  })
})

describe('noise (review 2026-11-17: a fan or a TV kept the conversation open)', () => {
  const NOISE = new Int16Array(320).fill(3000)
  const nothingHeard = (): TurnStart => ({
    ok: true,
    events: stream([
      { type: 'turn', data: { conversation_id: 'c-1', turn: 1 } },
      { type: 'done', data: { status: 'nothing_heard', ignored: 'no_speech' } },
    ]),
  })

  it('noise the models call no speech lets the window lapse on the clock, and nothing is uploaded', async () => {
    const t = build([spoken('Hello.')])
    await open(t)
    for (let ms = 0; ms < MAX_UTTERANCE_MS + 1000; ms += 20) t.audio.handlers?.onChunk(NOISE)
    await settle()
    expect(t.requests).toHaveLength(1)
    expect(t.audio.micOpen).toBe(false)
    expect(t.client.getState().lines.at(-1)).toMatchObject({ kind: 'sys', text: CONVERSATION_CLOSED })
  })

  it('noise still lapses by the third window (late loud runs it again at most twice)', async () => {
    const t = build([spoken('Hello.')])
    await open(t)
    for (let ms = 0; ms < 3 * WINDOW_MS + 100; ms += 20) t.audio.handlers?.onChunk(NOISE)
    expect(t.client.getState().mic).toBe('off')
  })

  it('with the models, an utterance at the cap is sent within the engine cap', async () => {
    const t = build([spoken('Hello.')])
    await open(t)
    for (let ms = 0; ms < 400; ms += 20) t.audio.handlers?.onChunk(new Int16Array(320))
    t.detector.handlers?.onStart()
    for (let ms = 0; ms < MAX_UTTERANCE_MS + 40 && t.requests.length < 2; ms += 20) t.audio.handlers?.onChunk(NOISE)
    await settle()
    expect(t.requests).toHaveLength(2)
    expect(t.requests[1].audio!.length).toBeLessThanOrEqual(MAX_UTTERANCE_SAMPLES)
  })

  it('two turns in a row with no speech in them close the conversation', async () => {
    const t = build([spoken('Hello.'), nothingHeard(), nothingHeard()])
    await open(t)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.client.getState().mic).toBe('on')
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.requests).toHaveLength(3)
    expect(t.audio.micOpen).toBe(false)
    expect(t.client.getState().lines.at(-1)).toMatchObject({ kind: 'sys', text: CONVERSATION_CLOSED })
  })

  it('words between them reset the count', async () => {
    const t = build([spoken('Hello.'), nothingHeard(), spoken('Sure.', {}, [{ type: 'heard', data: { text: 'Hi there' } }]), nothingHeard()])
    await open(t)
    for (let i = 0; i < 3; i++) {
      t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
      await settle()
      t.audio.handlers?.onPlaying(false)
      await settle()
    }
    expect(t.client.getState().mic).toBe('on')
  })
})

describe('wall-clock backstops (review r3: a suspended capture sends no chunks)', () => {
  async function openOnFakeTimers() {
    const t = build([spoken('Hello.')])
    void t.client.startVoice()
    await vi.advanceTimersByTimeAsync(0)
    t.audio.handlers?.onPlaying(false)
    await vi.advanceTimersByTimeAsync(0)
    expect(t.client.getState().mic).toBe('on')
    return t
  }

  it('with no chunks at all, the follow-up window still lapses on the clock and the mic is let go', async () => {
    vi.useFakeTimers()
    try {
      const t = await openOnFakeTimers()
      await vi.advanceTimersByTimeAsync(WINDOW_MS - 1_500)
      expect(t.client.getState().mic).toBe('on')
      await vi.advanceTimersByTimeAsync(2_500)
      expect(t.audio.micOpen).toBe(false)
      expect(t.client.getState().lines.at(-1)).toMatchObject({ kind: 'sys', text: CONVERSATION_CLOSED })
    } finally {
      vi.useRealTimers()
    }
  })

  it('hidden for a few seconds with the conversation open, it closes; back sooner, it carries on', async () => {
    vi.useFakeTimers()
    const setHidden = (hidden: boolean) => {
      Object.defineProperty(document, 'visibilityState', { value: hidden ? 'hidden' : 'visible', configurable: true })
      document.dispatchEvent(new Event('visibilitychange'))
    }
    try {
      const t = await openOnFakeTimers()
      const chunk = new Int16Array(320)
      const tick = async (ms: number) => {
        for (let at = 0; at < ms; at += 500) {
          t.audio.handlers?.onChunk(chunk)
          await vi.advanceTimersByTimeAsync(500)
        }
      }
      setHidden(true)
      await tick(HIDDEN_CLOSE_MS - 1_000)
      setHidden(false)
      await tick(HIDDEN_CLOSE_MS)
      expect(t.client.getState().mic).toBe('on')
      setHidden(true)
      await tick(HIDDEN_CLOSE_MS + 500)
      expect(t.audio.micOpen).toBe(false)
    } finally {
      setHidden(false)
      vi.useRealTimers()
    }
  })
})

describe('early transcripts, off for this ship (deps.ts EARLY_TRANSCRIPT)', () => {
  it("the page's deps carry no early transcript", () => {
    expect(EARLY_TRANSCRIPT).toBe(false)
    expect(createCascadeDeps().transcribe).toBeUndefined()
  })

  it('off: a pause transcribes nothing and the turn goes to the engine once, as audio', async () => {
    const t = build([spoken('Hello.')])
    await open(t)
    t.detector.handlers?.onStart()
    t.detector.handlers?.onEarly(UTTERANCE)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.transcribe).not.toHaveBeenCalled()
    expect(t.requests).toHaveLength(2)
    expect(t.requests[1].audio).toBe(UTTERANCE)
    expect(t.requests[1].text).toBeUndefined()
  })
})

describe('speculation (early transcript at 300 ms of silence), behind the switch', () => {
  it('sends the early words as a spoken turn when nothing was said after them', async () => {
    const t = build([spoken('Hello.')], { early: true })
    await open(t)
    t.detector.handlers?.onStart()
    t.detector.handlers?.onEarly(UTTERANCE)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.requests[1]).toMatchObject({ text: 'How is it built', spoken: true })
    expect(t.requests[1].audio).toBeUndefined()
    expect(t.client.eotMs).toEqual([704])
  })

  it('throws the early words away when speech resumes, and sends the audio', async () => {
    const t = build([spoken('Hello.')], { early: true })
    await open(t)
    t.detector.handlers?.onEarly(UTTERANCE)
    t.detector.handlers?.onResumed()
    expect(t.transcribeSignals[0].aborted).toBe(true)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.requests[1].audio).toBeDefined()
    expect(t.requests[1].text).toBeUndefined()
  })

  it('falls back to the audio when the early transcript failed', async () => {
    const t = build([spoken('Hello.')], { transcript: null, early: true })
    await open(t)
    t.detector.handlers?.onEarly(UTTERANCE)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.requests[1].audio).toBeDefined()
  })
})

describe('the demo', () => {
  it('enters the visit inside the opening tap before asking for the greeting', async () => {
    let enter: (ok: boolean) => void = () => {}
    const prepare = vi.fn(() => new Promise<boolean>((resolve) => (enter = resolve)))
    const t = build([spoken('Hi.')], { prepare })
    void t.client.startVoice()
    await settle()
    expect(prepare).toHaveBeenCalledTimes(1)
    expect(t.requests).toHaveLength(0)
    expect(t.funnel).toEqual(['orb_tap'])
    enter(true)
    await settle()
    expect(t.requests[0].greet).toBe(true)
  })

  it('taps during the entry are ignored: a double (or triple) tap is one conversation, one greeting', async () => {
    let enter: (ok: boolean) => void = () => {}
    const t = build([spoken('Hi.')], { prepare: () => new Promise<boolean>((resolve) => (enter = resolve)) })
    void t.client.startVoice()
    await settle()
    expect(t.client.getState().mic).toBe('starting')
    void t.client.startVoice()
    t.client.interrupt()
    t.client.stopVoice()
    expect(t.client.getState().mic).toBe('starting')
    enter(true)
    await settle()
    expect(t.requests).toHaveLength(1)
    expect(t.requests[0].greet).toBe(true)
    expect(t.audio.micOpen).toBe(true)
    expect(t.client.getState().lines.some((l) => l.kind === 'sys' && l.text === CONVERSATION_CLOSED)).toBe(false)
    // After the greeting has started, a tap behaves normally (it interrupts).
    t.audio.handlers?.onPlaying(false)
    await settle()
    expect(t.client.getState().mic).toBe('on')
  })

  it('an entry that hangs lets the mic go after its deadline, says so, and a tap enters afresh', async () => {
    vi.useFakeTimers()
    try {
      const prepare = vi.fn(() => new Promise<boolean>(() => {}))
      const t = build([spoken('Hi.')], { prepare })
      void t.client.startVoice()
      await vi.advanceTimersByTimeAsync(ENTRY_MS - 100)
      expect(t.audio.micOpen).toBe(true)
      await vi.advanceTimersByTimeAsync(200)
      expect(t.audio.micOpen).toBe(false)
      expect(t.client.getState().mic).toBe('off')
      expect(t.client.getState().activity).not.toBe('thinking')
      expect(t.client.getState().notice?.text).toBe(ENTRY_STALLED.text)
      void t.client.startVoice()
      await vi.advanceTimersByTimeAsync(0)
      expect(prepare).toHaveBeenCalledTimes(2)
      expect(t.audio.micOpen).toBe(true)
      expect(t.requests).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('during a slow entry a tap in the first two seconds is ignored, a later one gives up on it', async () => {
    vi.useFakeTimers()
    try {
      const t = build([spoken('Hi.')], { prepare: () => new Promise<boolean>(() => {}) })
      void t.client.startVoice()
      await vi.advanceTimersByTimeAsync(1_000)
      t.client.interrupt()
      expect(t.audio.micOpen).toBe(true)
      await vi.advanceTimersByTimeAsync(ENTRY_TAP_MS)
      t.client.interrupt()
      expect(t.audio.micOpen).toBe(false)
      expect(t.client.getState().notice?.text).toBe(ENTRY_STALLED.text)
    } finally {
      vi.useRealTimers()
    }
  })

  it("an abandoned entry's timer never abandons the newer entry", async () => {
    vi.useFakeTimers()
    try {
      const prepare = vi.fn((_signal: AbortSignal) => new Promise<boolean>(() => {}))
      const t = build([spoken('Hi.')], { prepare })
      void t.client.startVoice()
      await vi.advanceTimersByTimeAsync(ENTRY_TAP_MS + 500)
      t.client.interrupt()
      expect(t.audio.micOpen).toBe(false)
      // The first entry was aborted, so the page's request can stop.
      expect(prepare.mock.calls[0][0].aborted).toBe(true)
      void t.client.startVoice()
      await vi.advanceTimersByTimeAsync(ENTRY_MS - ENTRY_TAP_MS)
      // Past the first entry's 12 s, inside the second's: the second is still entering.
      expect(t.audio.micOpen).toBe(true)
      expect(t.client.getState().mic).toBe('starting')
      expect(prepare.mock.calls[1][0].aborted).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('a client the page let go of during the entry asks the engine for nothing', async () => {
    let enter: (ok: boolean) => void = () => {}
    const t = build([spoken('Hi.')], { prepare: () => new Promise<boolean>((resolve) => (enter = resolve)) })
    void t.client.startVoice()
    await settle()
    t.client.dispose()
    enter(true)
    await settle()
    expect(t.requests).toHaveLength(0)
    expect(t.client.send('Hello?')).toBe(false)
    t.client.ask('Tell me about Kevin.')
    await settle()
    expect(t.requests).toHaveLength(0)
  })

  it('a failed entry asks for nothing and closes the mic', async () => {
    const t = build([], { prepare: async () => false })
    await t.client.startVoice()
    await settle()
    expect(t.requests).toHaveLength(0)
    expect(t.client.getState().mic).toBe('off')
  })

  it('plays the tour beat by beat and counts its start and finish', async () => {
    const t = build([
      spoken('Hi.'),
      spoken('Beat one.', { tour: { beat: 0, next: 1 } }),
      spoken('Beat two.', { tour: { beat: 1, next: null } }),
    ])
    await open(t)
    t.client.startTour()
    await settle()
    expect(t.requests[1].tour).toBe(0)
    t.audio.handlers?.onPlaying(false)
    await settle()
    expect(t.requests[2].tour).toBe(1)
    t.audio.handlers?.onPlaying(false)
    await settle()
    expect(t.requests).toHaveLength(3)
    expect(t.funnel).toEqual(['orb_tap', 'tour_started', 'tour_finished'])
  })

  it('something said during the tour goes with the beat, so "skip" and "stop" steer it', async () => {
    const t = build([spoken('Hi.'), spoken('Beat one.', { tour: { beat: 0, next: 1 } })])
    await open(t)
    t.client.startTour()
    await settle()
    t.audio.handlers?.onPlaying(true)
    t.detector.fire()
    t.detector.verdict(false)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.requests.at(-1)).toMatchObject({ tour: 1 })
  })

  it('something typed during the tour goes with the beat too', async () => {
    const t = build([spoken('Hi.'), spoken('Beat one.', { tour: { beat: 0, next: 1 } })])
    await open(t)
    t.client.startTour()
    await settle()
    t.client.send('skip')
    await settle()
    expect(t.requests.at(-1)).toMatchObject({ text: 'skip', tour: 1 })
  })

  it('counts the first word heard and the third turn', async () => {
    const heard = (text: string) => spoken('Ok.', {}, [{ type: 'heard', data: { text } }])
    const t = build([spoken('Hi.'), heard('one'), heard('two'), heard('three')])
    await open(t)
    for (let i = 0; i < 3; i++) {
      t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
      await settle()
      t.audio.handlers?.onPlaying(false)
      await settle()
    }
    expect(t.funnel.filter((e) => e === 'first_word')).toHaveLength(1)
    expect(t.funnel.filter((e) => e === 'turns_3')).toHaveLength(1)
  })

  it('fills the live strip from the filler, the tool and the turn cost', async () => {
    const t = build([
      spoken('Hi.'),
      spoken('Clear day.', { cost_usd: 0.0021 }, [
        { type: 'working', data: { tool: 'list_events' } },
        { type: 'filler', data: { tool: 'list_events', text: 'Checking the calendar.' } },
        { type: 'tool', data: { name: 'list_events', ok: true, ms: 412 } },
      ]),
    ])
    await open(t)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    expect(t.client.strip.tools).toEqual([{ tool: 'list_events', ms: 412, filler: 'Checking the calendar.' }])
    expect(t.client.strip.costUsd).toBe(0.0021)
  })

  it('a sentence that was not for it leaves the screen and the page listens again', async () => {
    const t = build([
      spoken('Hi.'),
      {
        ok: true,
        events: stream([
          { type: 'turn', data: { conversation_id: 'c-1', turn: 1 } },
          { type: 'heard', data: { text: 'Wow, listen to that voice.' } },
          { type: 'done', data: { status: 'not_addressed', ignored: 'not_addressed' } },
        ]),
      },
    ])
    await open(t)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    const mine = t.client.getState().lines.filter((l) => l.kind === 'me')
    expect(mine).toEqual([])
    expect(t.client.ignoredTurns).toBe(1)
    expect(t.client.getState().mic).toBe('on')
  })

  it('an unsure sentence shows the cue', async () => {
    const t = build([
      spoken('Hi.'),
      {
        ok: true,
        events: stream([
          { type: 'turn', data: { conversation_id: 'c-1', turn: 1 } },
          { type: 'heard', data: { text: 'Can you believe this?' } },
          { type: 'done', data: { status: 'unsure', ignored: 'unsure', words: 'Were you talking to me? Say my name or tap to go on.' } },
        ]),
      },
    ])
    await open(t)
    t.detector.handlers?.onEnd(UTTERANCE, 'base', 704)
    await settle()
    const sys = t.client.getState().lines.filter((l) => l.kind === 'sys').map((l) => l.text)
    expect(sys).toContain('Were you talking to me? Say my name or tap to go on.')
  })

  it('a tapped question is answered aloud without opening the microphone', async () => {
    const t = build([spoken('He builds things.')])
    expect(t.client.ask('Tell me about Kevin.')).toBe(true)
    await settle()
    expect(t.requests[0]).toMatchObject({ text: 'Tell me about Kevin.', spoken: true })
    expect(t.audio.micOpen).toBe(false)
    expect(t.audio.played).toHaveLength(1)
  })
})
