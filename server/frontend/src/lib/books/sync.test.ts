import { describe, expect, test, vi } from 'vitest'

import { BooksHttpError, type BookmarkRow, type PositionRow } from '@/api/books'

import { MemoryKV } from './kv'
import {
  OFFLINE_SENTENCE,
  PositionRecorder,
  SyncQueue,
  applyBookmarkPull,
  mergePositions,
  newestWins,
  nextCursor,
  type Place,
  type Sender,
} from './sync'

const SHA = 'a'.repeat(64)
const place = (p: number): Place => ({ locator: { page: p }, totalProgression: p / 10, chapterLabel: null })
const row = (recorded_at: string, p = 0.5, device = 'A25'): PositionRow => ({
  book_sha256: SHA,
  variant: 'original',
  locator: null,
  total_progression: p,
  chapter_label: null,
  recorded_at,
  device,
  updated_at: recorded_at,
})

describe('newest wins', () => {
  test('the later recorded_at wins, by the reader clock', () => {
    expect(newestWins(row('2026-10-23T10:00:00Z'), row('2026-10-23T11:00:00Z'))).toBe('remote')
    expect(newestWins(row('2026-10-23T12:00:00Z'), row('2026-10-23T11:00:00Z'))).toBe('local')
  })

  test('a tie keeps what is already here; one side missing picks the other', () => {
    const t = '2026-10-23T10:00:00Z'
    expect(newestWins(row(t), row(t))).toBe('local')
    expect(newestWins(undefined, row(t))).toBe('remote')
    expect(newestWins(row(t), undefined)).toBe('local')
    expect(newestWins(undefined, undefined)).toBe('none')
  })

  test('a merge keeps the newest of each book and variant', () => {
    const merged = mergePositions(
      [row('2026-10-23T09:00:00Z', 0.1)],
      [row('2026-10-23T08:00:00Z', 0.9), { ...row('2026-10-23T08:00:00Z', 0.3), variant: 'resizable' }],
    )
    expect(merged).toHaveLength(2)
    expect(merged.find((r) => r.variant === 'original')?.total_progression).toBe(0.1)
  })
})

describe('when a page turn may be saved', () => {
  function recorder() {
    const saved: { place: Place; at: string }[] = []
    let clock = new Date('2026-10-23T10:00:00Z')
    const r = new PositionRecorder((p, at) => saved.push({ place: p, at }), 1500, () => clock)
    return { r, saved, setClock: (iso: string) => (clock = new Date(iso)) }
  }

  test('the opening position is never saved: nothing is recorded until the reader is armed', () => {
    vi.useFakeTimers()
    const { r, saved } = recorder()
    r.moved(place(3))
    vi.advanceTimersByTime(5000)
    r.flush()
    expect(saved).toEqual([])
    vi.useRealTimers()
  })

  test('once armed, a turn is saved after the pause, stamped when it was made', () => {
    vi.useFakeTimers()
    const { r, saved, setClock } = recorder()
    r.arm()
    r.moved(place(4))
    setClock('2026-10-23T10:00:01Z')
    r.moved(place(5))
    setClock('2026-10-23T10:00:09Z')
    vi.advanceTimersByTime(1499)
    expect(saved).toEqual([])
    vi.advanceTimersByTime(2)
    expect(saved).toEqual([{ place: place(5), at: '2026-10-23T10:00:01.000Z' }])
    vi.useRealTimers()
  })

  test('a person-made turn arms it at once, so a turn right after opening is not lost', () => {
    vi.useFakeTimers()
    const { r, saved } = recorder()
    r.userTurn()
    r.moved(place(6))
    r.flush()
    expect(saved).toHaveLength(1)
    vi.useRealTimers()
  })

  test('hiding the tab flushes what is waiting, and flushing twice saves once', () => {
    vi.useFakeTimers()
    const { r, saved } = recorder()
    r.arm()
    r.moved(place(7))
    r.flush()
    r.flush()
    expect(saved).toHaveLength(1)
    vi.useRealTimers()
  })
})

describe('the offline queue', () => {
  const body = (p: number) => ({
    locator: null,
    total_progression: p,
    chapter_label: null,
    recorded_at: '2026-10-23T10:00:00Z',
    device: 'web',
  })
  function sender(overrides: Partial<Sender> = {}): Sender & { positions: number[] } {
    const positions: number[] = []
    return {
      positions,
      putPosition: async (_s, _v, b) => void positions.push(b.total_progression),
      putBookmark: async () => {},
      deleteBookmark: async () => {},
      ...overrides,
    }
  }

  test('ten turns while offline are one write, the latest', async () => {
    const q = new SyncQueue(new MemoryKV())
    for (let i = 1; i <= 10; i++) {
      await q.enqueue({ kind: 'position', sha256: SHA, variant: 'original', body: body(i / 10) })
    }
    expect(await q.pendingCount()).toBe(1)
    const s = sender()
    expect(await q.flush(s)).toEqual({ sent: 1, dropped: 0, pending: 0 })
    expect(s.positions).toEqual([1])
  })

  test('an unreachable server keeps everything and says how many are waiting', async () => {
    const q = new SyncQueue(new MemoryKV())
    await q.enqueue({ kind: 'position', sha256: SHA, variant: 'original', body: body(0.2) })
    await q.enqueue({ kind: 'bookmark-delete', id: 'b1' })
    const dead = async () => {
      throw new TypeError('network error')
    }
    const down = sender({ putPosition: dead, deleteBookmark: dead })
    expect(await q.flush(down)).toEqual({ sent: 0, dropped: 0, pending: 2 })
    expect(OFFLINE_SENTENCE).toBe('Reading progress will sync when the server is reachable.')
  })

  test('a server error is retried; a refusal is dropped so it cannot block the rest', async () => {
    const q = new SyncQueue(new MemoryKV())
    await q.enqueue({ kind: 'bookmark-delete', id: 'bad' })
    await q.enqueue({ kind: 'position', sha256: SHA, variant: 'original', body: body(0.4) })
    const s = sender({
      deleteBookmark: async () => {
        throw new BooksHttpError(400, 'bad')
      },
    })
    expect(await q.flush(s)).toEqual({ sent: 1, dropped: 1, pending: 0 })
    await q.enqueue({ kind: 'position', sha256: SHA, variant: 'original', body: body(0.5) })
    const flaky = sender({
      putPosition: async () => {
        throw new BooksHttpError(503, 'down')
      },
    })
    expect((await q.flush(flaky)).pending).toBe(1)
  })

  test('a bookmark added then deleted offline leaves only the delete', async () => {
    const q = new SyncQueue(new MemoryKV())
    await q.enqueue({
      kind: 'bookmark-put',
      id: 'x',
      body: {
        book_sha256: SHA,
        variant: 'original',
        locator: {},
        total_progression: 0.1,
        label: null,
        note: null,
        created_at: '2026-10-23T10:00:00Z',
      },
    })
    await q.enqueue({ kind: 'bookmark-delete', id: 'x' })
    expect(await q.pendingOps()).toEqual([{ kind: 'bookmark-delete', id: 'x' }])
  })

  test('the queue is persisted: a new queue over the same store still has the writes', async () => {
    const store = new MemoryKV()
    await new SyncQueue(store).enqueue({ kind: 'position', sha256: SHA, variant: 'original', body: body(0.3) })
    expect(await new SyncQueue(store).pendingCount()).toBe(1)
  })
})

describe('bookmarks from the server', () => {
  const mark = (id: string, extra: Partial<BookmarkRow> = {}): BookmarkRow => ({
    id,
    book_sha256: SHA,
    variant: 'original',
    locator: {},
    total_progression: 0.2,
    label: null,
    note: null,
    created_at: '2026-10-23T10:00:00Z',
    deleted_at: null,
    updated_at: '2026-10-23T10:00:00Z',
    ...extra,
  })

  test('a tombstone removes the local bookmark; a row with a pending local change is left alone', () => {
    const local = new Map([
      ['a', mark('a')],
      ['b', mark('b')],
      ['c', mark('c')],
    ])
    const next = applyBookmarkPull(
      local,
      [mark('a', { deleted_at: '2026-10-23T11:00:00Z' }), mark('b', { note: 'server' }), mark('c', { note: 'server' })],
      (id) => id === 'b',
    )
    expect([...next.keys()]).toEqual(['b', 'c'])
    expect(next.get('b')?.note).toBeNull()
    expect(next.get('c')?.note).toBe('server')
  })

  test('the next since is the newest server updated_at, never the local clock', () => {
    expect(
      nextCursor([
        mark('a', { updated_at: '2026-10-23T09:00:00Z' }),
        mark('b', { updated_at: '2026-10-23T12:00:00Z' }),
      ]),
    ).toBe('2026-10-23T12:00:00Z')
    expect(nextCursor([], '2026-10-23T08:00:00Z')).toBe('2026-10-23T08:00:00Z')
  })
})
