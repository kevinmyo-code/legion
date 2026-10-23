import { afterEach, describe, expect, test, vi } from 'vitest'

import { deleteBookmark, listPositions } from './books'

afterEach(() => vi.unstubAllGlobals())

const row = (n: number) => ({ book_sha256: 'a'.repeat(64), variant: 'original', locator: null, total_progression: n / 10, chapter_label: null, recorded_at: 'x', device: 'phone', updated_at: 'x' })
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

describe('reading the position list', () => {
  test('reads a bare array', async () => {
    vi.stubGlobal('fetch', async () => json([row(1), row(2)]))
    expect(await listPositions()).toHaveLength(2)
  })

  test('follows `next` through a paged shape, and sends since with the + encoded', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', async (req: Request) => {
      urls.push(req.url)
      return urls.length === 1
        ? json({ results: [row(1)], next: 'http://localhost/api/books/positions?since=2026-10-23T10%3A00%3A00%2B00%3A00&cursor=p2' })
        : json({ results: [row(2)], next: null })
    })
    const rows = await listPositions('2026-10-23T10:00:00+00:00')
    expect(rows).toHaveLength(2)
    expect(urls[0]).toContain('since=2026-10-23T10%3A00%3A00%2B00%3A00')
    expect(urls[1]).toContain('cursor=p2')
    expect(urls[1]).not.toMatch(/since=[^&]*\+/)
  })
})

describe('a refusal shows the engine sentence', () => {
  const refuse = (body: unknown) => new Response(JSON.stringify(body), { status: 404, headers: { 'Content-Type': 'application/json' } })

  test('reads `detail`, the shape the sync routes refuse with', async () => {
    vi.stubGlobal('fetch', async () => refuse({ detail: 'Nothing was deleted. No bookmark of yours has that id.' }))
    await expect(deleteBookmark('00000000-0000-0000-0000-000000000000')).rejects.toThrow('Nothing was deleted. No bookmark of yours has that id.')
  })

  test('still reads `message`, the shape the convert route refuses with', async () => {
    vi.stubGlobal('fetch', async () => refuse({ code: 'scanned', message: 'This PDF is scanned pages with no text, so its text cannot be refit.' }))
    await expect(deleteBookmark('00000000-0000-0000-0000-000000000000')).rejects.toThrow('This PDF is scanned pages')
  })
})
