/**
 * The microphone side of the assistant, running on the audio render thread.
 *
 * Bundled by Vite as its own script (`?worker&url` in `web-audio.ts`) and loaded
 * with `audioWorklet.addModule`. It does one job: take the context's float
 * frames (an iPhone context runs at 48 kHz), resample to 16 kHz, convert to
 * 16-bit PCM and post 20 ms chunks to the main thread, which base64s and sends
 * them. Kept this small on purpose so the audio path can be swapped for a
 * record-per-turn fallback without touching the conversation logic.
 */
import { INPUT_RATE, Resampler, floatToPcm16 } from './pcm'

declare const sampleRate: number
declare class AudioWorkletProcessor {
  readonly port: MessagePort
  constructor()
}
declare function registerProcessor(name: string, ctor: new () => AudioWorkletProcessor): void

/** 20 ms at 16 kHz: the lower end of Google's "send 20-40 ms chunks" guidance. */
const CHUNK = 320

class CaptureProcessor extends AudioWorkletProcessor {
  private readonly resampler = new Resampler(sampleRate, INPUT_RATE)
  private pending = new Float32Array(0)

  process(inputs: Float32Array[][]): boolean {
    const channel = inputs[0]?.[0]
    if (channel && channel.length > 0) {
      const resampled = this.resampler.process(channel)
      const merged = new Float32Array(this.pending.length + resampled.length)
      merged.set(this.pending)
      merged.set(resampled, this.pending.length)
      let offset = 0
      while (merged.length - offset >= CHUNK) {
        const pcm = floatToPcm16(merged.subarray(offset, offset + CHUNK))
        this.port.postMessage(pcm, [pcm.buffer])
        offset += CHUNK
      }
      this.pending = merged.slice(offset)
    }
    return true
  }
}

registerProcessor('legion-capture', CaptureProcessor)
