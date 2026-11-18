import type { GreetingHistory } from './cascade-client'

/**
 * The greeting's caps (spec 27), the one thing about a conversation kept in the browser: the kinds
 * of topic led with today and the last hellos' words, never the facts. Stored where `store` keeps
 * it; a store that refuses (a private window) falls back to this page's memory.
 */
export class GreetingCaps {
  private memory: string | null = null
  private readonly now: () => number
  private readonly store?: { load(): string | null; save(value: string): void }

  constructor(now: () => number, store?: { load(): string | null; save(value: string): void }) {
    this.now = now
    this.store = store
  }

  history(): GreetingHistory {
    return this.load().history
  }

  /** The greeting the engine just gave: its lead kind, or a hello's words. */
  note(kind: string, text: string) {
    if (!kind || kind === 'scripted') return
    const { day, history } = this.load()
    if (kind === 'hello') {
      history.hellos = [...history.hellos, text].slice(-3)
      history.last = 'hello'
    } else {
      history.today = [...history.today, kind]
      history.last = kind
    }
    const value = JSON.stringify({ day, history })
    this.memory = value
    try {
      this.store?.save(value)
    } catch {
      // Storage refused: kept in memory for this page.
    }
  }

  private load(): { day: string; history: GreetingHistory } {
    const day = new Date(this.now()).toDateString()
    let raw: string | null = this.memory
    try {
      raw = this.store?.load() ?? raw
    } catch {
      // Storage refused (a private window): the page's own memory stands in.
    }
    try {
      const parsed = raw ? (JSON.parse(raw) as { day?: string; history?: GreetingHistory }) : null
      const history = parsed?.history
      if (history && Array.isArray(history.hellos)) {
        return { day, history: { ...history, today: parsed?.day === day ? history.today ?? [] : [] } }
      }
    } catch {
      // Not ours, or not JSON: start clean.
    }
    return { day, history: { today: [], last: null, hellos: [] } }
  }
}
