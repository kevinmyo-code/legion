/**
 * When the person's turn starts and ends: the phone's `EndOfTurnPolicy.kt` (app/.../service/turn/),
 * ported line for line as a pure fold over Silero windows (32 ms each) and Smart Turn results.
 * Same constants, held to `shared/turn/spec.json` and run through `shared/turn/cases.json` by
 * `turn-spec.node.test.ts` (the phone's `SharedTurnSpecTest.kt` runs the same file).
 *
 * - Speech starts after CONFIRM_MS of Silero speech (start at >= START_PROB, held while >= END_PROB).
 * - Silence is time since the last window >= END_PROB; speech resumes at >= START_PROB and cancels
 *   everything pending.
 * - At GATE_MS of silence one Smart Turn check starts on the whole turn.
 * - At BASE_MS of silence the turn ends (base) unless a check already said "not done".
 * - A result below THRESHOLD that arrives BEFORE BASE_MS holds the turn open; checks then run back to
 *   back and the turn ends at the first scoring >= THRESHOLD (extended), or at CAP_MS (cap).
 * - Without a working Smart Turn it is silence only (fallback).
 * - With `finishedMs` below the base (the cascade, Kevin 2026-11-19): a "done" verdict before the base
 *   ends the turn at `finishedMs` of silence, or at once if that is already past (finished).
 * - With `earlyEnd` too (ADR 0064 amendment 2026-11-21): the FIRST verdict of a silence scoring >=
 *   EARLY_THRESHOLD that lands before `finishedMs` ends the turn at EARLY_MS, or on arrival if later
 *   (early). A silence that already heard "not done" keeps the `finishedMs` rule.
 *
 * The web adds one thing the phone's cascade does elsewhere (`Speculation.kt`): a `speculate` action
 * at SPECULATE_MS of silence, when the browser may start transcribing what it has (off on the page,
 * `deps.ts` EARLY_TRANSCRIPT; thrown away if speech resumes). It never ends a turn.
 */

export const WINDOW_MS = 32
export const START_PROB = 0.5
export const END_PROB = 0.35
export const CONFIRM_MS = 200
export const GATE_MS = 192
export const BASE_MS = 700
/** The cascade's end after a Smart Turn "done" (Kevin, 2026-11-19: "700 -> 500"). */
export const FINISHED_MS = 500
export const CAP_MS = 2_500
export const THRESHOLD = 0.5
/** A first verdict at or above this ends the cascade's turn at EARLY_MS (ADR 0064, 2026-11-21). */
export const EARLY_THRESHOLD = 0.95
/** The least silence an early end takes; a verdict landing sooner waits for it. */
export const EARLY_MS = 300
/** The web's speculation point: transcribe what was said once this much silence has passed. */
export const SPECULATE_MS = 300

export type Reason = 'base' | 'finished' | 'early' | 'extended' | 'cap' | 'fallback'

export interface Score {
  score: number
  askedAtMs: number
  arrivedAtMs: number
}

export type Action =
  | { type: 'start'; onsetWindows: number }
  | { type: 'check'; checkId: number; silenceMs: number }
  | { type: 'speculate'; silenceMs: number }
  | { type: 'end'; reason: Reason; silenceMs: number; scores: Score[]; longestSilenceMs: number }
  | { type: 'resumed'; afterSilenceMs: number }

type Phase = 'idle' | 'candidate' | 'speaking' | 'silence'

export class EndOfTurnPolicy {
  smartAvailable: boolean
  private readonly baseMs: number
  /** The silence that ends a turn Smart Turn called finished; at or above the base it is off. */
  private readonly finishedMs: number
  private readonly earlyEnd: boolean
  private phase: Phase = 'idle'
  private candidateWindows = 0
  private silenceMs = 0
  private hold = false
  private anyResult = false
  /** Smart Turn said "done" before the base and the finished end is on: the silence that ends it. */
  private finishAtMs: number | null = null
  private extendedThisTurn = false
  private longestSilenceMs = 0
  private checkId = 0
  private inFlight: number | null = null
  private inFlightAskedAt = 0
  private scores: Score[] = []
  private speculated = false

  constructor(smartAvailable: boolean, baseMs: number = BASE_MS, finishedMs: number = baseMs, earlyEnd = false) {
    this.smartAvailable = smartAvailable
    this.baseMs = baseMs
    this.finishedMs = finishedMs
    this.earlyEnd = earlyEnd
  }

  get inActivity(): boolean {
    return this.phase === 'speaking' || this.phase === 'silence'
  }

  get currentSilenceMs(): number {
    return this.phase === 'silence' ? this.silenceMs : 0
  }

  /** One Silero window. Returns what to do, in order (usually nothing). */
  onWindow(prob: number): Action[] {
    switch (this.phase) {
      case 'idle':
        if (prob >= START_PROB) {
          this.phase = 'candidate'
          this.candidateWindows = 1
          return this.confirmIfLongEnough()
        }
        return []
      case 'candidate':
        if (prob >= END_PROB) {
          this.candidateWindows++
          return this.confirmIfLongEnough()
        }
        this.phase = 'idle'
        this.candidateWindows = 0
        return []
      case 'speaking':
        if (prob < END_PROB) {
          this.phase = 'silence'
          this.silenceMs = 0
          return this.advanceSilence()
        }
        return []
      case 'silence':
        if (prob >= START_PROB) {
          const was = this.silenceMs
          this.resetSilence()
          this.phase = 'speaking'
          return [{ type: 'resumed', afterSilenceMs: was }]
        }
        return this.advanceSilence()
    }
  }

  /** Barge-in: the echo gate already confirmed speech, so the activity starts now. */
  forceStart(): Action | null {
    if (this.inActivity) return null
    this.phase = 'speaking'
    this.candidateWindows = 0
    this.resetSilence()
    this.scores = []
    this.extendedThisTurn = false
    this.longestSilenceMs = 0
    return { type: 'start', onsetWindows: 0 }
  }

  /** A Smart Turn answer. Stale ids (speech resumed, turn ended) are ignored. */
  onCheckResult(id: number, score: number): Action[] {
    if (id !== this.inFlight || this.phase !== 'silence') return []
    this.inFlight = null
    this.scores.push({ score, askedAtMs: this.inFlightAskedAt, arrivedAtMs: this.silenceMs })
    const first = !this.anyResult
    this.anyResult = true
    const done = score >= THRESHOLD
    if (done && this.finishedApplies(first)) return this.finish(first && score >= EARLY_THRESHOLD)
    // The verdict that matters for the base: only a "not done" before it holds.
    if (first && !done && this.silenceMs < this.baseMs) {
      this.hold = true
      return [this.request()]
    }
    if (!this.hold) return []
    if (done && this.silenceMs < this.baseMs) {
      this.hold = false
      return []
    }
    if (done) return this.end('extended')
    return [this.request()]
  }

  /** Smart Turn threw: silence only from here on. */
  onCheckFailed(id: number): Action[] {
    if (id === this.inFlight) this.inFlight = null
    this.smartAvailable = false
    if (this.phase === 'silence' && this.hold) {
      this.hold = false
      if (this.silenceMs >= this.baseMs) return this.end('fallback')
    }
    return []
  }

  /** The caller ended the turn for its own reason; forget it. */
  reset() {
    this.phase = 'idle'
    this.candidateWindows = 0
    this.resetSilence()
    this.scores = []
    this.extendedThisTurn = false
    this.longestSilenceMs = 0
  }

  private confirmIfLongEnough(): Action[] {
    if (this.candidateWindows * WINDOW_MS < CONFIRM_MS) return []
    this.phase = 'speaking'
    const onset = this.candidateWindows
    this.candidateWindows = 0
    this.resetSilence()
    this.scores = []
    this.extendedThisTurn = false
    this.longestSilenceMs = 0
    return [{ type: 'start', onsetWindows: onset }]
  }

  private advanceSilence(): Action[] {
    const before = this.silenceMs
    this.silenceMs += WINDOW_MS
    if (this.silenceMs > this.longestSilenceMs) this.longestSilenceMs = this.silenceMs
    if (this.hold && before < this.baseMs && this.silenceMs >= this.baseMs) this.extendedThisTurn = true
    const out: Action[] = []
    if (this.crossedGate(before)) out.push(this.request())
    if (!this.speculated && before < SPECULATE_MS && this.silenceMs >= SPECULATE_MS) {
      this.speculated = true
      out.push({ type: 'speculate', silenceMs: this.silenceMs })
    }
    const reason = this.silenceEnd()
    if (reason) out.push(...this.end(reason))
    return out
  }

  /** A "done" now ends the turn at finishedMs: on, before the base, and the verdict that counts. */
  private finishedApplies(first: boolean): boolean {
    return this.finishedMs < this.baseMs && this.silenceMs < this.baseMs && (first || this.hold)
  }

  /** `clear`: the silence's first verdict, scoring high. It ends early only if it beat finishedMs. */
  private finish(clear: boolean): Action[] {
    this.hold = false
    const at = this.earlyEnd && clear && this.silenceMs < this.finishedMs ? EARLY_MS : this.finishedMs
    this.finishAtMs = at
    return this.silenceMs >= at ? this.end(this.finishReason(at)) : []
  }

  private finishReason(at: number): Reason {
    return at < this.finishedMs ? 'early' : 'finished'
  }

  /** The silence end that applies now, or null while the turn goes on. */
  private silenceEnd(): Reason | null {
    const at = this.finishAtMs
    if (this.silenceMs >= CAP_MS) return 'cap'
    if (this.hold) return null
    if (at !== null && this.silenceMs >= at) return this.finishReason(at)
    if (this.silenceMs < this.baseMs) return null
    if (this.extendedThisTurn) return 'extended'
    return this.smartAvailable ? 'base' : 'fallback'
  }

  private crossedGate(before: number): boolean {
    const crossed = before < GATE_MS && this.silenceMs >= GATE_MS
    return crossed && this.smartAvailable && this.inFlight === null && !this.anyResult
  }

  private request(): Action {
    this.checkId++
    this.inFlight = this.checkId
    this.inFlightAskedAt = this.silenceMs
    return { type: 'check', checkId: this.checkId, silenceMs: this.silenceMs }
  }

  private end(reason: Reason): Action[] {
    const action: Action = {
      type: 'end',
      reason,
      silenceMs: this.silenceMs,
      scores: [...this.scores],
      longestSilenceMs: Math.max(this.longestSilenceMs, this.silenceMs),
    }
    this.phase = 'idle'
    this.resetSilence()
    this.extendedThisTurn = false
    this.longestSilenceMs = 0
    return [action]
  }

  private resetSilence() {
    this.silenceMs = 0
    this.hold = false
    this.finishAtMs = null
    this.anyResult = false
    this.inFlight = null
    this.speculated = false
  }
}
