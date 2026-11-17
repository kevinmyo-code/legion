/**
 * Listening for one utterance on the cascade (ADR 0102), by an energy threshold over the capture
 * worklet's 20 ms chunks. The phone decides end of turn with Silero and Smart Turn; the browser has
 * neither, so this is the voice-behaviour spec's adaptation (item 12), with the phone's own numbers
 * where they carry over (items 13 to 15).
 *
 * - The follow-up window (spec 14): once listening starts, `WINDOW_MS` of no speech closes the
 *   conversation. Speech cancels it.
 * - Its extension (spec 15): if something loud was heard within `LATE_LOUD_MS` of the deadline, the
 *   window runs again, at most `MAX_EXTENSIONS` times.
 * - End of the utterance (spec 12, adapted): `SILENCE_MS` of quiet after speech.
 * - The utterance cap (spec 13): `MAX_UTTERANCE_MS`, matching the engine's own.
 *
 * Pure, so it is tested in Node: the worklet is unchanged (Live still streams from it) and this
 * runs on the main thread over the chunks it posts. Each constant is one edit.
 * OPEN RULING (Kevin): the 1.2 s silence window (`SILENCE_MS`).
 */

/** Silence after speech that ends the utterance (spec 12, the open ruling). */
export const SILENCE_MS = 1_200
/** The phone's follow-up window: this long with no speech closes the conversation (spec 14). */
export const WINDOW_MS = 8_000
/** Loud within this long of the window's end runs the window again (spec 15). */
export const LATE_LOUD_MS = 1_500
/** At most this many extra windows (spec 15). */
export const MAX_EXTENSIONS = 2
/** The longest utterance; the engine refuses more (spec 13, the phone's 28 s). */
export const MAX_UTTERANCE_MS = 28_000
/**
 * RMS of a 16-bit chunk above which it counts as speech: about -40 dBFS, the same line
 * `live-client.ts` `isVoiced` draws and the engine's loudness gate (spec 3).
 */
export const VOICE_RMS = 330
/** Voiced chunks in a row before speech counts as started (60 ms), so a click is not speech. */
export const ONSET_CHUNKS = 3

/** listening: keep going. end / max: send the utterance. lapsed: the window closed with no speech. */
export type Verdict = 'listening' | 'end' | 'lapsed' | 'max'

export function rms(pcm: Int16Array): number {
  if (pcm.length === 0) return 0
  let sum = 0
  for (let i = 0; i < pcm.length; i++) sum += pcm[i] * pcm[i]
  return Math.sqrt(sum / pcm.length)
}

export class Endpointer {
  private elapsedMs = 0
  private quietMs = 0
  private run = 0
  private started = false
  private windowStart = 0
  private lastLoudMs = -Infinity
  private extensions = 0
  private speechStartMs = 0

  /** Whether speech has started: an utterance without it is not sent. */
  get heardSpeech(): boolean {
    return this.started
  }

  /** One chunk at 16 kHz. Returns what to do now. */
  push(pcm: Int16Array): Verdict {
    const ms = (pcm.length / 16_000) * 1000
    this.elapsedMs += ms
    const voiced = rms(pcm) > VOICE_RMS
    if (voiced) {
      this.run += 1
      this.quietMs = 0
      this.lastLoudMs = this.elapsedMs
      if (this.run >= ONSET_CHUNKS && !this.started) {
        this.started = true
        this.speechStartMs = this.elapsedMs - this.run * ms
      }
    } else {
      this.run = 0
      this.quietMs += ms
    }
    if (this.started) {
      if (this.elapsedMs - this.speechStartMs >= MAX_UTTERANCE_MS) return 'max'
      if (this.quietMs >= SILENCE_MS) return 'end'
      return 'listening'
    }
    const deadline = this.windowStart + WINDOW_MS
    if (this.elapsedMs >= deadline) {
      if (this.lastLoudMs >= deadline - LATE_LOUD_MS && this.extensions < MAX_EXTENSIONS) {
        this.extensions += 1
        this.windowStart = this.elapsedMs
        return 'listening'
      }
      return 'lapsed'
    }
    return 'listening'
  }
}
