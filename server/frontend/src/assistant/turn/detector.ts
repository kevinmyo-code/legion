/**
 * The phone's turn-taking in the browser (ADR 0064, recruiter-demo ticket 12): Silero VAD windows
 * fold through the phone's `EndOfTurnPolicy` (when the person's turn ends, Smart Turn extending it)
 * and its `BargeInGate` (when they talk over the reply), with the models in two workers.
 *
 * The page feeds every 20 ms capture chunk (16 kHz PCM16) with whether the reply is audible; this
 * cuts 512-sample windows, asks the Silero worker for each window's probability in order, and acts
 * on the policy's and the gate's answers:
 * - `onStart` (speech confirmed; the utterance begins with the phone's 544 ms pre-roll),
 * - `onEarly` (EARLY_MS of silence: the audio so far, for an early transcript),
 * - `onResumed` (speech came back: drop that early transcript),
 * - `onEnd` (the turn ended: the utterance, the reason and how long the silence was),
 * - `onBargeIn` (sustained speech over the reply) and `onVerdict` (whether that was the reply's
 *   own echo).
 *
 * `loadTurnDetector` returns null when the models cannot load or run too slowly here
 * (`MAX_SILERO_MS` per window, `MAX_SMART_MS` per check); the page then keeps its energy-threshold
 * `Endpointer` and listens half-duplex. A device fast enough for Silero but not Smart Turn gets
 * silence-only end of turn at the phone's 700 ms base (`smartAvailable` false).
 */
import { BargeInGate, peakLevel, type GateEvent } from './barge-in-gate'
import { EndOfTurnPolicy, WINDOW_MS, type Action, type Reason } from './eot-policy'
import type { FromWorker, ToWorker } from './turn.worker'

export const WINDOW = 512
/** The phone's pre-roll before speech onset (ADR 0064: 544 ms, 17 windows). */
export const PREROLL_WINDOWS = 17
/** Slower than this per Silero window and the device cannot keep up with 32 ms windows. */
export const MAX_SILERO_MS = 16
/** Slower than this per Smart Turn check and it could not hold a turn before the 700 ms base. */
export const MAX_SMART_MS = 450
/** The longest turn kept (Smart Turn reads the last 8 s; the engine takes 28 s). */
export const MAX_TURN_SAMPLES = 28 * 16_000

export interface TurnHandlers {
  onStart(): void
  onEarly(audio: Int16Array): void
  onResumed(): void
  onEnd(audio: Int16Array, reason: Reason, silenceMs: number): void
  onBargeIn(event: Extract<GateEvent, { type: 'fire' }>): void
  onVerdict(event: Extract<GateEvent, { type: 'verdict' }>): void
}

export interface TurnDetector {
  readonly kind: 'models'
  readonly smartAvailable: boolean
  readonly loadMs: number
  readonly sileroMs: number
  readonly smartMs: number | null
  readonly gate: BargeInGate
  setHandlers(handlers: TurnHandlers | null): void
  /** One capture chunk. `listening`: the page wants a turn now; `playing`: the reply is audible. */
  push(chunk: Int16Array, listening: boolean, playing: boolean): void
  /** The reply started or stopped being audible (the gate learns each reply's echo). */
  replyStarted(): void
  replyEnded(): void
  /** Forget any turn in progress (the page closed or reset the conversation). */
  reset(): void
  dispose(): void
}

type WorkerLike = Pick<Worker, 'postMessage' | 'terminate'> & { onmessage: ((e: MessageEvent<FromWorker>) => void) | null }

export interface DetectorDeps {
  makeWorker(): WorkerLike
  sileroUrl: string
  smartUrl: string
}

function toFloat(pcm: Int16Array): Float32Array {
  const out = new Float32Array(pcm.length)
  for (let i = 0; i < pcm.length; i++) out[i] = pcm[i] / 32768
  return out
}

function ready(worker: WorkerLike, init: ToWorker, timeoutMs: number): Promise<Extract<FromWorker, { type: 'ready' }> | null> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), timeoutMs)
    worker.onmessage = (e) => {
      if (e.data.type === 'ready' || e.data.type === 'failed') {
        clearTimeout(timer)
        resolve(e.data.type === 'ready' ? e.data : null)
      }
    }
    worker.postMessage(init)
  })
}

/** Loads both models; null when they cannot run here (the page keeps its energy threshold). */
export async function loadTurnDetector(deps: DetectorDeps, timeoutMs = 20_000): Promise<TurnDetector | null> {
  const vad = deps.makeWorker()
  const st = deps.makeWorker()
  const [v, s] = await Promise.all([
    ready(vad, { type: 'init', silero: deps.sileroUrl }, timeoutMs),
    ready(st, { type: 'init', smart: deps.smartUrl }, timeoutMs),
  ])
  if (!v || v.sileroMs > MAX_SILERO_MS) {
    vad.terminate()
    st.terminate()
    return null
  }
  const smartOk = Boolean(s && s.smartMs !== null && s.smartMs <= MAX_SMART_MS)
  if (!smartOk) st.terminate()
  return new ModelDetector(vad, smartOk ? st : null, Math.max(v.loadMs, s?.loadMs ?? 0), v.sileroMs, s?.smartMs ?? null)
}

class ModelDetector implements TurnDetector {
  readonly kind = 'models' as const
  readonly gate = new BargeInGate()
  readonly loadMs: number
  readonly sileroMs: number
  readonly smartMs: number | null
  private readonly policy: EndOfTurnPolicy
  private handlers: TurnHandlers | null = null
  private pending = new Int16Array(0)
  private seq = 0
  /** Windows sent and not yet answered, in order: their samples, level and whether listening. */
  private inFlight = new Map<number, { pcm: Int16Array; level: number; listening: boolean; playing: boolean }>()
  private recent: Int16Array[] = []
  private turn: Int16Array[] | null = null
  private turnSamples = 0
  private turnKey = 0
  private disposed = false

  private readonly vad: WorkerLike
  private readonly smart: WorkerLike | null

  constructor(vad: WorkerLike, smart: WorkerLike | null, loadMs: number, sileroMs: number, smartMs: number | null) {
    this.vad = vad
    this.smart = smart
    this.loadMs = loadMs
    this.sileroMs = sileroMs
    this.smartMs = smartMs
    this.policy = new EndOfTurnPolicy(smart !== null)
    vad.onmessage = (e) => {
      if (e.data.type === 'prob') this.onProb(e.data.seq, e.data.prob)
    }
    if (smart) {
      smart.onmessage = (e) => {
        if (e.data.type === 'score') this.act(this.policy.onCheckResult(e.data.checkId, e.data.score))
        else if (e.data.type === 'check-failed') this.act(this.policy.onCheckFailed(e.data.checkId))
      }
    }
  }

  get smartAvailable(): boolean {
    return this.policy.smartAvailable
  }

  setHandlers(handlers: TurnHandlers | null) {
    this.handlers = handlers
  }

  push(chunk: Int16Array, listening: boolean, playing: boolean) {
    if (this.disposed) return
    const merged = new Int16Array(this.pending.length + chunk.length)
    merged.set(this.pending)
    merged.set(chunk, this.pending.length)
    let at = 0
    while (merged.length - at >= WINDOW) {
      const pcm = merged.slice(at, at + WINDOW)
      at += WINDOW
      const seq = ++this.seq
      this.inFlight.set(seq, { pcm, level: peakLevel(pcm), listening, playing })
      this.vad.postMessage({ type: 'window', seq, samples: toFloat(pcm) } satisfies ToWorker)
    }
    this.pending = merged.slice(at)
  }

  replyStarted() {
    this.gate.onReplyStart()
  }

  replyEnded() {
    this.gate.onReplyEnd()
  }

  reset() {
    this.policy.reset()
    this.turn = null
    this.turnSamples = 0
  }

  dispose() {
    this.disposed = true
    this.vad.terminate()
    this.smart?.terminate()
  }

  private onProb(seq: number, prob: number) {
    const w = this.inFlight.get(seq)
    this.inFlight.delete(seq)
    if (!w || this.disposed) return
    this.recent.push(w.pcm)
    if (this.recent.length > PREROLL_WINDOWS) this.recent.shift()
    if (this.turn && this.turnSamples < MAX_TURN_SAMPLES) {
      this.turn.push(w.pcm)
      this.turnSamples += w.pcm.length
    }
    if (w.playing) {
      const event = this.gate.onWindow(prob, w.level, true)
      if (event?.type === 'fire') {
        // Talked over: the activity starts now, with the windows that fired it as its start.
        const start = this.policy.forceStart()
        this.beginTurn()
        this.handlers?.onBargeIn(event)
        if (start) this.handlers?.onStart()
        return
      }
      if (event?.type === 'verdict') this.handlers?.onVerdict(event)
      if (!this.turn) return
    } else {
      const event = this.gate.onWindow(prob, w.level, false)
      if (event?.type === 'verdict') this.handlers?.onVerdict(event)
    }
    if (!w.listening && !this.turn) return
    this.act(this.policy.onWindow(prob))
  }

  private beginTurn() {
    this.turn = [...this.recent]
    this.turnSamples = this.turn.reduce((n, c) => n + c.length, 0)
    this.turnKey++
  }

  private audio(): Int16Array {
    const out = new Int16Array(this.turnSamples)
    let at = 0
    for (const c of this.turn ?? []) {
      out.set(c, at)
      at += c.length
    }
    return out
  }

  private act(actions: Action[]) {
    for (const a of actions) {
      if (a.type === 'start') {
        if (!this.turn) this.beginTurn()
        this.handlers?.onStart()
      } else if (a.type === 'check') {
        const pcm = this.audio()
        this.smart?.postMessage({ type: 'check', checkId: a.checkId, turnKey: this.turnKey, audio: toFloat(pcm) } satisfies ToWorker)
      } else if (a.type === 'early') {
        this.handlers?.onEarly(this.audio())
      } else if (a.type === 'resumed') {
        this.handlers?.onResumed()
      } else if (a.type === 'end') {
        const pcm = this.audio()
        this.turn = null
        this.turnSamples = 0
        this.gate.onUserTurnEnd()
        this.handlers?.onEnd(pcm, a.reason, a.silenceMs)
      }
    }
  }
}

export { WINDOW_MS }
