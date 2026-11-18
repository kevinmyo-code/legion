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

/** The phone's closing tone (spec 17): 150 ms, half gain. */
const TONE_MS = 150
const TONE_HZ = 880
export const TONE_RATE = 24_000

/** The phone's closing tone as 16-bit PCM. */
export function closingTone(): Int16Array {
  const n = Math.round((TONE_RATE * TONE_MS) / 1000)
  const out = new Int16Array(n)
  for (let i = 0; i < n; i++) {
    const fade = Math.min(1, i / 240, (n - i) / 240)
    out[i] = Math.round(0.5 * fade * 0x7fff * Math.sin((2 * Math.PI * TONE_HZ * i) / TONE_RATE))
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
