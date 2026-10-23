import {
  BooksHttpError,
  type BookmarkPut,
  type BookmarkRow,
  type PositionPut,
  type PositionRow,
  type Variant,
} from '@/api/books'

import type { KV } from './kv'

/**
 * Reading sync, the client half of ticket 08's contract: which of two positions
 * wins, what is waiting to be sent, and when a page turn is allowed to be saved.
 * Everything here is pure or runs against the `KV` seam, so the rules are tested
 * without a browser.
 */

export const OFFLINE_SENTENCE = 'Reading progress will sync when the server is reachable.'

export const SYNC_UNREACHABLE_ON_OPEN =
  'Could not reach the server, so the place you were at on your phone may be out of date.'

// ---- newest wins ----------------------------------------------------------

type Stamped = { recorded_at: string }

/** The position that is newer by the READER's clock (`recorded_at`), never the server's. */
export function newestWins<T extends Stamped>(local: T | undefined, remote: T | undefined): 'local' | 'remote' | 'none' {
  if (!local && !remote) return 'none'
  if (!remote) return 'local'
  if (!local) return 'remote'
  // A tie keeps what is on screen already: a jump to the same instant is a pointless jump.
  return Date.parse(remote.recorded_at) > Date.parse(local.recorded_at) ? 'remote' : 'local'
}

export function positionKey(sha256: string, variant: Variant): string {
  return `${sha256}|${variant}`
}

/** Keep the newest of each key when two lists of positions are merged (a pull). */
export function mergePositions(local: PositionRow[], remote: PositionRow[]): PositionRow[] {
  const byKey = new Map<string, PositionRow>()
  for (const row of [...local, ...remote]) {
    const key = positionKey(row.book_sha256, row.variant)
    const held = byKey.get(key)
    if (!held || newestWins(held, row) === 'remote') byKey.set(key, row)
  }
  return [...byKey.values()]
}

// ---- the offline queue ----------------------------------------------------

export type QueuedOp =
  | { kind: 'position'; sha256: string; variant: Variant; body: PositionPut }
  | { kind: 'bookmark-put'; id: string; body: BookmarkPut }
  | { kind: 'bookmark-delete'; id: string }

export interface Sender {
  putPosition(sha256: string, variant: Variant, body: PositionPut, init?: { keepalive?: boolean }): Promise<unknown>
  putBookmark(id: string, body: BookmarkPut): Promise<unknown>
  deleteBookmark(id: string): Promise<unknown>
}

function opKey(op: QueuedOp): string {
  return op.kind === 'position' ? `position|${positionKey(op.sha256, op.variant)}` : `bookmark|${op.id}`
}

export interface FlushResult {
  sent: number
  /** Ops still waiting: the server could not be reached, or answered with a server error. */
  pending: number
  /** Ops the server refused outright (a 4xx): resending cannot help, so they are dropped. */
  dropped: number
}

/**
 * Writes waiting for the server. One entry per thing, so ten page turns while
 * offline are one position write (the latest) and a bookmark added then deleted
 * offline leaves only the delete. Persisted, so a closed tab does not lose them.
 */
export class SyncQueue {
  private readonly store: KV
  constructor(store: KV) {
    this.store = store
  }

  async enqueue(op: QueuedOp): Promise<void> {
    await this.store.set('queue', opKey(op), op)
  }

  async pendingOps(): Promise<QueuedOp[]> {
    return (await this.store.entries<QueuedOp>('queue')).map(([, op]) => op)
  }

  async pendingCount(): Promise<number> {
    return (await this.pendingOps()).length
  }

  async hasPendingBookmark(id: string): Promise<boolean> {
    return (await this.store.get('queue', `bookmark|${id}`)) !== undefined
  }

  async flush(send: Sender, init?: { keepalive?: boolean }): Promise<FlushResult> {
    let sent = 0
    let dropped = 0
    const ops = await this.store.entries<QueuedOp>('queue')
    for (const [key, op] of ops) {
      try {
        if (op.kind === 'position') await send.putPosition(op.sha256, op.variant, op.body, init)
        else if (op.kind === 'bookmark-put') await send.putBookmark(op.id, op.body)
        else await send.deleteBookmark(op.id)
      } catch (err) {
        if (err instanceof BooksHttpError && err.status >= 400 && err.status < 500) {
          await this.store.delete('queue', key)
          dropped += 1
          continue
        }
        // Unreachable or a 5xx: stop here, keep this and everything after it.
        return { sent, dropped, pending: await this.pendingCount() }
      }
      // Remove only if nothing newer replaced it while the request was in flight.
      const now = await this.store.get<QueuedOp>('queue', key)
      if (JSON.stringify(now) === JSON.stringify(op)) await this.store.delete('queue', key)
      sent += 1
    }
    return { sent, dropped, pending: await this.pendingCount() }
  }
}

// ---- when a page turn may be saved ----------------------------------------

export interface Place {
  locator: Record<string, unknown> | null
  totalProgression: number
  chapterLabel: string | null
}

/**
 * Debounced saving of the reader's place.
 *
 * **Disarmed until the first jump is done.** A reader reports its position the
 * moment it loads - page one, or wherever it was told to start - and saving that
 * with `recorded_at = now` would beat the phone's real position under newest-wins
 * the next time anyone synced. The screen calls `arm()` only after the opening
 * jump has settled, so only a turn the person made is ever recorded.
 */
export class PositionRecorder {
  private armed = false
  private pending: Place | undefined
  private timer: ReturnType<typeof setTimeout> | undefined

  private readonly save: (place: Place, recordedAt: string) => void
  private readonly delayMs: number
  private readonly now: () => Date
  private readonly timers: { set: typeof setTimeout; clear: typeof clearTimeout }

  constructor(
    save: (place: Place, recordedAt: string) => void,
    delayMs = 1500,
    now: () => Date = () => new Date(),
    timers: { set: typeof setTimeout; clear: typeof clearTimeout } = {
      set: (...a) => setTimeout(...a),
      clear: (...a) => clearTimeout(...a),
    },
  ) {
    this.save = save
    this.delayMs = delayMs
    this.now = now
    this.timers = timers
  }

  arm() {
    this.armed = true
  }

  /** A person-made turn (key, tap, button): from here on the opening jump is over, whatever the clock says. */
  userTurn() {
    this.armed = true
  }

  /** The reader reported a position. Ignored until armed. */
  moved(place: Place) {
    if (!this.armed) return
    this.pending = place
    // The reader's clock is read when the person turned the page, not when the debounce fires.
    this.stamp = this.now().toISOString()
    if (this.timer !== undefined) this.timers.clear(this.timer)
    this.timer = this.timers.set(() => this.flush(), this.delayMs)
  }

  private stamp: string | undefined

  /** Save now: the tab is hiding or the reader is closing. */
  flush() {
    if (this.timer !== undefined) this.timers.clear(this.timer)
    this.timer = undefined
    const place = this.pending
    const at = this.stamp
    this.pending = undefined
    this.stamp = undefined
    if (place && at) this.save(place, at)
  }
}

// ---- bookmarks ------------------------------------------------------------

/** Server rows into the local copy; a row with a pending local change is left alone until sent. */
export function applyBookmarkPull(
  local: Map<string, BookmarkRow>,
  remote: BookmarkRow[],
  hasPending: (id: string) => boolean,
): Map<string, BookmarkRow> {
  const next = new Map(local)
  for (const row of remote) {
    if (hasPending(row.id)) continue
    if (row.deleted_at) next.delete(row.id)
    else next.set(row.id, row)
  }
  return next
}

/** The next `since` cursor: the newest server `updated_at` seen, never the local clock. */
export function nextCursor(rows: { updated_at: string }[], previous?: string): string | undefined {
  let best = previous
  for (const r of rows) if (!best || Date.parse(r.updated_at) > Date.parse(best)) best = r.updated_at
  return best
}
