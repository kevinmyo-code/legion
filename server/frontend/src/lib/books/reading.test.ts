import { describe, expect, test } from 'vitest'

import type { PositionPut, PositionRow } from '@/api/books'
import { createEngine } from '@/test/engine'

import { MemoryKV } from './kv'
import { ReadingSync, type Remote } from './reading'
import type { Place, Sender } from './sync'

const SHA = 'b'.repeat(64)
const place: Place = { locator: { href: 'OEBPS/ch2.xhtml' }, totalProgression: 0.4, chapterLabel: 'Chapter 2' }

/** The reading-sync API, answered by the fake engine that follows ticket 08's contract. */
function setUp() {
  const engine = createEngine({})
  let down = false
  const call = <T>(method: string, path: string, body?: unknown, search = ''): Promise<T> => {
    if (down) return Promise.reject(new TypeError('network error'))
    const r = engine.handle(method, path, new URLSearchParams(search), body)
    return Promise.resolve(r.body as T)
  }
  const remote: Remote = {
    listPositions: (since) => call('GET', '/api/books/positions', undefined, since ? `since=${since}` : ''),
    listBookmarks: (since) => call('GET', '/api/books/bookmarks', undefined, since ? `since=${since}` : ''),
  }
  const sender: Sender = {
    putPosition: (sha, variant, body) => call('PUT', `/api/books/positions/${sha}/${variant}`, body),
    putBookmark: (id, body) => call('PUT', `/api/books/bookmarks/${id}`, body),
    deleteBookmark: (id) => call('DELETE', `/api/books/bookmarks/${id}`),
  }
  const store = new MemoryKV()
  return {
    engine,
    store,
    sync: new ReadingSync(store, remote, sender),
    setDown: (v: boolean) => (down = v),
    sender,
  }
}

const phoneRow = (recorded_at: string): PositionRow => ({
  book_sha256: SHA,
  variant: 'original',
  locator: { href: 'OEBPS/ch3.xhtml' },
  total_progression: 0.7,
  chapter_label: 'Chapter 3',
  recorded_at,
  device: 'A25',
  updated_at: recorded_at,
})

const settle = () => new Promise((r) => setTimeout(r, 0))

describe('where to open', () => {
  test('the phone is newer: open there', async () => {
    const t = setUp()
    t.engine.books.positions.push(phoneRow(new Date().toISOString()))
    const { decision, reachable } = await t.sync.decideStart(SHA, 'original')
    expect(reachable).toBe(true)
    expect(decision.from).toBe('remote')
  })

  test('this browser is newer (it read on after the phone): open here, silently', async () => {
    const t = setUp()
    t.engine.books.positions.push(phoneRow('2026-10-23T08:00:00.000Z'))
    await t.sync.savePosition(SHA, 'original', place, '2026-10-23T09:00:00.000Z')
    const { decision } = await t.sync.decideStart(SHA, 'original')
    expect(decision.from).toBe('local')
  })

  test('the other variant is a different book: a resizable position does not move an original', async () => {
    const t = setUp()
    t.engine.books.positions.push({ ...phoneRow(new Date().toISOString()), variant: 'resizable' })
    expect((await t.sync.decideStart(SHA, 'original')).decision.from).toBe('none')
  })

  test('with the server unreachable the local position decides alone and the page can say so', async () => {
    const t = setUp()
    await t.sync.savePosition(SHA, 'original', place, '2026-10-23T09:00:00.000Z')
    t.setDown(true)
    const { decision, reachable } = await t.sync.decideStart(SHA, 'original')
    expect(reachable).toBe(false)
    expect(decision.from).toBe('local')
  })
})

describe('saving', () => {
  test('a save goes local, then to the server, with the web device name and the reader clock', async () => {
    const t = setUp()
    const r = await t.sync.savePosition(SHA, 'original', place, '2026-10-23T09:00:00.000Z')
    expect(r.pending).toBe(0)
    expect(t.engine.books.positions[0]).toMatchObject({
      device: 'web',
      recorded_at: '2026-10-23T09:00:00.000Z',
      total_progression: 0.4,
    })
    expect(await t.sync.localPosition(SHA, 'original')).toBeDefined()
  })

  test('an older write cannot overwrite a newer stored one: the server keeps it and says so', async () => {
    const t = setUp()
    t.engine.books.positions.push(phoneRow('2026-10-23T12:00:00.000Z'))
    const body: PositionPut = {
      locator: null,
      total_progression: 0.1,
      chapter_label: null,
      recorded_at: '2026-10-23T09:00:00.000Z',
      device: 'web',
    }
    const reply = (await t.sender.putPosition(SHA, 'original', body)) as { kept?: string }
    expect(reply.kept).toBe('newer')
    expect(t.engine.books.positions[0].total_progression).toBe(0.7)
  })

  test('offline it queues, reports the pending count, and sends on the next flush', async () => {
    const t = setUp()
    t.setDown(true)
    expect((await t.sync.savePosition(SHA, 'original', place, '2026-10-23T09:00:00.000Z')).pending).toBe(1)
    expect(t.engine.books.positions).toHaveLength(0)
    t.setDown(false)
    expect((await t.sync.flush()).pending).toBe(0)
    expect(t.engine.books.positions).toHaveLength(1)
  })
})

describe('bookmarks', () => {
  const input = {
    sha256: SHA,
    variant: 'original' as const,
    locator: { page: 2 },
    totalProgression: 0.2,
    label: 'Page 3',
    note: 'the part about the clock',
  }

  test('add, list, delete round-trips through the server, tombstone included', async () => {
    const t = setUp()
    const mark = await t.sync.addBookmark(input)
    await settle()
    expect(t.engine.books.bookmarks[0]).toMatchObject({ id: mark.id, note: 'the part about the clock' })
    expect(await t.sync.bookmarksFor(SHA, 'original')).toHaveLength(1)
    await t.sync.removeBookmark(mark.id)
    await settle()
    expect(t.engine.books.bookmarks[0].deleted_at).not.toBeNull()
    expect(await t.sync.bookmarksFor(SHA, 'original')).toHaveLength(0)
  })

  test('a tombstone from another device removes the local copy on the next pull', async () => {
    const t = setUp()
    const mark = await t.sync.addBookmark(input)
    await settle()
    expect(await t.sync.pullBookmarks()).toBe(true)
    await t.sender.deleteBookmark(mark.id)
    expect(await t.sync.pullBookmarks()).toBe(true)
    expect(await t.sync.bookmarksFor(SHA, 'original')).toHaveLength(0)
  })

  test('a bookmark made offline is kept locally and sent later; the pull does not erase it', async () => {
    const t = setUp()
    t.setDown(true)
    await t.sync.addBookmark(input)
    await settle()
    expect(await t.sync.pullBookmarks()).toBe(false)
    expect(await t.sync.bookmarksFor(SHA, 'original')).toHaveLength(1)
    t.setDown(false)
    await t.sync.flush()
    expect(t.engine.books.bookmarks).toHaveLength(1)
    expect(await t.sync.pullBookmarks()).toBe(true)
    expect(await t.sync.bookmarksFor(SHA, 'original')).toHaveLength(1)
  })
})
