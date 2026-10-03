import { useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query'

import { useVisibleInterval } from '@/api/refetch'
import { runWrite } from '@/api/refusal'

/**
 * Reading and writing the engine's synced tables from a screen.
 *
 * Every synced table answers `GET <table>/?since=` one page at a time with a
 * `next` cursor (null on the last page), `PUT <table>/<identity>/` as an upsert
 * and `DELETE` as a soft tombstone. A screen here wants "every live row", so
 * `useRows` follows `next` until it is null and hands the screen the whole list
 * or nothing: **a partial fetch is never rendered, so it can never be totalled**
 * (spec ticket 16, "paged lists fetch to `next: null` and never total a partial
 * fetch"). While pages are still arriving the query is `pending`; if one page
 * fails the whole read fails and the screen says it could not reach the engine,
 * which is a different sentence from "there is nothing here".
 *
 * The typed calls themselves live in `aspects.ts`, one explicit closure per
 * table, because the generated client types every path separately and a
 * generic `get(path)` would be a cast around the very types that make a renamed
 * server field a build error.
 */

export interface PageOf<T> {
  results: T[]
  next: string | null
  next_after: string | null
}

export interface Cursor {
  since?: string
  after?: string
}

interface Reply<D> {
  data?: D
  error?: unknown
  response: Response
}

/** One synced table, as a screen sees it. */
export interface Table<T> {
  /** The engine's table name, for query keys and messages. */
  name: string
  /** One page of rows. */
  page: (cursor: Cursor) => Promise<Reply<PageOf<T>>>
  /** Upsert by identity. Absent for a table the engine only lets the gate write. */
  put?: (identity: string, body: T) => Promise<Reply<unknown>>
  /** Soft delete by identity. Absent where the engine has no DELETE route. */
  del?: (identity: string) => Promise<Reply<unknown>>
}

const MAX_PAGES = 2000

export async function readAll<T>(table: Table<T>): Promise<T[]> {
  const rows: T[] = []
  let cursor: Cursor = {}
  for (let pages = 0; pages < MAX_PAGES; pages += 1) {
    const reply = await table.page(cursor)
    if (reply.data === undefined) {
      throw new Error(`GET ${table.name} answered ${reply.response.status}`)
    }
    rows.push(...reply.data.results)
    if (reply.data.next === null) return rows
    const next = { since: reply.data.next, after: reply.data.next_after ?? undefined }
    if (next.since === cursor.since && next.after === cursor.after) {
      // The engine handed back the cursor we just used: following it would loop
      // forever and the list could never be complete. Say so, do not truncate.
      throw new Error(`GET ${table.name} did not advance past ${next.since}`)
    }
    cursor = next
  }
  throw new Error(`GET ${table.name} did not finish within ${MAX_PAGES} pages`)
}

/** How often a screen asks again while visible. The source of these rows changes
 * when a phone syncs or a daily job runs, not by the second (spec D13). */
export const ASPECT_POLL_MS = 5 * 60_000

export function rowsKey(table: { name: string }): QueryKey {
  return ['rows', table.name]
}

/** Every live row of a table, complete or not at all. */
export function useRows<T>(table: Table<T>, enabled = true) {
  const interval = useVisibleInterval(ASPECT_POLL_MS)
  return useQuery({
    queryKey: rowsKey(table),
    queryFn: () => readAll(table),
    enabled,
    retry: false,
    refetchInterval: interval,
    refetchOnWindowFocus: 'always',
    refetchOnReconnect: 'always',
  })
}

/**
 * Save one row. Not optimistic: an engine write can be refused by validation,
 * and a row that appears and then vanishes is worse than one that waits. The
 * promise rejects with a `WriteRefused` whose message says what did not happen.
 */
export function useSave<T>(table: Table<T>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ identity, body }: { identity: string; body: T }) => {
      const put = table.put
      if (!put) throw new Error(`${table.name} has no write route`)
      await runWrite('saved', () => put(identity, body))
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: rowsKey(table) }),
  })
}

/** Soft-delete one row. Not optimistic, for the same reason. */
export function useRemove<T>(table: Table<T>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (identity: string) => {
      const del = table.del
      if (!del) throw new Error(`${table.name} has no delete route`)
      await runWrite('deleted', () => del(identity))
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: rowsKey(table) }),
  })
}

type Equal<A, B> =
  (<X>() => X extends A ? 1 : 2) extends <X>() => X extends B ? 1 : 2 ? true : false

type WritableKeys<T> = {
  [K in keyof T]-?: Equal<{ [Q in K]: T[K] }, { -readonly [Q in K]: T[K] }> extends true ? K : never
}[keyof T]

/** The fields of a schema type a client may send: everything not `readOnly`. */
export type Writable<T> = Pick<T, WritableKeys<T>>

/**
 * The body of a write, typed as the schema's full row.
 *
 * `server/openapi.yaml` lists `id`, `provenance`, `created_at`, `updated_at` and
 * `deleted_at` as both `required` and `readOnly` (drf-spectacular's quirk), so
 * the generated request type demands fields the server ignores. This takes only
 * the writable fields - checked by `Writable<T>`, so a misspelt or missing one
 * is a build error - and returns them as the full type for the client. The
 * server mints the rest. It is the one cast on the write path, and it is here so
 * no call site needs its own (the same trade `api/types.ts` makes for
 * checklists).
 */
export function wire<T extends object>(fields: Writable<T>): T {
  return fields as unknown as T
}
