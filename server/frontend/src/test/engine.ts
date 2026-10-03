import { todayEpochDay } from '../lib/day'
import type { Changes, Checklist, ChecklistItem, ChecklistTick, Event } from '../api/types'

/**
 * The fake engine: what every web test and every screenshot talks to instead of
 * Django.
 *
 * It answers HTTP, not hooks. A test renders a route, the route calls the
 * generated client, the client calls `fetch`, and `fetch` lands here and gets a
 * real `Response` - so the request path under test is the production one right
 * up to the wire (spec "Testing Decisions": no mocking of hooks or components).
 *
 * Deliberately free of vitest and DOM imports so two different harnesses can
 * share it: `engineFetch` adapts it to `fetch` for vitest, and
 * `e2e/shots.spec.ts` adapts the same object to Playwright's `page.route`.
 * Every later ticket grows `handle` with the routes it needs; a route this
 * engine does not know answers 501 and is recorded in `unhandled`, so a test
 * that wanders somewhere unplanned fails by name rather than by timeout.
 *
 * The state is plain arrays, mutated by the write routes, so a tick round-trips:
 * POST it and the next `GET /api/changes` really contains it.
 */

type Mutable<T> = { -readonly [K in keyof T]: T[K] }

export const ME = {
  user_id: '3f2b1c88-0000-4000-8000-0123456789ab',
  email: 'mia@example.test',
  device_name: '',
}

export interface EngineOptions {
  /** Household name the shell shows. */
  householdName?: string
  /** False answers 401 on `/api/auth/me`: the signed-out state. */
  signedIn?: boolean
  events?: Event[]
  checklists?: Checklist[]
  items?: ChecklistItem[]
  ticks?: ChecklistTick[]
}

export interface Reply {
  status: number
  body?: unknown
}

export interface Engine {
  signedIn: boolean
  householdName: string
  events: Mutable<Event>[]
  checklists: Mutable<Checklist>[]
  items: Mutable<ChecklistItem>[]
  ticks: Mutable<ChecklistTick>[]
  /** True makes every request fail the way a dead network does (a rejected
   * `fetch`), not the way a sick server does. */
  down: boolean
  /** True makes only `GET /api/changes` answer 503, leaving auth working: the
   * "engine is up but the data read failed" case. */
  changesFailing: boolean
  /** True makes only `GET /api/households/me` answer 503: the shell cannot name
   * the household but the rest of the page may still load. */
  householdFailing: boolean
  /** How many times each `METHOD /path` was asked for, for refetch assertions. */
  calls: Record<string, number>
  /** Requests no handler claimed. A test can assert this stays empty. */
  unhandled: string[]
  handle(method: string, pathname: string, search: URLSearchParams, body: unknown): Reply
}

/** Epoch-ms of "today at `hour`:`minute`" in the machine's local zone. */
export function todayAt(hour: number, minute = 0, dayOffset = 0): string {
  const d = new Date()
  d.setDate(d.getDate() + dayOffset)
  d.setHours(hour, minute, 0, 0)
  return d.toISOString()
}

let counter = 0
function uuid(): string {
  counter += 1
  return `00000000-0000-4000-8000-${counter.toString(16).padStart(12, '0')}`
}

const STAMP = '2026-01-01T00:00:00Z'

export function makeEvent(overrides: Partial<Event> & { title: string }): Event {
  return {
    id: uuid(),
    starts_at: null,
    ends_at: null,
    all_day: false,
    location: null,
    notes: null,
    source: 'legion',
    google_event_id: null,
    done: false,
    done_at: null,
    sort_order: null,
    trigger_place_label: null,
    repeat_kind: null,
    repeat_every: null,
    repeat_days_of_week: null,
    repeat_day: null,
    repeat_month: null,
    repeat_end_kind: null,
    repeat_end_date: null,
    repeat_end_count: null,
    exact: true,
    exact_downgraded: false,
    missed_at: null,
    missed_dismissed_at: null,
    logged_at: null,
    provenance: 'DETERMINISTIC',
    created_at: STAMP,
    updated_at: STAMP,
    deleted_at: null,
    origin_guid: null,
    structured_meta: null,
    kind: 'event',
    ...overrides,
  }
}

export function makeChecklist(overrides: Partial<Checklist> & { name: string }): Checklist {
  return {
    id: uuid(),
    schedule_kind: null,
    schedule_every: null,
    schedule_days_of_week: null,
    sort_order: 0,
    archived: false,
    created_at: STAMP,
    updated_at: STAMP,
    deleted_at: null,
    sync_id: null,
    ...overrides,
  }
}

export function makeItem(
  checklist: Checklist,
  text: string,
  overrides: Partial<ChecklistItem> = {},
): ChecklistItem {
  return {
    id: uuid(),
    checklist: checklist.id,
    text,
    sort_order: 0,
    created_at: STAMP,
    updated_at: STAMP,
    deleted_at: null,
    sync_id: null,
    measure_unit: null,
    measure_target: null,
    measure_direction: null,
    ...overrides,
  }
}

/**
 * A household with a believable day in it. Used by the screenshots and by tests
 * that only need "some data". Times are relative to now so the page always has
 * a today to show, whenever it is run.
 */
export function seedHousehold(): Pick<EngineOptions, 'events' | 'checklists' | 'items' | 'ticks'> {
  const groceries = makeChecklist({ name: 'Groceries', sort_order: 0 })
  const hardware = makeChecklist({ name: 'Hardware store', sort_order: 1 })
  const morning = makeChecklist({
    name: 'Morning',
    sort_order: 2,
    schedule_kind: 'DAILY',
    schedule_every: 1,
  })

  const grocery = ['Oat milk', 'Eggs', 'Spinach', 'Bananas', 'Greek yogurt', 'Sourdough loaf'].map(
    (text, index) => makeItem(groceries, text, { sort_order: index }),
  )
  const hardwareItems = ['Picture hooks', 'Wood glue'].map((text, index) =>
    makeItem(hardware, text, { sort_order: index }),
  )
  const morningItems = ['Take vitamins', 'Water the plants'].map((text, index) =>
    makeItem(morning, text, { sort_order: index }),
  )

  const events: Event[] = [
    makeEvent({ title: 'Soccer pickup', starts_at: todayAt(11, 45), location: 'Maplewood fields' }),
    makeEvent({ title: 'Dentist', starts_at: todayAt(15, 15), location: 'Riverside Dental' }),
    makeEvent({ title: 'Pottery class', starts_at: todayAt(17, 30), location: 'Studio 4' }),
    makeEvent({
      title: 'Reply to the picnic email',
      kind: 'task',
      starts_at: todayAt(18, 0),
    }),
    makeEvent({
      title: 'Return the library books',
      kind: 'task',
      starts_at: todayAt(18, 0, -2),
    }),
    makeEvent({ title: 'Farmers market', starts_at: todayAt(9, 0, 1) }),
    makeEvent({ title: 'Oil change', starts_at: todayAt(8, 30, 3) }),
  ]

  const tick = (itemId: string): ChecklistTick => ({
    id: uuid(),
    item: itemId,
    day: todayEpochDay(),
    ticked_at: STAMP,
    updated_at: STAMP,
    deleted_at: null,
    sync_id: null,
    value: null,
    source: 'USER_REPORTED',
  })

  return {
    events,
    checklists: [groceries, hardware, morning],
    items: [...grocery, ...hardwareItems, ...morningItems],
    // One grocery and one morning item already ticked, so a screenshot shows
    // both what a ticked row looks like and what is still to do.
    ticks: [tick(grocery[1].id), tick(morningItems[0].id)],
  }
}

export function createEngine(options: EngineOptions = {}): Engine {
  const engine: Engine = {
    signedIn: options.signedIn ?? true,
    householdName: options.householdName ?? 'The Test House',
    events: options.events ?? [],
    checklists: options.checklists ?? [],
    items: options.items ?? [],
    ticks: options.ticks ?? [],
    down: false,
    changesFailing: false,
    householdFailing: false,
    calls: {},
    unhandled: [],

    handle(method, pathname, _search, body) {
      const key = `${method} ${pathname}`
      engine.calls[key] = (engine.calls[key] ?? 0) + 1
      const now = new Date().toISOString()

      if (method === 'GET' && pathname === '/api/auth/me') {
        return engine.signedIn
          ? { status: 200, body: ME }
          : { status: 401, body: { detail: 'Not signed in.' } }
      }
      if (method === 'POST' && pathname === '/api/auth/session/login') {
        engine.signedIn = true
        return { status: 200, body: ME }
      }
      if (method === 'POST' && pathname === '/api/auth/session/logout') {
        engine.signedIn = false
        return { status: 204 }
      }
      if (method === 'GET' && pathname === '/api/households/me') {
        if (engine.householdFailing) {
          return { status: 503, body: { detail: 'The engine could not read the household.' } }
        }
        return { status: 200, body: { id: 'h1', name: engine.householdName, members: [] } }
      }
      if (method === 'GET' && pathname === '/api/changes') {
        if (engine.changesFailing) {
          return { status: 503, body: { detail: 'The engine could not read the household.' } }
        }
        const changes: Changes = {
          server_time: now,
          events: engine.events,
          checklists: engine.checklists,
          checklist_items: engine.items,
          checklist_ticks: engine.ticks,
        }
        return { status: 200, body: changes }
      }

      let match = pathname.match(/^\/api\/events\/([^/]+)$/)
      if (match && method === 'PATCH') {
        const event = engine.events.find((candidate) => candidate.id === match![1])
        if (!event) return { status: 404, body: { detail: 'No such event.' } }
        Object.assign(event, body as Partial<Event>, { updated_at: now })
        return { status: 200, body: event }
      }

      if (method === 'POST' && pathname === '/api/checklists/') {
        const created = makeChecklist({
          name: (body as { name: string }).name,
          sort_order: engine.checklists.length,
          created_at: now,
          updated_at: now,
        })
        engine.checklists.push(created)
        return { status: 201, body: created }
      }

      match = pathname.match(/^\/api\/checklists\/([^/]+)$/)
      if (match && method === 'DELETE') {
        const list = engine.checklists.find((candidate) => candidate.id === match![1])
        if (list) list.deleted_at = now
        return { status: 204 }
      }

      match = pathname.match(/^\/api\/checklists\/([^/]+)\/items$/)
      if (match && method === 'POST') {
        const list = engine.checklists.find((candidate) => candidate.id === match![1])
        if (!list) return { status: 404, body: { detail: 'No such list.' } }
        const created = makeItem(list, (body as { text: string }).text, {
          sort_order: engine.items.length,
          created_at: now,
          updated_at: now,
        })
        engine.items.push(created)
        return { status: 201, body: created }
      }

      match = pathname.match(/^\/api\/checklists\/([^/]+)\/items\/([^/]+)$/)
      if (match && method === 'DELETE') {
        const item = engine.items.find((candidate) => candidate.id === match![2])
        if (item) item.deleted_at = now
        return { status: 204 }
      }

      match = pathname.match(/^\/api\/checklists\/([^/]+)\/items\/([^/]+)\/tick$/)
      if (match && method === 'POST') {
        const day = (body as { day: number }).day
        const existing = engine.ticks.find(
          (tick) => tick.item === match![2] && tick.day === day,
        )
        if (existing) {
          existing.deleted_at = null
          existing.updated_at = now
        } else {
          engine.ticks.push({
            id: uuid(),
            item: match[2],
            day,
            ticked_at: now,
            updated_at: now,
            deleted_at: null,
            sync_id: null,
            value: null,
            source: 'USER_REPORTED',
          })
        }
        return { status: 204 }
      }

      match = pathname.match(/^\/api\/checklists\/([^/]+)\/items\/([^/]+)\/tick\/(-?\d+)$/)
      if (match && method === 'DELETE') {
        const day = Number(match[3])
        for (const tick of engine.ticks) {
          if (tick.item === match[2] && tick.day === day) tick.deleted_at = now
        }
        return { status: 204 }
      }

      engine.unhandled.push(key)
      return {
        status: 501,
        body: { detail: `The fake engine has no route for ${key}. Add it to src/test/engine.ts.` },
      }
    },
  }
  return engine
}

/** A `Reply` as a real `Response`, the way Django would send it. */
export function toResponse(reply: Reply): Response {
  if (reply.body === undefined || reply.status === 204) {
    return new Response(null, { status: reply.status })
  }
  return new Response(JSON.stringify(reply.body), {
    status: reply.status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/**
 * `fetch`-shaped adapter for vitest: `vi.stubGlobal('fetch', engineFetch(engine))`.
 * The generated client always hands `fetch` a `Request`, so that is the only
 * input shape it has to read.
 */
export function engineFetch(engine: Engine) {
  return async (input: Request): Promise<Response> => {
    if (engine.down) throw new TypeError('network error')
    const url = new URL(input.url)
    const method = input.method.toUpperCase()
    let body: unknown = undefined
    if (method !== 'GET' && method !== 'DELETE') {
      const text = await input.text()
      body = text === '' ? undefined : JSON.parse(text)
    }
    return toResponse(engine.handle(method, url.pathname, url.searchParams, body))
  }
}
