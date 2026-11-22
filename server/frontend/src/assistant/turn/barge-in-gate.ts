/**
 * Whether the person is talking over the assistant: the phone's `BargeInGate.kt`
 * (app/.../service/turn/), ported line for line as a pure fold over Silero windows (32 ms) heard
 * while the reply plays. Same constants; `barge-in-gate.test.ts` is the Kotlin test, ported.
 *
 * The reply's own echo is speech too, so Silero alone cannot tell it from the person. A window counts
 * only when Silero is confident (PROB) AND its peak is above the echo floor learned from THIS reply
 * (ECHO_MARGIN x the ECHO_PERCENTILEth percentile of the reply's windows so far; until LEARN_WINDOWS
 * have been heard, the floor the previous reply learned, or DEFAULT_ECHO_FLOOR when none has). It
 * fires when SUSTAIN_WINDOWS such windows fall within the last SPAN_WINDOWS (512 ms), so a short
 * backchannel never stops the reply. After a fire it watches VERDICT_MS: a self-interrupt is too
 * little speech after the flush AND a fire that was never CLEARLY_USER_FACTOR x its threshold loud.
 * MAX_SELF_INTERRUPTS of those turn barge-in off for the rest of the conversation.
 *
 * Levels are peak absolute sample values of the window (of 32,767), as on the phone. The browser's
 * echo cancellation sits in front, so the learned floor is usually far below the phone's call-mode
 * DEFAULT_ECHO_FLOOR; the default is the phone's, and a reply learns its own within 320 ms.
 *
 * **Echo warm-up (ADR 0064 amendment 2026-11-21).** The echo canceller needs a few seconds of
 * playback to learn the room each time the audio path is new. `startWarmUp` (the caller's, at the
 * first reply audio after the mic opens or the default device moves) opens WARMUP_WINDOWS windows,
 * counted only while reply audio plays. Inside them a span that would fire is reported as `ignored`
 * instead: nothing is flushed, no verdict is watched, nothing counts, and the span starts over.
 *
 * **The web, 2026-11-22.** Voice barge-in was off on the web from 11-17 to 11-22 after the web's own
 * "pause, then decide" failed the headless-Chrome echo runs. That path is gone: a fire flushes the
 * reply at once, as on the phone, and the warm-up above guards the opening. Two web-only rules stay
 * (`shared/turn/spec.json` web_only): the page may count a self-interrupt the engine found (an empty
 * transcript, or one echoing the reply), once per fire; and VERDICT_GRACE_MS, below.
 */

export const WINDOW_MS = 32
export const PROB = 0.8
export const SPEECH_PROB = 0.5
export const SUSTAIN_MS = 256
export const SUSTAIN_WINDOWS = SUSTAIN_MS / WINDOW_MS
export const SPAN_WINDOWS = 16
const SPAN_MASK = (1 << SPAN_WINDOWS) - 1
export const ECHO_MARGIN = 1.5
export const ECHO_PERCENTILE = 90
export const DEFAULT_ECHO_FLOOR = 12_000
export const MIN_LEVEL = 3_000
export const LEARN_WINDOWS = 10
export const USER_GUARD_MS = 1_000
export const USER_GUARD_WINDOWS = Math.floor(USER_GUARD_MS / WINDOW_MS)
const MAX_LEARN = 2_000
export const VERDICT_MS = 1_216
export const SELF_SPEECH_MS = 160
/**
 * The web's addition: speech in the verdict's first this-many ms is not counted. What already left
 * the speaker when the reply was flushed is still on its way back to the mic (the browser's output
 * latency, the room); counted, a fire on the reply's own echo read as a person, and with an engine
 * that answered the echo as words the cut-off never came (`echo.test.ts`, no canceller).
 */
export const VERDICT_GRACE_MS = 320
export const MAX_SELF_INTERRUPTS = 2
export const CLEARLY_USER_FACTOR = 2
/** How long a would-be fire is ignored from the first reply audio on a new audio path. */
export const WARMUP_MS = 3_000
/** WARMUP_MS in windows, rounded up. */
export const WARMUP_WINDOWS = Math.ceil(WARMUP_MS / WINDOW_MS)

export type GateEvent =
  | { type: 'fire'; prob: number; level: number; echoFloor: number; threshold: number; sustainMs: number }
  | {
      type: 'verdict'
      selfInterrupt: boolean
      speechMsAfter: number
      fireLevel: number
      fireThreshold: number
      selfInterrupts: number
      nowDisabled: boolean
    }
  /** A span that would have fired, dropped for `reason`; the span starts over. */
  | { type: 'ignored'; reason: 'warmup'; level: number; threshold: number }

export interface ReplyStats {
  fired: boolean
  windows: number
  speechWindows: number
  loudWindows: number
  qualifyingWindows: number
  maxProb: number
  maxLevel: number
  longestRunMs: number
  bestSpanWindows: number
  echoFloor: number
  threshold: number
}

function bitCount(n: number): number {
  let c = 0
  while (n) {
    c += n & 1
    n >>>= 1
  }
  return c
}

export class BargeInGate {
  disabled = false
  selfInterrupts = 0
  private levels = new Int32Array(MAX_LEARN)
  private learned = 0
  private seedFloor: number | null = null
  private userGuardLeft = 0
  private span = 0
  private spanProb = new Float32Array(SPAN_WINDOWS)
  private spanLevel = new Int32Array(SPAN_WINDOWS)
  private spanPos = 0
  private runMs = 0
  private armed = false
  private verdictWindowsLeft = 0
  private speechAfterMs = 0
  /** The part of it after VERDICT_GRACE_MS, which is what the verdict goes on. */
  private speechAfterGraceMs = 0
  private fireLevel = 0
  private fireThreshold = 0
  private replyOpen = false
  private fired = false
  private windows = 0
  private speechWindows = 0
  private loudWindows = 0
  private qualifyingWindows = 0
  private replyMaxProb = 0
  private replyMaxLevel = 0
  private longestRunMs = 0
  private bestSpanWindows = 0
  /** The last barge-in already counted as a self-interrupt (by the verdict or by the page). */
  private fireCounted = false

  /** Windows left in which a would-be fire is ignored (`startWarmUp`). */
  private warmUpLeft = 0

  /** The echo canceller is still settling (`startWarmUp`). */
  get warmingUpNow(): boolean {
    return this.warmUpLeft > 0
  }

  /** Reply audio just started on a new audio path: ignore fires for the next WARMUP_MS. */
  startWarmUp() {
    this.warmUpLeft = WARMUP_WINDOWS
  }

  get armedNow(): boolean {
    return this.armed
  }

  /** A new conversation: barge-in is back on, with no self-interrupts counted. */
  newConversation() {
    this.disabled = false
    this.selfInterrupts = 0
    this.verdictWindowsLeft = 0
    this.fireCounted = false
  }

  /** Stop listening for good in this conversation (the web's own half-duplex switch). */
  disable() {
    this.disabled = true
    this.armed = false
  }

  onReplyStart() {
    this.learned = 0
    this.resetRun()
    this.armed = !this.disabled
    this.replyOpen = true
    this.fired = false
    // At most one barge-in per reply: a new reply is a new one to count.
    this.fireCounted = false
    this.windows = 0
    this.speechWindows = 0
    this.loudWindows = 0
    this.qualifyingWindows = 0
    this.replyMaxProb = 0
    this.replyMaxLevel = 0
    this.longestRunMs = 0
    this.bestSpanWindows = 0
  }

  onReplyEnd(): ReplyStats | null {
    this.armed = false
    this.resetRun()
    if (!this.replyOpen) return null
    this.replyOpen = false
    if (this.learned >= LEARN_WINDOWS) this.seedFloor = this.percentile()
    return {
      fired: this.fired,
      windows: this.windows,
      speechWindows: this.speechWindows,
      loudWindows: this.loudWindows,
      qualifyingWindows: this.qualifyingWindows,
      maxProb: this.replyMaxProb,
      maxLevel: this.replyMaxLevel,
      longestRunMs: this.longestRunMs,
      bestSpanWindows: this.bestSpanWindows,
      echoFloor: this.echoFloor(),
      threshold: this.threshold(),
    }
  }

  onUserTurnEnd() {
    this.userGuardLeft = USER_GUARD_WINDOWS
  }

  echoFloor(): number {
    return this.learned < LEARN_WINDOWS ? (this.seedFloor ?? DEFAULT_ECHO_FLOOR) : this.percentile()
  }

  threshold(): number {
    return Math.max(MIN_LEVEL, Math.trunc(this.echoFloor() * ECHO_MARGIN))
  }

  onWindow(prob: number, level: number, playing: boolean): GateEvent | null {
    if (this.userGuardLeft > 0) this.userGuardLeft--
    const warmingUp = this.warmUpLeft > 0
    // Only playback trains the canceller: silence between replies must not use the window up.
    if (warmingUp && playing) this.warmUpLeft--
    if (this.verdictWindowsLeft > 0) return this.watch(prob)
    if (!this.armed || !playing || this.disabled) return null
    return this.listen(prob, level, warmingUp)
  }

  private listen(prob: number, level: number, warmingUp: boolean): GateEvent | null {
    const threshold = this.threshold()
    const qualifies = prob >= PROB && level >= threshold
    this.count(prob, level, threshold, qualifies)
    this.span = ((this.span << 1) | (qualifies ? 1 : 0)) & SPAN_MASK
    this.spanProb[this.spanPos] = prob
    this.spanLevel[this.spanPos] = level
    this.spanPos = (this.spanPos + 1) % SPAN_WINDOWS
    if (qualifies) {
      this.runMs += WINDOW_MS
      if (this.runMs > this.longestRunMs) this.longestRunMs = this.runMs
    } else {
      if (this.userGuardLeft === 0 || prob < SPEECH_PROB) this.learn(level)
      this.runMs = 0
    }
    const inSpan = bitCount(this.span)
    if (inSpan > this.bestSpanWindows) this.bestSpanWindows = inSpan
    if (inSpan < SUSTAIN_WINDOWS) return null
    let maxProb = 0
    let maxLevel = 0
    for (let ago = 0; ago < SPAN_WINDOWS; ago++) {
      if ((this.span & (1 << ago)) === 0) continue
      const i = (this.spanPos - 1 - ago + SPAN_WINDOWS) % SPAN_WINDOWS
      if (this.spanProb[i] > maxProb) maxProb = this.spanProb[i]
      if (this.spanLevel[i] > maxLevel) maxLevel = this.spanLevel[i]
    }
    if (warmingUp) {
      this.resetRun()
      return { type: 'ignored', reason: 'warmup', level: maxLevel, threshold }
    }
    const fire: GateEvent = {
      type: 'fire',
      prob: maxProb,
      level: maxLevel,
      echoFloor: this.echoFloor(),
      threshold,
      sustainMs: inSpan * WINDOW_MS,
    }
    this.fireLevel = maxLevel
    this.fireThreshold = threshold
    this.fired = true
    this.fireCounted = false
    this.armed = false
    this.resetRun()
    this.verdictWindowsLeft = Math.floor(VERDICT_MS / WINDOW_MS)
    this.speechAfterMs = 0
    this.speechAfterGraceMs = 0
    this.userGuardLeft = USER_GUARD_WINDOWS
    return fire
  }

  private count(prob: number, level: number, threshold: number, qualifies: boolean) {
    this.windows++
    if (prob >= PROB) this.speechWindows++
    if (level >= threshold) this.loudWindows++
    if (qualifies) this.qualifyingWindows++
    if (prob > this.replyMaxProb) this.replyMaxProb = prob
    if (level > this.replyMaxLevel) this.replyMaxLevel = level
  }

  private watch(prob: number): GateEvent | null {
    const watchedMs = (Math.floor(VERDICT_MS / WINDOW_MS) - this.verdictWindowsLeft) * WINDOW_MS
    if (prob >= SPEECH_PROB) {
      this.speechAfterMs += WINDOW_MS
      if (watchedMs >= VERDICT_GRACE_MS) this.speechAfterGraceMs += WINDOW_MS
      this.userGuardLeft = USER_GUARD_WINDOWS
    }
    if (--this.verdictWindowsLeft > 0) return null
    const clearlyUser = this.fireLevel >= this.fireThreshold * CLEARLY_USER_FACTOR
    const self = this.speechAfterGraceMs < SELF_SPEECH_MS && !clearlyUser
    if (self && !this.fireCounted) {
      this.fireCounted = true
      this.selfInterrupts++
    }
    if (this.selfInterrupts >= MAX_SELF_INTERRUPTS) this.disabled = true
    return {
      type: 'verdict',
      selfInterrupt: self,
      speechMsAfter: this.speechAfterMs,
      fireLevel: this.fireLevel,
      fireThreshold: this.fireThreshold,
      selfInterrupts: this.selfInterrupts,
      nowDisabled: this.disabled,
    }
  }

  /**
   * A self-interrupt the page found itself (an empty transcript, or one echoing the reply). Each
   * barge-in counts once, whichever of this and the gate's own verdict comes first.
   */
  countSelfInterrupt(): boolean {
    if (this.fireCounted) return this.disabled
    this.fireCounted = true
    this.selfInterrupts++
    if (this.selfInterrupts >= MAX_SELF_INTERRUPTS) this.disable()
    return this.disabled
  }

  private learn(level: number) {
    if (this.learned < MAX_LEARN) this.levels[this.learned++] = level
  }

  private percentile(): number {
    const s = Array.from(this.levels.subarray(0, this.learned)).sort((a, b) => a - b)
    const idx = Math.trunc((ECHO_PERCENTILE / 100) * (s.length - 1))
    return s[idx]
  }

  private resetRun() {
    this.span = 0
    this.runMs = 0
  }
}

/** Peak absolute sample of a window, the phone's level. */
export function peakLevel(window: Int16Array | Float32Array): number {
  let peak = 0
  const scale = window instanceof Float32Array ? 32767 : 1
  for (let i = 0; i < window.length; i++) {
    const v = Math.abs(window[i] * scale)
    if (v > peak) peak = v
  }
  return Math.min(32767, Math.round(peak))
}
