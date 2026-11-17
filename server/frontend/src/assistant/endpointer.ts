/**
 * End of speech for the cascade (ADR 0102): Live decided it on Google's side; on the cascade the
 * browser does, by an energy threshold over the capture worklet's 20 ms chunks.
 *
 * Tap to start. After the person has spoken, `SILENCE_MS` of chunks below the threshold ends the
 * utterance. Nothing voiced within `NO_SPEECH_MS` of the tap ends it as heard nothing (and nothing
 * is sent). `MAX_UTTERANCE_MS` ends it whatever is happening, matching the engine's own cap
 * (`assistant/views.py` `MAX_UTTERANCE_SECONDS`). A click stops it early at any time
 * (`CascadeClient.stopVoice`).
 *
 * Pure, so it is tested in Node: the worklet is unchanged (Live still streams from it) and this
 * runs on the main thread over the chunks it posts. Each constant is one edit.
 * OPEN RULING (Kevin): the 1.2 s silence window.
 */

/** Silence after speech that ends the utterance. */
export const SILENCE_MS = 1_200
/** No speech at all this long after the tap: stop, send nothing. */
export const NO_SPEECH_MS = 8_000
/** The longest utterance; the engine refuses more. */
export const MAX_UTTERANCE_MS = 15_000
/**
 * RMS of a 16-bit chunk above which it counts as speech: about -40 dBFS, the same line
 * `live-client.ts` `isVoiced` draws. The capture has echo cancellation and noise suppression on.
 */
export const VOICE_RMS = 330
/** Voiced chunks in a row before speech counts as started (60 ms), so a click is not speech. */
export const ONSET_CHUNKS = 3

export type Verdict = 'listening' | 'end' | 'nothing' | 'max'

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
      if (this.run >= ONSET_CHUNKS) this.started = true
    } else {
      this.run = 0
      this.quietMs += ms
    }
    if (this.elapsedMs >= MAX_UTTERANCE_MS) return this.started ? 'max' : 'nothing'
    if (this.started && this.quietMs >= SILENCE_MS) return 'end'
    if (!this.started && this.elapsedMs >= NO_SPEECH_MS) return 'nothing'
    return 'listening'
  }
}
