// @vitest-environment node
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import type * as Ort from 'onnxruntime-web'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'

import { loadTurnDetector, type TurnDetector } from './detector'
import { chunks, concat, silence } from './fixtures'
import type { FromWorker, ToWorker } from './turn.worker'

// Fails fast, with its name, if anything hangs.
vi.setConfig({ testTimeout: 10_000 })

/**
 * The real Silero VAD v6.2 (the site's own `public/turn/` copy) on onnxruntime-web, in Node:
 * - per-window probabilities against the phone's golden (`golden.json`, tolerance 1e-3 as on the
 *   phone);
 * - the whole detector on the PCM fixtures: speech then silence ends a turn; the reply alone,
 *   heard back at a lower level, never fires a barge-in; the person talking over it does.
 * The speech is the phone's golden vector (`__fixtures__/golden_short.pcm`); the "echo" is the
 * same audio scaled down, as the browser's echo cancellation leaves it.
 */

const here = (path: string) => fileURLToPath(new URL(path, import.meta.url))
const WINDOW = 512
const CONTEXT = 64

// Loaded only when opted in: importing the runtime starts WebAssembly work that kept a default
// `vitest run` from exiting (2026-11-17).
let ort: typeof Ort
let session: Ort.InferenceSession | undefined

/**
 * Opt-in (`LEGION_SILERO_TEST=1`): the real model in Node is slow to start and once ran away in a
 * CI sandbox (2026-11-17: a node process at 100% CPU for 8 minutes). The phone's golden test and the
 * headless browser check cover the model; this is for a person checking the web port by hand. Every
 * step has a hard timeout and the session is released.
 */
const RUN = process.env.LEGION_SILERO_TEST === '1'

function within<T>(ms: number, work: Promise<T>): Promise<T> {
  return Promise.race([work, new Promise<T>((_, reject) => setTimeout(() => reject(new Error(`over ${ms} ms`)), ms))])
}

beforeAll(async () => {
  if (!RUN) return
  ort = await import('onnxruntime-web')
  ort.env.wasm.numThreads = 1
  ort.env.wasm.proxy = false
  session = await within(
    30_000,
    ort.InferenceSession.create(new Uint8Array(readFileSync(here('../../../public/turn/silero_vad_v6_2.onnx')))),
  )
}, 40_000)

afterAll(async () => {
  await session?.release()
})

function golden(): Int16Array {
  const b = readFileSync(here('./__fixtures__/golden_short.pcm'))
  return new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength))
}

function scaled(pcm: Int16Array, factor: number): Int16Array {
  return Int16Array.from(pcm, (v) => Math.round(v * factor))
}

function mix(a: Int16Array, b: Int16Array): Int16Array {
  const out = new Int16Array(Math.max(a.length, b.length))
  for (let i = 0; i < out.length; i++) out[i] = Math.max(-32767, Math.min(32767, (a[i] ?? 0) + (b[i] ?? 0)))
  return out
}

/** A stand-in for `turn.worker.ts` running the same Silero step in this process. */
class InProcessSilero {
  onmessage: ((e: MessageEvent<FromWorker>) => void) | null = null
  private state = new Float32Array(256)
  private input = new Float32Array(CONTEXT + WINDOW)
  private queue: Promise<void> = Promise.resolve()

  async prob(samples: Float32Array): Promise<number> {
    this.input.set(samples, CONTEXT)
    const result = await session!.run({
      input: new ort.Tensor('float32', this.input.slice(), [1, CONTEXT + WINDOW]),
      state: new ort.Tensor('float32', this.state, [2, 1, 128]),
      sr: new ort.Tensor('int64', BigInt64Array.from([16000n]), []),
    })
    const names = session!.outputNames
    this.state = Float32Array.from(result[names[1]].data as Float32Array)
    this.input.copyWithin(0, WINDOW, WINDOW + CONTEXT)
    return (result[names[0]].data as Float32Array)[0]
  }

  postMessage(msg: ToWorker) {
    this.queue = this.queue.then(async () => {
      let reply: FromWorker | null = null
      if (msg.type === 'init') reply = msg.silero ? { type: 'ready', sileroMs: 1, smartMs: null, loadMs: 1 } : { type: 'failed', reason: 'silero only here' }
      else if (msg.type === 'window') reply = { type: 'prob', seq: msg.seq, prob: await this.prob(msg.samples), ms: 1 }
      if (reply) this.onmessage?.({ data: reply } as MessageEvent<FromWorker>)
    })
  }

  terminate() {}

  done() {
    return this.queue
  }
}

describe.skipIf(!RUN)('Silero VAD v6.2 on onnxruntime-web', () => {
  it('matches the phone golden per window', async () => {
    const want: number[] = JSON.parse(readFileSync(here('./__fixtures__/golden.json'), 'utf8')).silero_short
    const pcm = golden()
    const vad = new InProcessSilero()
    let worst = 0
    for (let w = 0; w < want.length; w++) {
      const window = Float32Array.from(pcm.subarray(w * WINDOW, (w + 1) * WINDOW), (v) => v / 32768)
      worst = Math.max(worst, Math.abs((await vad.prob(window)) - want[w]))
    }
    expect(worst).toBeLessThan(1e-3)
  }, 60_000)
})

describe.skipIf(!RUN)('the detector on real Silero (Smart Turn absent: silence only)', () => {
  let workers: InProcessSilero[]

  async function load(): Promise<TurnDetector> {
    workers = []
    const detector = await loadTurnDetector({
      makeWorker: () => {
        const w = new InProcessSilero()
        workers.push(w)
        return w
      },
      sileroUrl: 'silero',
      smartUrl: 'smart',
    })
    expect(detector).not.toBeNull()
    return detector!
  }

  async function play(detector: TurnDetector, pcm: Int16Array, listening: boolean, playing: boolean) {
    for (const c of chunks(pcm)) detector.push(c, listening, playing)
    await workers[0].done()
  }

  it('ends a spoken turn after its silence, well inside the old 1.2 s', async () => {
    const detector = await load()
    const ends: number[] = []
    let starts = 0
    detector.setHandlers({
      onStart: () => starts++,
      onSpeculate: () => {},
      onResumed: () => {},
      onEnd: (_a, _r, silenceMs) => ends.push(silenceMs),
      onBargeIn: () => {},
      onVerdict: () => {},
    })
    await play(detector, concat(silence(500), golden(), silence(1500)), true, false)
    expect(starts).toBeGreaterThanOrEqual(1)
    expect(ends.length).toBeGreaterThanOrEqual(1)
    expect(ends[ends.length - 1]).toBeLessThan(1200)
  }, 60_000)

  it('does not barge in on the reply alone, and does when the person talks over it', async () => {
    const detector = await load()
    let bargeIns = 0
    detector.setHandlers({
      onStart: () => {},
      onSpeculate: () => {},
      onResumed: () => {},
      onEnd: () => {},
      onBargeIn: () => bargeIns++,
      onVerdict: () => {},
    })
    const reply = concat(golden(), golden())
    detector.replyStarted()
    // The reply alone, heard back at a quarter of its level.
    await play(detector, scaled(reply, 0.25), false, true)
    expect(bargeIns).toBe(0)
    // The person over it at full level.
    await play(detector, mix(scaled(reply.subarray(0, 32_000), 0.25), golden().subarray(0, 32_000)), false, true)
    expect(bargeIns).toBe(1)
    detector.replyEnded()
  }, 60_000)
})
