import { afterEach, describe, expect, it, vi } from 'vitest'

import { BASE_MS, EARLY_MS } from './eot-policy'
import { loadTurnDetector, type TurnDetector, type TurnHandlers } from './detector'
import { chunks, concat, echo, over, silence, speech } from './fixtures'
import type { FromWorker, ToWorker } from './turn.worker'
import { SILENCE_MS } from '../endpointer'

/**
 * The detector's turn and barge-in logic on synthesized PCM (`fixtures.ts`), with the two models
 * stood in for by workers that answer like them: "speech" is any window with real energy (so the
 * reply's echo IS speech to it, as it is to Silero), and Smart Turn says the turn is complete. What
 * keeps the assistant from hearing itself is the gate's echo floor, which is the thing under test.
 * `silero.node.test.ts` runs the real Silero on the same fixtures.
 */

class FakeWorker {
  onmessage: ((e: MessageEvent<FromWorker>) => void) | null = null
  terminated = false
  private readonly answer: (msg: ToWorker) => FromWorker | null
  constructor(answer: (msg: ToWorker) => FromWorker | null) {
    this.answer = answer
  }
  postMessage(msg: ToWorker) {
    if (this.terminated) return
    const reply = this.answer(msg)
    if (!reply) return
    // A Smart Turn check answers after a real model's time, on a timer: answered at once (a
    // microtask), a "not done" score asks again at once, forever, and nothing else ever runs.
    const deliver = () => {
      if (!this.terminated) this.onmessage?.({ data: reply } as MessageEvent<FromWorker>)
    }
    if (msg.type === 'check') setTimeout(deliver, 5)
    else queueMicrotask(deliver)
  }
  terminate() {
    this.terminated = true
  }
}

function rms(samples: Float32Array): number {
  let sum = 0
  for (const v of samples) sum += v * v
  return Math.sqrt(sum / samples.length)
}

function fakeWorkers(options: { sileroMs?: number; smartMs?: number | null; smartScore?: number } = {}) {
  const made: FakeWorker[] = []
  const makeWorker = () => {
    const w = new FakeWorker((msg) => {
      switch (msg.type) {
        case 'init':
          if (msg.silero) return { type: 'ready', sileroMs: options.sileroMs ?? 2, smartMs: null, loadMs: 40 }
          if (options.smartMs === null) return { type: 'failed', reason: 'no smart turn here' }
          return { type: 'ready', sileroMs: 0, smartMs: options.smartMs ?? 120, loadMs: 60 }
        case 'window':
          // Speech-like at any real level; the fixtures' syllable troughs (RMS ~0.015) still count,
          // as they do for Silero, so a pause is only real silence.
          return { type: 'prob', seq: msg.seq, prob: rms(msg.samples) > 0.005 ? 0.95 : 0.02, ms: 1 }
        case 'check':
          return { type: 'score', checkId: msg.checkId, score: options.smartScore ?? 0.9, ms: 90 }
        default:
          return null
      }
    })
    made.push(w)
    return w
  }
  return { makeWorker, made }
}

async function flush() {
  for (let i = 0; i < 6; i++) await new Promise((resolve) => setTimeout(resolve, 0))
}

interface Seen {
  starts: number
  early: number[]
  resumed: number
  ends: { samples: number; reason: string; silenceMs: number }[]
  bargeIns: number
  verdicts: { selfInterrupt: boolean; nowDisabled: boolean }[]
}

function watch(detector: TurnDetector): Seen {
  const seen: Seen = { starts: 0, early: [], resumed: 0, ends: [], bargeIns: 0, verdicts: [] }
  const handlers: TurnHandlers = {
    onStart: () => seen.starts++,
    onEarly: (audio) => seen.early.push(audio.length),
    onResumed: () => seen.resumed++,
    onEnd: (audio, reason, silenceMs) => seen.ends.push({ samples: audio.length, reason, silenceMs }),
    onBargeIn: () => seen.bargeIns++,
    onVerdict: (v) => seen.verdicts.push({ selfInterrupt: v.selfInterrupt, nowDisabled: v.nowDisabled }),
  }
  detector.setHandlers(handlers)
  return seen
}

async function play(detector: TurnDetector, pcm: Int16Array, listening: boolean, playing: boolean) {
  for (const c of chunks(pcm)) {
    detector.push(c, listening, playing)
    await flush()
  }
}

// Every test here fails fast, with its name, if anything hangs.
vi.setConfig({ testTimeout: 10_000 })

const opened: TurnDetector[] = []
afterEach(() => {
  // Nothing outlives its test: no worker, no timer.
  while (opened.length) opened.pop()!.dispose()
})

async function load(options: Parameters<typeof fakeWorkers>[0] = {}) {
  const { makeWorker, made } = fakeWorkers(options)
  const detector = await loadTurnDetector({ makeWorker, sileroUrl: '/turn/s.onnx', smartUrl: '/turn/t.onnx' }, 1000)
  if (detector) opened.push(detector)
  return { detector, made }
}

describe('loadTurnDetector', () => {
  it('loads both models and says how fast they ran', async () => {
    const { detector } = await load()
    expect(detector).not.toBeNull()
    expect(detector!.smartAvailable).toBe(true)
  })

  it('falls back to the energy threshold when Silero is too slow here', async () => {
    const { detector, made } = await load({ sileroMs: 40 })
    expect(detector).toBeNull()
    expect(made.every((w) => w.terminated)).toBe(true)
  })

  it('runs on Silero alone when Smart Turn fails or is too slow', async () => {
    expect((await load({ smartMs: null })).detector!.smartAvailable).toBe(false)
    expect((await load({ smartMs: 900 })).detector!.smartAvailable).toBe(false)
  })
})

describe('the turn (speech, then silence)', () => {
  it('starts on speech, transcribes early at 300 ms, and ends at the base silence', async () => {
    const { detector } = await load()
    const seen = watch(detector!)
    await play(detector!, concat(silence(300), speech(1200), silence(1000)), true, false)
    expect(seen.starts).toBe(1)
    expect(seen.early).toHaveLength(1)
    expect(seen.ends).toHaveLength(1)
    const end = seen.ends[0]
    expect(end.reason).toBe('base')
    // The end of turn: BASE_MS of silence (to one window), against the old 1.2 s energy window.
    expect(end.silenceMs).toBeGreaterThanOrEqual(BASE_MS)
    expect(end.silenceMs).toBeLessThan(BASE_MS + 64)
    expect(end.silenceMs).toBeLessThan(SILENCE_MS)
    // The turn's audio carries its start (the pre-roll) and the whole utterance.
    expect(end.samples).toBeGreaterThan(1.2 * 16_000)
    expect(seen.early[0]).toBeLessThanOrEqual(end.samples)
    expect(EARLY_MS).toBe(300)
  })

  it('throws the early transcript away when speech resumes', async () => {
    const { detector } = await load()
    const seen = watch(detector!)
    await play(detector!, concat(speech(800), silence(400), speech(800), silence(900)), true, false)
    expect(seen.early.length).toBe(2)
    expect(seen.resumed).toBe(1)
    expect(seen.ends).toHaveLength(1)
  })

  it('holds the turn open while Smart Turn says the person is not done', async () => {
    const { detector } = await load({ smartScore: 0.1 })
    const seen = watch(detector!)
    await play(detector!, concat(speech(1000), silence(1500)), true, false)
    expect(seen.ends).toHaveLength(0)
    await play(detector!, silence(1200), true, false)
    expect(seen.ends.map((e) => e.reason)).toEqual(['cap'])
  })

  it('hears nothing in silence alone', async () => {
    const { detector } = await load()
    const seen = watch(detector!)
    await play(detector!, silence(3000), true, false)
    expect(seen.starts + seen.ends.length + seen.early.length).toBe(0)
  })
})

describe('barge-in while the reply plays', () => {
  it('does not fire on the reply alone (playback heard back at a lower level)', async () => {
    const { detector } = await load()
    const seen = watch(detector!)
    detector!.replyStarted()
    await play(detector!, echo(4000), false, true)
    detector!.replyEnded()
    expect(seen.bargeIns).toBe(0)
    expect(seen.starts).toBe(0)
  })

  it('fires when the person talks over the reply, and the turn carries what they said', async () => {
    const { detector } = await load()
    const seen = watch(detector!)
    detector!.replyStarted()
    await play(detector!, echo(1200), false, true)
    await play(detector!, over(600), false, true)
    expect(seen.bargeIns).toBe(1)
    expect(seen.starts).toBe(1)
    // The page stops playback; the person finishes and goes quiet.
    detector!.replyEnded()
    await play(detector!, concat(speech(800), silence(900)), true, false)
    expect(seen.ends).toHaveLength(1)
    expect(seen.ends[0].samples).toBeGreaterThan(16_000)
  })

  it('counts a barge-in with no speech after it as its own voice, and stops after two', async () => {
    const { detector } = await load()
    const seen = watch(detector!)
    for (let i = 0; i < 2; i++) {
      detector!.replyStarted()
      await play(detector!, echo(1200), false, true)
      // A burst over the echo floor (window peaks ~4.1k to 7.5k against a threshold of ~5k), but
      // under twice it, so not "clearly the user": it fires, then nothing more is said.
      await play(detector!, over(400, 11_000), false, true)
      await play(detector!, silence(1400), false, true)
      detector!.replyEnded()
      detector!.reset()
    }
    expect(seen.bargeIns).toBe(2)
    expect(seen.verdicts.map((v) => v.selfInterrupt)).toEqual([true, true])
    expect(detector!.gate.disabled).toBe(true)
    // Half-duplex now: talking over the reply does nothing.
    detector!.replyStarted()
    await play(detector!, over(1000), false, true)
    expect(seen.bargeIns).toBe(2)
  })
})
