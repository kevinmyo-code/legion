import type { BookmarkRow, PositionPut, PositionRow, Variant } from '../api/books'
import type { Reply } from './engine'

/**
 * The fake engine's `/api/books/*`, following ticket 08's contract exactly
 * (`.scratch/books/issues/08-build-reading-sync-engine.md`), because the real
 * endpoints are not deployed yet: upsert with newest-`recorded_at`-wins and
 * `"kept": "newer"`, tombstoned bookmarks that come back in `since` reads, and
 * `updated_at` set by the server. Rows are the CALLER's own, as on the server.
 *
 * `POST /api/books/convert` is not here: it answers bytes, and this engine's
 * replies are JSON. Tests that need it pass the reader a `convert` function.
 */

export interface BooksState {
  positions: PositionRow[]
  bookmarks: BookmarkRow[]
}

export function defaultBooks(): BooksState {
  return { positions: [], bookmarks: [] }
}

const SHA = /^[0-9a-f]{64}$/
const VARIANTS = new Set<Variant>(['original', 'resizable'])

let tick = 0
/** Strictly increasing server time, so two writes in one millisecond still order. */
function serverNow(): string {
  tick += 1
  return new Date(Date.now() + tick).toISOString()
}

function bad(message: string): Reply {
  return { status: 400, body: { message } }
}

export function handleBooks(
  host: { books: BooksState },
  method: string,
  pathname: string,
  search: URLSearchParams,
  body: unknown,
): Reply | undefined {
  if (!pathname.startsWith('/api/books/')) return undefined
  const b = host.books
  const since = search.get('since')
  const after = (row: { updated_at: string }) => !since || Date.parse(row.updated_at) > Date.parse(since)

  if (method === 'GET' && pathname === '/api/books/positions') {
    return { status: 200, body: b.positions.filter(after) }
  }

  const put = pathname.match(/^\/api\/books\/positions\/([^/]+)\/([^/]+)$/)
  if (put && method === 'PUT') {
    const [, sha, variant] = put
    const sent = body as PositionPut
    if (!SHA.test(sha)) return bad('That is not a SHA-256 hash: it must be 64 lowercase hex characters.')
    if (!VARIANTS.has(variant as Variant)) return bad('The variant must be original or resizable.')
    if (typeof sent.total_progression !== 'number' || sent.total_progression < 0 || sent.total_progression > 1) {
      return bad('The progression must be a number from 0 to 1.')
    }
    const held = b.positions.find((p) => p.book_sha256 === sha && p.variant === variant)
    if (held && Date.parse(held.recorded_at) > Date.parse(sent.recorded_at)) {
      return { status: 200, body: { ...held, kept: 'newer' } }
    }
    const row: PositionRow = { ...sent, book_sha256: sha, variant: variant as Variant, updated_at: serverNow() }
    if (held) Object.assign(held, row)
    else b.positions.push(row)
    return { status: 200, body: row }
  }

  if (method === 'GET' && pathname === '/api/books/bookmarks') {
    return { status: 200, body: b.bookmarks.filter(after) }
  }

  const mark = pathname.match(/^\/api\/books\/bookmarks\/([^/]+)$/)
  if (mark && method === 'PUT') {
    const sent = body as Omit<BookmarkRow, 'id' | 'deleted_at' | 'updated_at'>
    if (!SHA.test(sent.book_sha256)) return bad('That is not a SHA-256 hash: it must be 64 lowercase hex characters.')
    const held = b.bookmarks.find((x) => x.id === mark[1])
    const row: BookmarkRow = { ...sent, id: mark[1], deleted_at: held?.deleted_at ?? null, updated_at: serverNow() }
    if (held) Object.assign(held, row)
    else b.bookmarks.push(row)
    return { status: 200, body: row }
  }
  if (mark && method === 'DELETE') {
    const held = b.bookmarks.find((x) => x.id === mark[1])
    if (!held) return { status: 404, body: { message: 'No such bookmark. Nothing was changed.' } }
    if (!held.deleted_at) {
      held.deleted_at = serverNow()
      held.updated_at = held.deleted_at
    }
    return { status: 200, body: held }
  }
  return undefined
}
