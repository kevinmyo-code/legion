import createClient from 'openapi-fetch'

import { djangoSession } from '@/api/client'

/**
 * HAND-TYPED client for the books endpoints. REPLACE WITH THE GENERATED TYPES.
 *
 * Tickets 08 (reading sync) and 04 (PDF convert) have not shipped, so their paths
 * are not in `schema.d.ts` yet. `client.ts` says a missing path is fixed upstream
 * and never by a cast; this is the one deliberate exception, narrow on purpose:
 * only the six endpoints those two tickets define, typed exactly as ticket 08's
 * "Tables" and "API" sections and ticket 04's response table say. It still goes
 * through `openapi-fetch` and the same `djangoSession` middleware (CSRF header,
 * same-origin cookies), so nothing here is a raw `fetch`.
 *
 * When 08 and 04 land and `npm run gen:api` adds them to `schema.d.ts`, delete the
 * `BooksPaths` interface below and import `paths` from `./schema`; every call site
 * is already shaped like the generated one.
 *
 * Paths have NO trailing slash, as ticket 08 writes them (the games routes have one).
 */

export type Variant = 'original' | 'resizable'

/** A reader locator as stored: Readium Locator JSON for an EPUB, `{ page }` (0-based) for PDF/CBZ. */
export type LocatorJson = Record<string, unknown>

export interface PositionRow {
  book_sha256: string
  variant: Variant
  locator: LocatorJson | null
  total_progression: number
  chapter_label: string | null
  recorded_at: string
  device: string
  updated_at: string
}

export interface PositionPut {
  locator: LocatorJson | null
  total_progression: number
  chapter_label: string | null
  recorded_at: string
  device: string
}

export interface PositionPutResponse extends PositionRow {
  /** `"newer"` when the stored row was newer and the write was kept out. */
  kept?: 'newer'
}

export interface BookmarkRow {
  id: string
  book_sha256: string
  variant: Variant
  locator: LocatorJson
  total_progression: number
  label: string | null
  note: string | null
  created_at: string
  deleted_at: string | null
  updated_at: string
}

export type BookmarkPut = Omit<BookmarkRow, 'id' | 'deleted_at' | 'updated_at'>

export interface ConvertError {
  code: 'scanned' | 'unreadable' | 'too_big' | 'failed'
  message: string
}

/** Ticket 08 does not fix the list shape yet: a bare array or `{ results, next }`. Both are read. */
type Page<T> = T[] | { results: T[]; next?: string | null }

interface BooksPaths {
  '/api/books/positions': {
    get: {
      parameters: { query?: Record<string, string> }
      responses: { 200: { content: { 'application/json': Page<PositionRow> } } }
    }
  }
  '/api/books/positions/{sha256}/{variant}': {
    put: {
      parameters: { path: { sha256: string; variant: Variant } }
      requestBody: { content: { 'application/json': PositionPut } }
      responses: { 200: { content: { 'application/json': PositionPutResponse } } }
    }
  }
  '/api/books/bookmarks': {
    get: {
      parameters: { query?: Record<string, string> }
      responses: { 200: { content: { 'application/json': Page<BookmarkRow> } } }
    }
  }
  '/api/books/bookmarks/{id}': {
    put: {
      parameters: { path: { id: string } }
      requestBody: { content: { 'application/json': BookmarkPut } }
      responses: { 200: { content: { 'application/json': BookmarkRow } } }
    }
    delete: {
      parameters: { path: { id: string } }
      responses: { 200: { content: { 'application/json': BookmarkRow } } }
    }
  }
  '/api/books/convert': {
    post: {
      requestBody: { content: { 'application/pdf': ArrayBuffer } }
      // 200 is `application/epub+zip` bytes with `X-Legion-Pages`; read with parseAs 'arrayBuffer'.
      responses: { 200: { content: { 'application/epub+zip': ArrayBuffer } } }
    }
  }
}

const books = createClient<BooksPaths>({
  baseUrl: '/',
  credentials: 'same-origin',
  fetch: (request) => globalThis.fetch(request),
})
books.use(djangoSession)

export const CANT_REACH_BOOKS_SYNC = 'Could not reach the server, so reading progress was not synced.'

/** A response the server answered with a status that is not a success. */
export class BooksHttpError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

// The sync routes refuse as `{"detail": ...}` (DRF); the convert route as `{"code", "message"}`.
function failed(status: number, body: unknown): never {
  const said = (key: 'detail' | 'message'): string | undefined =>
    body && typeof body === 'object' && key in body && typeof (body as Record<string, unknown>)[key] === 'string'
      ? ((body as Record<string, unknown>)[key] as string)
      : undefined
  throw new BooksHttpError(status, said('detail') ?? said('message') ?? `The server answered ${status}.`)
}

async function readAll<T>(
  first: Record<string, string>,
  get: (query: Record<string, string>) => Promise<{ data?: Page<T>; error?: unknown; response: Response }>,
): Promise<T[]> {
  const out: T[] = []
  let query: Record<string, string> | undefined = first
  for (let guard = 0; query && guard < 200; guard++) {
    const { data, error, response } = await get(query)
    if (!data) failed(response.status, error)
    if (Array.isArray(data)) {
      out.push(...data)
      break
    }
    out.push(...data.results)
    // `next` may be absolute; only its query string matters, and it is re-encoded on the way out.
    query = undefined
    if (data.next) {
      const next: Record<string, string> = {}
      new URL(data.next, window.location.origin).searchParams.forEach((v, k) => {
        next[k] = v
      })
      query = next
    }
  }
  return out
}

export function listPositions(since?: string): Promise<PositionRow[]> {
  return readAll<PositionRow>(since ? { since } : {}, (query) =>
    books.GET('/api/books/positions', { params: { query } }),
  )
}

export async function putPosition(
  sha256: string,
  variant: Variant,
  body: PositionPut,
  init?: { keepalive?: boolean },
): Promise<PositionPutResponse> {
  const { data, error, response } = await books.PUT('/api/books/positions/{sha256}/{variant}', {
    params: { path: { sha256, variant } },
    body,
    ...init,
  })
  if (!data) failed(response.status, error)
  return data
}

export function listBookmarks(since?: string): Promise<BookmarkRow[]> {
  return readAll<BookmarkRow>(since ? { since } : {}, (query) =>
    books.GET('/api/books/bookmarks', { params: { query } }),
  )
}

export async function putBookmark(id: string, body: BookmarkPut): Promise<BookmarkRow> {
  const { data, error, response } = await books.PUT('/api/books/bookmarks/{id}', {
    params: { path: { id } },
    body,
  })
  if (!data) failed(response.status, error)
  return data
}

export async function deleteBookmark(id: string): Promise<BookmarkRow> {
  const { data, error, response } = await books.DELETE('/api/books/bookmarks/{id}', {
    params: { path: { id } },
  })
  if (!data) failed(response.status, error)
  return data
}

/** A PDF's resizable EPUB copy (ticket 04). Nothing is stored server-side. */
export async function convertPdf(pdf: ArrayBuffer): Promise<{ epub: Uint8Array; pages: number | null }> {
  const { data, error, response } = await books.POST('/api/books/convert', {
    body: pdf,
    bodySerializer: (b) => b as BodyInit,
    headers: { 'Content-Type': 'application/pdf' },
    parseAs: 'arrayBuffer',
  })
  if (!data) failed(response.status, error)
  const pages = Number(response.headers.get('X-Legion-Pages'))
  return { epub: new Uint8Array(data as ArrayBuffer), pages: Number.isFinite(pages) && pages > 0 ? pages : null }
}
