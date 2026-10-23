import type { BookmarkRow, LocatorJson, PositionRow, Variant } from '@/api/books'

import type { KV } from './kv'
import {
  SyncQueue,
  applyBookmarkPull,
  newestWins,
  nextCursor,
  positionKey,
  type Place,
  type Sender,
} from './sync'

/**
 * The Books page's whole relationship with the engine and with local storage, in
 * one class: where was I, save where I am, bookmarks. The UI never touches the
 * queue or the cursor itself.
 *
 * Local rows are THIS device's own saves. A remote row is compared with them and
 * then dropped, never copied into local storage, so "which of the two is newer"
 * always compares what this browser did with what the server holds.
 */

export const DEVICE = 'web'

export interface Remote {
  listPositions(since?: string): Promise<PositionRow[]>
  listBookmarks(since?: string): Promise<BookmarkRow[]>
}

export type StartDecision =
  | { from: 'remote'; row: PositionRow }
  | { from: 'local'; row: PositionRow }
  | { from: 'none' }

export class ReadingSync {
  readonly queue: SyncQueue
  private readonly store: KV
  private readonly remote: Remote
  private readonly sender: Sender
  private readonly now: () => Date

  constructor(store: KV, remote: Remote, sender: Sender, now: () => Date = () => new Date()) {
    this.store = store
    this.remote = remote
    this.sender = sender
    this.now = now
    this.queue = new SyncQueue(store)
  }

  // ---- positions ----------------------------------------------------------

  async localPosition(sha256: string, variant: Variant): Promise<PositionRow | undefined> {
    return this.store.get<PositionRow>('positions', positionKey(sha256, variant))
  }

  /**
   * Where to open. Asks the server for the caller's positions; if it cannot be
   * reached, `reachable` is false and the local row decides alone.
   */
  async decideStart(
    sha256: string,
    variant: Variant,
  ): Promise<{ decision: StartDecision; reachable: boolean }> {
    const local = await this.localPosition(sha256, variant)
    let remote: PositionRow | undefined
    let reachable = true
    try {
      const rows = await this.remote.listPositions()
      remote = rows.find((r) => r.book_sha256 === sha256 && r.variant === variant)
    } catch {
      reachable = false
    }
    const winner = newestWins(local, remote)
    if (winner === 'remote' && remote) return { decision: { from: 'remote', row: remote }, reachable }
    if (winner === 'local' && local) return { decision: { from: 'local', row: local }, reachable }
    return { decision: { from: 'none' }, reachable }
  }

  /** Every position the server holds, for the library's "43% read" labels. Null when unreachable. */
  async allRemote(): Promise<PositionRow[] | null> {
    try {
      return await this.remote.listPositions()
    } catch {
      return null
    }
  }

  async allLocal(): Promise<PositionRow[]> {
    return (await this.store.entries<PositionRow>('positions')).map(([, row]) => row)
  }

  /** Record a turn the person made: local first, then queued, then a try at sending. */
  async savePosition(
    sha256: string,
    variant: Variant,
    place: Place,
    recordedAt: string,
    init?: { keepalive?: boolean },
  ): Promise<{ pending: number }> {
    const body = {
      locator: place.locator,
      total_progression: place.totalProgression,
      chapter_label: place.chapterLabel,
      recorded_at: recordedAt,
      device: DEVICE,
    }
    await this.store.set('positions', positionKey(sha256, variant), {
      ...body,
      book_sha256: sha256,
      variant,
      updated_at: recordedAt,
    } satisfies PositionRow)
    await this.queue.enqueue({ kind: 'position', sha256, variant, body })
    return this.flush(init)
  }

  async flush(init?: { keepalive?: boolean }): Promise<{ pending: number }> {
    const result = await this.queue.flush(this.sender, init)
    return { pending: result.pending }
  }

  // ---- bookmarks ----------------------------------------------------------

  private async localBookmarks(): Promise<Map<string, BookmarkRow>> {
    return new Map(await this.store.entries<BookmarkRow>('bookmarks'))
  }

  async bookmarksFor(sha256: string, variant: Variant): Promise<BookmarkRow[]> {
    return [...(await this.localBookmarks()).values()]
      .filter((b) => b.book_sha256 === sha256 && b.variant === variant && !b.deleted_at)
      .sort((a, b) => a.total_progression - b.total_progression)
  }

  /** Pull everything newer than the cursor. Returns false when the server could not be reached. */
  async pullBookmarks(): Promise<boolean> {
    try {
      const cursor = await this.store.get<string>('meta', 'bookmarks-cursor')
      const rows = await this.remote.listBookmarks(cursor)
      const pending = new Set((await this.queue.pendingOps()).flatMap((o) => (o.kind === 'position' ? [] : [o.id])))
      const merged = applyBookmarkPull(await this.localBookmarks(), rows, (id) => pending.has(id))
      const before = await this.localBookmarks()
      for (const id of before.keys()) if (!merged.has(id)) await this.store.delete('bookmarks', id)
      for (const [id, row] of merged) await this.store.set('bookmarks', id, row)
      const next = nextCursor(rows, cursor)
      if (next) await this.store.set('meta', 'bookmarks-cursor', next)
      return true
    } catch {
      return false
    }
  }

  async addBookmark(input: {
    sha256: string
    variant: Variant
    locator: LocatorJson
    totalProgression: number
    label: string | null
    note: string | null
  }): Promise<BookmarkRow> {
    const stamp = this.now().toISOString()
    const row: BookmarkRow = {
      id: crypto.randomUUID(),
      book_sha256: input.sha256,
      variant: input.variant,
      locator: input.locator,
      total_progression: input.totalProgression,
      label: input.label,
      note: input.note,
      created_at: stamp,
      deleted_at: null,
      updated_at: stamp,
    }
    await this.store.set('bookmarks', row.id, row)
    await this.queue.enqueue({
      kind: 'bookmark-put',
      id: row.id,
      body: {
        book_sha256: row.book_sha256,
        variant: row.variant,
        locator: row.locator,
        total_progression: row.total_progression,
        label: row.label,
        note: row.note,
        created_at: row.created_at,
      },
    })
    await this.flush()
    return row
  }

  async removeBookmark(id: string): Promise<void> {
    await this.store.delete('bookmarks', id)
    await this.queue.enqueue({ kind: 'bookmark-delete', id })
    await this.flush()
  }
}
