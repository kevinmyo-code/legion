/**
 * How long the page waits on the engine for one turn before it stops waiting (review 2026-11-17:
 * a stalled stream left "thinking" with the mic live until a tap). The engine's own wall clock for a
 * turn is 60 s (`server/assistant/cascade.py` TURN_WALL_S); the page's is above it so the engine
 * always gets to say its own "took too long" first. The first event (`turn`) is sent before any
 * model runs, so its wait only covers the network and the engine taking the request.
 */
/** The demo entry (the visit, the engine question) inside the opening tap: past this, give up. */
export const ENTRY_MS = 12_000
/** Taps in the entry's first seconds are one double tap; a tap after this gives up on it. */
export const ENTRY_TAP_MS = 2_000
export const FIRST_EVENT_MS = 20_000
export const TURN_MS = 75_000

export class TurnDeadline {
  private first: ReturnType<typeof setTimeout> | null
  private whole: ReturnType<typeof setTimeout> | null

  constructor(expired: () => void) {
    this.first = setTimeout(expired, FIRST_EVENT_MS)
    this.whole = setTimeout(expired, TURN_MS)
  }

  /** The engine sent something: the first-event wait is over. */
  heard() {
    if (this.first) clearTimeout(this.first)
    this.first = null
  }

  clear() {
    this.heard()
    if (this.whole) clearTimeout(this.whole)
    this.whole = null
  }
}
