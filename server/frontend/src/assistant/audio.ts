/**
 * The seam between the conversation and the device's audio.
 *
 * `LiveClient` knows nothing about `AudioContext`, worklets or getUserMedia; it
 * talks to this interface. That is deliberate (web-assistant 08): iOS audio is
 * the risk of this whole feature (`research/01-live-from-an-iphone-pwa.md`,
 * section 4), and if live voice proves unusable on Mia's phone the fix is a
 * different `AudioIO` (record a turn, send it, play the answer) behind the same
 * interface, not a rewrite of the conversation.
 */

/** Why the microphone could not open or stopped, in the vocabulary the screen words. */
export type MicFailure = 'denied' | 'unavailable' | 'lost'

export class AudioError extends Error {
  readonly kind: MicFailure

  constructor(kind: MicFailure, message: string) {
    super(message)
    this.name = 'AudioError'
    this.kind = kind
  }
}

export interface AudioHandlers {
  /** One 20 ms chunk of 16 kHz, 16-bit mono microphone audio. */
  onChunk(pcm: Int16Array): void
  /** The microphone stopped by itself: a call came in, the track ended, the context was interrupted. */
  onMicLost(): void
  /** Playback went from nothing queued to something playing (`true`) or back (`false`). */
  onPlaying(playing: boolean): void
}

export interface AudioIO {
  /**
   * Open the microphone and start capture. **Must be called synchronously from
   * the tap that asked for it**: on an iPhone an `AudioContext` only runs, and a
   * permission prompt only shows, from a user gesture. Rejects with `AudioError`.
   */
  startMic(handlers: AudioHandlers): Promise<void>
  stopMic(): void
  /** Queue 16-bit mono audio at `rate` (Gemini's 24 kHz) behind what is already queued. */
  play(pcm: Int16Array, rate: number): void
  /** Drop everything queued and stop what is sounding (an interruption). */
  flush(): void
  /** Everything: mic, playback, contexts. Safe to call twice. */
  dispose(): void
}
