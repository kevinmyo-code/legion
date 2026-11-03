/**
 * The browser's half of a conversation's record on the engine (Kevin, 2026-11-03: conversations
 * "may be recorded ... to improve" the assistant; `server/devicelogs/web_conversation.py`).
 *
 * The engine opens the record when it mints the conversation and writes every tool call into it
 * itself. What only the browser hears goes here: the person's words and the assistant's (the
 * transcriptions, or the typed text), turn ends, interruptions, cancelled tool calls, mic changes,
 * the end, and two timings. **No audio, ever.** Events, not log lines: the engine writes the lines.
 *
 * Sent after each turn and at the end (`flush`), and when the page is going away (`beacon`, which
 * survives the page closing). Only events not yet stored are sent; the engine stores a repeat once,
 * so a retry after a lost answer costs nothing.
 */

export type RecordEvent =
  | { kind: 'user'; at_ms: number; text: string; typed: boolean }
  | { kind: 'assistant'; at_ms: number; text: string }
  | { kind: 'interrupted'; at_ms: number; reason: 'server' | 'barge_in' }
  | { kind: 'turn_complete'; at_ms: number }
  | { kind: 'tool_cancelled'; at_ms: number; id: string; name: string }
  | { kind: 'mic_open' | 'mic_closed'; at_ms: number; reason: string }
  | { kind: 'session_end'; at_ms: number; reason: string }
  | { kind: 'latency'; at_ms: number; metric: 'cold_start' | 'reply'; ms: number }

/** How the events reach the engine; `deps.ts` has the real one, tests a fake. */
export interface RecordTransport {
  /** Resolves true when the engine stored them (or already had them). */
  upload(conversationId: string, events: RecordEvent[]): Promise<boolean>
  /** Fire and forget while the page closes. True when the browser queued it. */
  beacon(conversationId: string, events: RecordEvent[]): boolean
}

/** The engine takes at most 500 events per upload. */
export const MAX_EVENTS_PER_UPLOAD = 200

export class ConversationRecord {
  private readonly events: RecordEvent[] = []
  private sent = 0
  private running: Promise<void> | null = null
  private again = false
  /** The engine's id for this conversation; null until the mint answers. Nothing is sent before it. */
  conversationId: string | null = null

  private readonly transport: RecordTransport

  constructor(transport: RecordTransport) {
    this.transport = transport
  }

  add(event: RecordEvent) {
    this.events.push(event)
  }

  /** Events not yet stored. */
  pending(): readonly RecordEvent[] {
    return this.events.slice(this.sent)
  }

  /** Send what is not yet stored. One upload at a time; a call during one runs again after it. */
  flush(): Promise<void> {
    if (this.running) {
      this.again = true
      return this.running
    }
    this.running = this.drain().finally(() => {
      this.running = null
    })
    return this.running
  }

  private async drain() {
    do {
      this.again = false
      const id = this.conversationId
      if (!id) return
      while (this.sent < this.events.length) {
        const upTo = Math.min(this.events.length, this.sent + MAX_EVENTS_PER_UPLOAD)
        let ok = false
        try {
          ok = await this.transport.upload(id, this.events.slice(this.sent, upTo))
        } catch {
          ok = false
        }
        if (!ok) return // kept; the next flush or the beacon sends them
        this.sent = upTo
      }
    } while (this.again)
  }

  /** The page is going away: hand what is not yet stored to the browser to deliver. */
  beacon() {
    const id = this.conversationId
    if (!id || this.sent >= this.events.length) return
    const tail = this.events.slice(this.sent, this.sent + MAX_EVENTS_PER_UPLOAD)
    if (this.transport.beacon(id, tail)) this.sent += tail.length
  }
}
