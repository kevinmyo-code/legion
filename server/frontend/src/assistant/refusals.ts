import { STILL_BUSY } from './cascade-words'
import type { Notice } from './live-client'

/**
 * What the conversation does with a refusal from `POST /api/assistant/turn`. Decided by the engine's
 * `code` (`server/assistant/views.py`); an engine that sends none is read by its status alone.
 */
export type Refusal =
  /** Still busy after the retries (`turn-post.ts`): say so, let the mic go, keep the conversation. */
  | { type: 'wait'; text: string }
  /** The demo conversation's time limit: end it. */
  | { type: 'end'; text: string | null }
  /** Close the conversation and keep the reason on screen. */
  | { type: 'close'; notice: Notice }
  /** A caption (when there are words), and the conversation listens again. */
  | { type: 'listen'; caption: string | null }

export function readRefusal(status: number, detail: string | null, code: string | null, inConversation: boolean): Refusal {
  if (code === 'busy') return { type: 'wait', text: STILL_BUSY }
  if (code === 'no_conversation') {
    // Already posted again as a new conversation (`CascadeClient.turn`); that failed too.
    return { type: 'close', notice: { kind: 'mint-failed', text: detail ?? 'The engine lost this conversation. Tap to start a new one.' } }
  }
  const timeLimit = code !== null ? code === 'time_limit' : status === 409
  if (timeLimit && inConversation) return { type: 'end', text: detail }
  const notice = closingNotice(status, detail)
  if (notice) return { type: 'close', notice }
  return { type: 'listen', caption: detail ?? 'The engine refused that, so nothing was asked.' }
}

/** A refusal that ends the conversation, in the engine's words when it gave some. */
function closingNotice(status: number, detail: string | null): Notice | null {
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
