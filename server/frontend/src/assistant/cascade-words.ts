import type { Notice } from './live-client'

/** What the cascade conversation says in words, and the one sound it makes on its own. */

export const CONVERSATION_CLOSED = 'Conversation closed'
export const ENDED_LOCKED = 'Conversation ended when the screen locked.'
export const ENDED_LIMIT = 'The conversation reached its time limit. Start a new one.'
export const UNREACHABLE = "Not answered: couldn't reach the engine. Nothing was changed."

/** The engine still had the last turn after the retries (`turn-post.ts`). */
export const STILL_BUSY = 'Still finishing the last answer. Tap to try again.'

/** The demo entry past its deadline, or given up by a tap (`turn-deadline.ts` ENTRY_MS). */
export const ENTRY_STALLED: Notice = {
  kind: 'engine-unreachable',
  text: 'Opening the demo is taking too long. Tap to try again.',
}

/** The demo's turn cap still full after the retries (`turn-post.ts`). */
export const DEMO_FULL = 'The demo is busy right now. Tap to try again.'

/** The engine stopped speaking this answer (`speech_stopped`); its text still comes. */
export const SPEECH_STOPPED = 'The rest is on screen.'

/** A turn past its deadline (`turn-deadline.ts`). A tool may have run, so it claims nothing either way. */
export const TURN_STALLED: Notice = {
  kind: 'engine-unreachable',
  text: 'The engine took too long to answer, so the page stopped waiting. Tap to start again.',
}

export const TONE_RATE = 24_000

/**
 * The phone's closing chime, note for note (`app/.../service/ListenCue.kt` `closeChimePcm`, its
 * `CLOSE_*` constants and `EARCON_GAIN`). Kevin, 2026-11-24: the demo's voice behavior is the
 * phone's, so this is the phone's sound and not an approximation of it.
 *
 * A falling fifth, F#5 then B4, the second note longer and softer, about 290 ms in all, with
 * raised-cosine edges so it never clicks. It was a single 150 ms 880 Hz tone at half gain here,
 * which was neither the phone's pitch, length nor shape. `cascade-words.test.ts` reads the Kotlin
 * for the five numbers, as the engine's wait lines do for theirs.
 */
const CLOSE_HIGH_HZ = 740
const CLOSE_HIGH_SEC = 0.1
const CLOSE_GAP_SEC = 0.04
const CLOSE_LOW_HZ = 494
const CLOSE_LOW_SEC = 0.15
const CLOSE_EDGE_SEC = 0.02
const EARCON_GAIN = 0.25

/** The phone's closing chime as 16-bit PCM at `TONE_RATE`. */
export function closingTone(): Int16Array {
  const notes: Array<[number, number]> = [
    [CLOSE_HIGH_HZ, CLOSE_HIGH_SEC],
    [0, CLOSE_GAP_SEC],
    [CLOSE_LOW_HZ, CLOSE_LOW_SEC],
  ]
  const edge = Math.trunc(CLOSE_EDGE_SEC * TONE_RATE)
  const out = new Int16Array(notes.reduce((n, [, secs]) => n + Math.trunc(secs * TONE_RATE), 0))
  let at = 0
  for (const [freq, secs] of notes) {
    const n = Math.trunc(secs * TONE_RATE)
    for (let i = 0; i < n; i++) {
      // The phone's envelope: a raised cosine over `edge` samples at each end.
      const ramp = Math.min(1, Math.min(i, n - 1 - i) / edge)
      const env = 0.5 - 0.5 * Math.cos(Math.PI * ramp)
      const v = freq === 0 ? 0 : Math.sin((2 * Math.PI * freq * i) / TONE_RATE) * env * EARCON_GAIN
      out[at + i] = Math.trunc(v * 0x7fff)
    }
    at += n
  }
  return out
}

export function micNotice(kind: 'denied' | 'unavailable' | 'lost'): Notice {
  switch (kind) {
    case 'denied':
      return {
        kind: 'mic-denied',
        text: 'The microphone is blocked for this app. To talk, allow the microphone for LEGION in your device or browser settings, then try again. Typing still works.',
      }
    case 'lost':
      return {
        kind: 'mic-lost',
        text: 'The microphone stopped, so nobody is listening. Tap the mic to talk again. Typing still works.',
      }
    default:
      return { kind: 'mic-unavailable', text: "A microphone couldn't be opened on this device. Typing still works." }
  }
}
