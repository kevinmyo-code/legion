/// <reference lib="webworker" />
/**
 * The phone's two turn models in the browser (the phone's `TurnModels.kt`), on onnxruntime-web
 * (WASM, one thread) in a worker so the page never waits on them:
 * - Silero VAD v6.2: one 512-sample (32 ms) window at 16 kHz per call, with its LSTM state and
 *   64-sample context carried exactly as the phone's `SileroVad` stream does (input `[1, 576]`,
 *   `state [2, 1, 128]`, `sr` int64);
 * - Smart Turn v3.2 (cpu, int8): `input_features [1, 80, 800]` from `log-mel.ts`.
 * One worker per model (`detector.ts` starts two), so a Smart Turn check never holds up the 32 ms
 * Silero windows, as on the phone where the check runs on its own thread.
 * Model files are this site's own (`/turn/*.onnx`, `public/turn/README.md`), fetched after the
 * first tap; the WASM runtime is bundled by the build. No third-party host is asked for anything.
 */
import * as ort from 'onnxruntime-web/wasm'

import { WhisperLogMel, N_FRAMES, N_MELS } from './log-mel'

export type ToWorker =
  | { type: 'init'; silero?: string; smart?: string }
  | { type: 'window'; seq: number; samples: Float32Array }
  | { type: 'reset' }
  | { type: 'check'; checkId: number; turnKey: number; audio: Float32Array }

export type FromWorker =
  | { type: 'ready'; sileroMs: number; smartMs: number | null; loadMs: number; smartError?: string }
  | { type: 'failed'; reason: string }
  | { type: 'prob'; seq: number; prob: number; ms: number }
  | { type: 'score'; checkId: number; score: number; ms: number }
  | { type: 'check-failed'; checkId: number; reason: string }

const WINDOW = 512
const CONTEXT = 64
const scope = self as unknown as DedicatedWorkerGlobalScope

let silero: ort.InferenceSession | null = null
let smart: ort.InferenceSession | null = null
let state = new Float32Array(2 * 128)
const input = new Float32Array(CONTEXT + WINDOW)
const sr = new ort.Tensor('int64', BigInt64Array.from([16000n]), [])
const mel = new WhisperLogMel()
let queue: Promise<void> = Promise.resolve()

function post(message: FromWorker) {
  scope.postMessage(message)
}

async function prob(samples: Float32Array): Promise<number> {
  input.set(samples, CONTEXT)
  const result = await silero!.run({
    input: new ort.Tensor('float32', input.slice(), [1, CONTEXT + WINDOW]),
    state: new ort.Tensor('float32', state, [2, 1, 128]),
    sr,
  })
  const names = silero!.outputNames
  const p = (result[names[0]].data as Float32Array)[0]
  state = Float32Array.from(result[names[1]].data as Float32Array)
  input.copyWithin(0, WINDOW, WINDOW + CONTEXT)
  return p
}

async function score(audio: Float32Array, turnKey: number): Promise<number> {
  const features = mel.extractTurn(audio, audio.length, turnKey)
  const result = await smart!.run({
    input_features: new ort.Tensor('float32', Float32Array.from(features), [1, N_MELS, N_FRAMES]),
  })
  return (result[smart!.outputNames[0]].data as Float32Array)[0]
}

async function init(sileroUrl?: string, smartUrl?: string) {
  const started = performance.now()
  ort.env.wasm.numThreads = 1
  try {
    const opts: ort.InferenceSession.SessionOptions = { executionProviders: ['wasm'], graphOptimizationLevel: 'all' }
    let smartError: string | undefined
    if (sileroUrl) silero = await ort.InferenceSession.create(new Uint8Array(await (await fetch(sileroUrl)).arrayBuffer()), opts)
    if (smartUrl) {
      try {
        smart = await ort.InferenceSession.create(new Uint8Array(await (await fetch(smartUrl)).arrayBuffer()), opts)
      } catch (error) {
        smartError = String(error).slice(0, 200)
        smart = null
      }
    }
    // Measure once on silence, the way the page decides whether this device is fast enough.
    let t = performance.now()
    let sileroMs = 0
    if (silero) {
      for (let i = 0; i < 5; i++) await prob(new Float32Array(WINDOW))
      sileroMs = (performance.now() - t) / 5
      state = new Float32Array(2 * 128)
      input.fill(0)
    }
    let smartMs: number | null = null
    if (smart) {
      t = performance.now()
      await score(new Float32Array(16000 * 3), -1)
      smartMs = performance.now() - t
      mel.resetTurn()
    }
    post({ type: 'ready', sileroMs, smartMs, loadMs: performance.now() - started, smartError })
  } catch (error) {
    post({ type: 'failed', reason: String(error).slice(0, 300) })
  }
}

scope.onmessage = (event: MessageEvent<ToWorker>) => {
  const message = event.data
  queue = queue.then(async () => {
    if (message.type === 'init') return init(message.silero, message.smart)
    if (!silero && !smart) return
    if (message.type === 'reset') {
      state = new Float32Array(2 * 128)
      input.fill(0)
      return
    }
    if (message.type === 'window' && silero) {
      const t = performance.now()
      const p = await prob(message.samples)
      post({ type: 'prob', seq: message.seq, prob: p, ms: performance.now() - t })
      return
    }
    if (message.type === 'check') {
      if (!smart) {
        post({ type: 'check-failed', checkId: message.checkId, reason: 'no smart turn' })
        return
      }
      const t = performance.now()
      try {
        const s = await score(message.audio, message.turnKey)
        post({ type: 'score', checkId: message.checkId, score: s, ms: performance.now() - t })
      } catch (error) {
        post({ type: 'check-failed', checkId: message.checkId, reason: String(error).slice(0, 200) })
      }
    }
  })
}
