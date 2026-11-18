import type { Notice } from './live-client'

/** What the cascade conversation says in words, and the one sound it makes on its own. */

export const CONVERSATION_CLOSED = 'Conversation closed'
export const ENDED_LOCKED = 'Conversation ended when the screen locked.'
export const ENDED_LIMIT = 'The conversation reached its time limit. Start a new one.'
export const UNREACHABLE = "Not answered: couldn't reach the engine. Nothing was changed."

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

/**
 * A refusal from `/turn` that ends the conversation, in the engine's words when it gave some; null
 * for one the conversation carries on past (said as a caption).
 */
export function refusalNotice(status: number, detail: string | null): Notice | null {
  const said = (fallback: string) => detail ?? fallback
  switch (status) {
    case 409:
      return { kind: 'mint-failed', text: said('The assistant could not answer that.') }
    case 429:
      return { kind: 'throttled', text: said('Too much at once. Try again in a little while.') }
    case 503:
      return { kind: 'no-key', text: said("The assistant isn't set up on this server.") }
    case 401:
    case 403:
      return { kind: 'signed-out', text: 'You are signed out, so nothing was asked. Sign in again.' }
    default:
      return null
  }
}
