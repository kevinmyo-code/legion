import { MAX_UTTERANCE_MS } from './endpointer'

/** The engine refuses an upload over this many 16 kHz samples (413, `views.py` MAX_AUDIO_BYTES). */
export const MAX_UTTERANCE_SAMPLES = (MAX_UTTERANCE_MS / 1000) * 16_000

/**
 * One utterance as the engine takes it: the chunks joined, never over its cap. Over the cap the
 * START is dropped and the end kept. The cap fires 28 s after speech began, so what is over is
 * the pre-roll kept ahead of the first word, and the end holds the words just said; a turn the
 * models ended near the cap loses its oldest audio, never its last words.
 */
export function utterance(parts: Int16Array | Int16Array[]): Int16Array {
  const chunks = Array.isArray(parts) ? parts : [parts]
  const total = chunks.reduce((n, c) => n + c.length, 0)
  const joined = chunks.length === 1 ? chunks[0] : new Int16Array(total)
  if (chunks.length !== 1) {
    let at = 0
    for (const chunk of chunks) {
      joined.set(chunk, at)
      at += chunk.length
    }
  }
  return joined.length > MAX_UTTERANCE_SAMPLES ? joined.subarray(joined.length - MAX_UTTERANCE_SAMPLES) : joined
}
