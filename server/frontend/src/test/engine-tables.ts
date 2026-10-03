import type { Engine, Reply } from './engine'

/**
 * The fake engine's synced tables: the same `GET <table>/?since=`, `PUT
 * <table>/<identity>/` and `DELETE` the real engine serves, for every table the
 * web reads through `api/synced.ts`.
 *
 * It models the three behaviours a screen can get wrong, because those are the
 * ones the real engine has:
 *
 * - **Paging.** A list answers one page at a time with `next` (the cursor of the
 *   last row, null on a short page) and `next_after` (that row's id), and a
 *   client that asks again with them gets the rows strictly after. `pageSize` is
 *   settable per test so a screen's "fetch to `next: null`" is actually
 *   exercised, including a page that is exactly full and followed by an empty
 *   one - a client that stops at a full page would silently lose rows.
 * - **Write routes that do not exist.** Receipts and line items are GET only (the
 *   gate writes them), and answer the gate's own 405 sentence on a write.
 * - **Identity.** Most tables are keyed by `origin_guid`; places by `label`; voice
 *   notes by `id`; maintenance schedules by `(vehicle_id, service_name)`. A PUT
 *   to an identity that does not exist creates the row, as the engine does.
 *
 * Tables are plain arrays on `engine.tables`, keyed by the path after `/api/`
 * (`body/bodyweight_logs`), mutated by the write routes so a save round-trips.
 */

export type Row = Record<string, unknown>

interface Spec {
  /** The row column the URL's identity names. */
  identity: 'origin_guid' | 'label' | 'id' | 'sync_id' | 'composite'
  writable: boolean
  deletable: boolean
  /** The column `since` is compared against. */
  cursor: 'updated_at' | 'created_at'
  /** Whether live-only (`active=1`) means anything on this table. */
  tombstones: boolean
}

const synced = (identity: Spec['identity'] = 'origin_guid'): Spec => ({
  identity,
  writable: true,
  deletable: true,
  cursor: 'updated_at',
  tombstones: true,
})

const gated: Spec = {
  identity: 'id',
  writable: false,
  deletable: false,
  cursor: 'created_at',
  tombstones: false,
}

export const TABLE_SPECS: Record<string, Spec> = {
  'pantry/grocery_staples': synced(),
  'pantry/receipts': gated,
  'pantry/line-items': gated,
  'body/bodyweight_logs': synced(),
  'body/sleep_logs': synced(),
  'body/sleep_targets': synced(),
  'body/meal_logs': synced(),
  'body/meal_targets': synced(),
  'body/workout_plans': synced(),
  'body/workout_plan_items': synced(),
  'body/workout_set_logs': synced(),
  'fleet/vehicles': synced(),
  'fleet/service_history': synced(),
  'fleet/maintenance_schedules': synced('composite'),
  'fleet/drives': synced('sync_id'),
  places: synced('label'),
  voice_notes: { ...synced('id'), deletable: true },
}

const TABLE_PATHS = Object.keys(TABLE_SPECS).sort((a, b) => b.length - a.length)

let counter = 0
function uuid(): string {
  counter += 1
  return `10000000-0000-4000-8000-${counter.toString(16).padStart(12, '0')}`
}

function cursorOf(row: Row, spec: Spec): string {
  return String(row[spec.cursor] ?? '')
}

function matchesIdentity(row: Row, spec: Spec, identity: string): boolean {
  if (spec.identity === 'composite') {
    const slash = identity.indexOf('/')
    return row.vehicle_id === identity.slice(0, slash) && row.service_name === identity.slice(slash + 1)
  }
  return row[spec.identity] === identity
}

/** The read-only columns a PUT body may not set. */
const SERVER_FACTS = ['id', 'provenance', 'created_at', 'updated_at', 'deleted_at']

export function handleTables(
  engine: Engine,
  method: string,
  pathname: string,
  search: URLSearchParams,
  body: unknown,
): Reply | null {
  for (const table of TABLE_PATHS) {
    const prefix = `/api/${table}/`
    if (!pathname.startsWith(prefix)) continue
    const spec = TABLE_SPECS[table]
    const rest = pathname.slice(prefix.length).replace(/\/$/, '')
    const rows = (engine.tables[table] ??= [])

    if (engine.failingTables.has(table)) {
      return { status: 503, body: { detail: `The engine could not read ${table}.` } }
    }

    if (rest === '') {
      if (method !== 'GET') return { status: 405, body: { detail: `Method ${method} not allowed.` } }
      return list(engine, rows, spec, search)
    }

    const identity = rest.split('/').map(decodeURIComponent).join('/')

    if (method === 'GET') {
      const row = rows.find((candidate) => matchesIdentity(candidate, spec, identity))
      return row ? { status: 200, body: row } : { status: 404, body: { detail: 'No such row.' } }
    }

    if (!spec.writable) {
      return {
        status: 405,
        body: {
          detail: `Nothing was ${method === 'DELETE' ? 'deleted' : 'written'}. ${table} rows come only from the reconciliation gate, never from this API. This route is read-only.`,
        },
      }
    }

    const now = new Date().toISOString()
    const found = rows.find((candidate) => matchesIdentity(candidate, spec, identity))

    if (method === 'DELETE') {
      if (!found) return { status: 404, body: { detail: `Nothing was changed. No ${table} row has ${spec.identity} '${identity}'.` } }
      if (found.deleted_at == null) {
        found.deleted_at = now
        found.updated_at = now
      }
      return { status: 204 }
    }

    if (method === 'PUT') {
      const fields: Row = { ...(body as Row) }
      for (const fact of SERVER_FACTS) delete fields[fact]
      if (found) {
        Object.assign(found, fields, { updated_at: now })
        return { status: 200, body: found }
      }
      const created: Row = {
        id: uuid(),
        provenance: 'USER',
        created_at: now,
        updated_at: now,
        deleted_at: null,
        ...fields,
      }
      if (spec.identity !== 'composite' && spec.identity !== 'id') created[spec.identity] = identity
      rows.push(created)
      return { status: 200, body: created }
    }
  }
  return null
}

function list(engine: Engine, rows: Row[], spec: Spec, search: URLSearchParams): Reply {
  const since = search.get('since')
  const after = search.get('after')
  const activeOnly = spec.tombstones && ['1', 'on', 'true', 'yes'].includes((search.get('active') ?? '').toLowerCase())

  const ordered = rows
    .filter((row) => !activeOnly || row.deleted_at == null)
    .sort((a, b) => cursorOf(a, spec).localeCompare(cursorOf(b, spec)) || String(a.id).localeCompare(String(b.id)))

  const start = ordered.findIndex((row) => {
    if (since === null) return true
    const cursor = cursorOf(row, spec)
    if (cursor > since) return true
    if (cursor < since) return false
    return after === null ? true : String(row.id) > after
  })
  const remaining = start === -1 ? [] : ordered.slice(start)
  const page = remaining.slice(0, engine.pageSize)
  const full = page.length === engine.pageSize
  const last = page[page.length - 1]

  return {
    status: 200,
    body: {
      results: page,
      next: full && last ? cursorOf(last, spec) : null,
      next_after: full && last ? String(last.id) : null,
    },
  }
}
