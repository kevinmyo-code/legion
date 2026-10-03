import { describe, expect, test } from 'vitest'

import { refusalMessage, runWrite, sentenceFromBody, WriteRefused } from '@/api/refusal'
import { readAll, type Cursor, type Table } from '@/api/synced'

function reply<D>(data: D, status = 200) {
  return { data, response: new Response(null, { status }) }
}

/** A table that serves `pages` in order, recording the cursors it was asked with. */
function pagedTable(pages: { results: number[]; next: string | null; next_after: string | null }[]) {
  const asked: Cursor[] = []
  const table: Table<number> = {
    name: 'test',
    page: async (cursor) => {
      asked.push(cursor)
      return reply(pages[Math.min(asked.length - 1, pages.length - 1)])
    },
  }
  return { table, asked }
}

describe('readAll', () => {
  test('follows next and next_after to the end and returns every row', async () => {
    const { table, asked } = pagedTable([
      { results: [1, 2], next: '2026-01-02T00:00:00Z', next_after: 'b' },
      { results: [3, 4], next: '2026-01-02T00:00:00Z', next_after: 'd' },
      { results: [5], next: null, next_after: null },
    ])
    expect(await readAll(table)).toEqual([1, 2, 3, 4, 5])
    expect(asked).toEqual([
      {},
      { since: '2026-01-02T00:00:00Z', after: 'b' },
      { since: '2026-01-02T00:00:00Z', after: 'd' },
    ])
  })

  test('a full page followed by an empty one still ends cleanly', async () => {
    const { table } = pagedTable([
      { results: [1, 2], next: '2026-01-02T00:00:00Z', next_after: 'b' },
      { results: [], next: null, next_after: null },
    ])
    expect(await readAll(table)).toEqual([1, 2])
  })

  test('a page that fails fails the whole read: there is no partial list to total', async () => {
    let calls = 0
    const table: Table<number> = {
      name: 'test',
      page: async () => {
        calls += 1
        if (calls === 2) return { response: new Response(null, { status: 503 }) }
        return reply({ results: [1, 2], next: 'x', next_after: String(calls) })
      },
    }
    await expect(readAll(table)).rejects.toThrow('GET test answered 503')
  })

  test('an engine that hands back the cursor it was just given is an error, not an endless loop', async () => {
    const { table } = pagedTable([{ results: [1], next: 's', next_after: 'a' }])
    await expect(readAll(table)).rejects.toThrow('did not advance')
  })
})

describe('what a refused write says', () => {
  test('reads both body shapes the engine uses', () => {
    expect(sentenceFromBody({ detail: 'No such row.' })).toBe('No such row.')
    expect(sentenceFromBody({ name: ['must not be blank.'], year: ['must be a number.'] })).toBe(
      'name: must not be blank. year: must be a number.',
    )
    expect(sentenceFromBody(null)).toBeNull()
    expect(sentenceFromBody({})).toBeNull()
  })

  test('opens with what did not happen, unless the engine already did', () => {
    expect(refusalMessage('saved', 400, { name: ['must not be blank.'] })).toBe(
      'Nothing was saved. name: must not be blank.',
    )
    expect(refusalMessage('deleted', 404, { detail: 'Nothing was changed. No row has id 1.' })).toBe(
      'Nothing was changed. No row has id 1.',
    )
    expect(refusalMessage('saved', 500, undefined)).toBe('Nothing was saved. The engine answered 500 without saying why.')
    expect(refusalMessage('saved', 403, undefined)).toContain('Sign in again')
  })

  test('a dead network is its own sentence, and still says nothing was saved', async () => {
    await expect(
      runWrite('saved', async () => {
        throw new TypeError('network error')
      }),
    ).rejects.toThrow('Could not reach the engine. Nothing was saved.')
  })

  test('resolves only when the engine said yes', async () => {
    await expect(runWrite('saved', async () => ({ response: new Response(null, { status: 200 }) }))).resolves.toBeUndefined()
    await expect(
      runWrite('saved', async () => ({ error: { detail: 'no' }, response: new Response(null, { status: 400 }) })),
    ).rejects.toBeInstanceOf(WriteRefused)
  })
})
