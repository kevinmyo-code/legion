/**
 * When the person's turn starts and ends: the phone's `EndOfTurnPolicy.kt` (app/.../service/turn/),
 * ported line for line as a pure fold over Silero windows (32 ms each) and Smart Turn results.
 * Same constants. `eot-policy.test.ts` is the Kotlin test, ported.
 *
 * Smart Turn can only EXTEND a turn, never end one early (Kevin, 2026-10-27):
 * - Speech starts after CONFIRM_MS of Silero speech (start at >= START_PROB, held while >= END_PROB).
 * - Silence is time since the last window >= END_PROB; speech resumes at >= START_PROB and cancels
 *   everything pending.
 * - At GATE_MS of silence one Smart Turn check starts on the whole turn.
 * - At BASE_MS of silence the turn ends (base) unless a check already said "not done".
 * - A result below THRESHOLD that arrives BEFORE BASE_MS holds the turn open; checks then run back to
 *   back and the turn ends at the first scoring >= THRESHOLD (extended), or at CAP_MS (cap).
 * - Without a working Smart Turn it is silence only (fallback).
 *
 * The web adds one thing the phone's cascade does elsewhere (`Speculation.kt`): an `early` action at
 * EARLY_MS of silence, when the browser may start transcribing what it has (spec item 33 is about
 * drafts; this is only the transcript, and it is thrown away if speech resumes).
 */

export const WINDOW_MS = 32
export const START_PROB = 0.5
export const END_PROB = 0.35
export const CONFIRM_MS = 200
export const GATE_MS = 192
export const BASE_MS = 700
/**
 * The web's early end (Kevin, 2026-11-19: "700 -> 500"; review F2): at this much silence the turn ends
 * ONLY when Smart Turn has already said the person is finished. With no verdict yet, or no Smart Turn
 * at all, the turn ends at BASE_MS (700) as before, so a slow device or a pause never loses time it
 * had. The phone passes no early end.
 */
export const WEB_FINISHED_MS = 500
export const CAP_MS = 2_500
export const THRESHOLD = 0.5
/** The phone's speculation point: transcribe what was said once this much silence has passed. */
export const EARLY_MS = 300

export type Reason = 'base' | 'finished' | 'extended' | 'cap' | 'fallback'

export interface Score {
  score: number
  askedAtMs: number
  arrivedAtMs: number
}

export type Action =
  | { type: 'start'; onsetWindows: number }
  | { type: 'check'; checkId: number; silenceMs: number }
  | { type: 'early'; silenceMs: number }
  | { type: 'end'; reason: Reason; silenceMs: number; scores: Score[]; longestSilenceMs: number }
  | { type: 'resumed'; afterSilenceMs: number }

type Phase = 'idle' | 'candidate' | 'speaking' | 'silence'

export class EndOfTurnPolicy {
  smartAvailable: boolean
  private readonly baseMs: number
  private phase: Phase = 'idle'
  private candidateWindows = 0
  private silenceMs = 0
  private hold = false
  private anyResult = false
  private extendedThisTurn = false
  private longestSilenceMs = 0
  private checkId = 0
  private inFlight: number | null = null
  private inFlightAskedAt = 0
  private scores: Score[] = []
  private earlySent = false
  private readonly finishedMs: number | null
  /** Smart Turn said "finished" in this silence (and nothing has held the turn since). */
  private finished = false

  constructor(smartAvailable: boolean, baseMs: number = BASE_MS, finishedMs: number | null = null) {
    this.smartAvailable = smartAvailable
    this.baseMs = baseMs
    this.finishedMs = finishedMs
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
    if (first && !done && this.silenceMs < this.baseMs) {
      this.hold = true
      return [this.request()]
    }
    if (done && this.silenceMs < this.baseMs && (first || this.hold)) {
      // Finished before the base: the early end may apply (now, if its time has passed).
      this.hold = false
      this.finished = true
      if (this.finishedMs !== null && this.silenceMs >= this.finishedMs) return this.end('finished')
      return []
    }
    if (!this.hold) return []
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
    if (!this.earlySent && before < EARLY_MS && this.silenceMs >= EARLY_MS) {
      this.earlySent = true
      out.push({ type: 'early', silenceMs: this.silenceMs })
    }
    if (this.silenceMs >= CAP_MS) out.push(...this.end('cap'))
    else if (this.finished && !this.hold && this.finishedMs !== null && this.silenceMs >= this.finishedMs && !this.extendedThisTurn) {
      out.push(...this.end('finished'))
    } else if (this.silenceMs >= this.baseMs && !this.hold) {
      out.push(...this.end(this.extendedThisTurn ? 'extended' : this.smartAvailable ? 'base' : 'fallback'))
    }
    return out
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
    this.anyResult = false
    this.inFlight = null
    this.earlySent = false
    this.finished = false
  }
}
