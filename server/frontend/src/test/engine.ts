import { todayEpochDay } from '../lib/day'
import type { components } from '../api/schema'
import type { Changes, Checklist, ChecklistItem, ChecklistTick, Event, EventSkip, Purchase } from '../api/types'
import { defaultAssistant, handleAssistant, type AssistantState } from './engine-assistant'
import { handleSettings, defaultSettings, type SettingsState } from './engine-settings'
import { handleTables, type Row } from './engine-tables'

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
  name: 'Mia',
}

export interface EngineOptions {
  /** Household name the shell shows. */
  householdName?: string
  /** The household's IANA timezone (`GET /api/households/me`); unset when absent. */
  householdTimezone?: string | null
  /** False answers 401 on `/api/auth/me`: the signed-out state. */
  signedIn?: boolean
  events?: Event[]
  skips?: EventSkip[]
  checklists?: Checklist[]
  items?: ChecklistItem[]
  ticks?: ChecklistTick[]
  /** The household's bought log (`/api/purchases/`). */
  purchases?: Purchase[]
  /** Synced tables by path after `/api/` (`body/bodyweight_logs`); see `engine-tables.ts`. */
  tables?: Record<string, Row[]>
  /** Rows per page of a synced-table list; the real engine's is 500. */
  pageSize?: number
  /** What `GET /api/ledger/spend` answers; an empty month when absent. */
  spend?: Spend
  /** Household members `GET /api/households/me` lists; nobody when absent. */
  members?: Member[]
  /** Join, signup and settings state (`engine-settings.ts`); defaults when absent. */
  settings?: Partial<SettingsState>
  /** The web assistant's state (`engine-assistant.ts`); a Dorothy and no failures when absent. */
  assistant?: Partial<AssistantState>
}

export type Member = components['schemas']['HouseholdMember']

export type Spend = components['schemas']['Spend']

export interface Reply {
  status: number
  body?: unknown
}

export interface Engine {
  signedIn: boolean
  householdName: string
  householdTimezone: string | null
  events: Mutable<Event>[]
  skips: Mutable<EventSkip>[]
  checklists: Mutable<Checklist>[]
  items: Mutable<ChecklistItem>[]
  ticks: Mutable<ChecklistTick>[]
  purchases: Mutable<Purchase>[]
  tables: Record<string, Row[]>
  pageSize: number
  /** The body of `GET /api/ledger/spend`. Its categories' `target_cents` follow
   * the live `ledger/budget_targets` rows, so setting a target round-trips. */
  spend: Spend
  /** The household's members, for `GET /api/households/me`. */
  members: Member[]
  /** What the join, signup and settings routes answer from (`engine-settings.ts`). */
  settings: SettingsState
  /** The web assistant's answers (`engine-assistant.ts`): companion, mint, tools. */
  assistant: AssistantState
  /** Awaited before a request is answered: hold one page back to see a screen
   * while a paged read is half done. Resolve to let it through. */
  delay?: (method: string, pathname: string, search: URLSearchParams) => Promise<void> | undefined
  /** Table paths whose reads answer 503: the engine is up but that read failed. */
  failingTables: Set<string>
  /** A forced reply for a `METHOD /path` (or a `METHOD /prefix*`), ahead of every
   * handler: a refused write. */
  refusals: Record<string, Reply>
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
  /** Every write the engine answered, in order, with the body it was sent: what a
   * test reads to say "the skip went first, then the POST". */
  writes: { method: string; pathname: string; body: unknown }[]
  /** The query string each `METHOD /path` was last sent with (a write's `?today=`). */
  searches: Record<string, string>
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
    remind_minutes_before: null,
    visibility: 'shared',
    ...overrides,
  }
}

export function makePurchase(overrides: Partial<Purchase> & { item: string }): Purchase {
  const day = overrides.bought_on ?? todayEpochDay()
  return {
    id: uuid(),
    bought_on: day,
    bought_on_date: isoForDay(day),
    logged_at: STAMP,
    logged_by: 'Mia',
    logged_by_me: true,
    store: null,
    price_cents: null,
    price_note: null,
    quantity_note: null,
    visibility: 'shared',
    source: 'MANUAL',
    tick: null,
    deleted_at: null,
    sync_id: null,
    ...overrides,
  }
}

function isoForDay(day: number): string {
  return new Date(day * 86_400_000).toISOString().slice(0, 10)
}

const PRICE_NOTE = 'entered by hand; never checked against the bank or added into ledger figures'

/** The engine's matcher in miniature: every word of the query appears in the
 * entry, plurals folded (`purchases/matching.py`). */
function wordsOf(text: string): string[] {
  const fold = (word: string) =>
    word.length > 4 && /(sh|ch|ss|x|z)es$/.test(word)
      ? word.slice(0, -2)
      : word.length > 3 && word.endsWith('s') && !word.endsWith('ss')
        ? word.slice(0, -1)
        : word
  return text
    .toLowerCase()
    .split(/[^a-z0-9']+/)
    .filter(Boolean)
    .map(fold)
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
    visibility: 'shared',
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
    householdTimezone: options.householdTimezone ?? null,
    events: options.events ?? [],
    skips: options.skips ?? [],
    checklists: options.checklists ?? [],
    items: options.items ?? [],
    ticks: options.ticks ?? [],
    purchases: options.purchases ?? [],
    tables: options.tables ?? {},
    pageSize: options.pageSize ?? 500,
    spend: options.spend ?? emptySpend(),
    members: options.members ?? [],
    settings: { ...defaultSettings(), ...options.settings },
    assistant: { ...defaultAssistant(), ...options.assistant },
    failingTables: new Set(),
    refusals: {},
    down: false,
    changesFailing: false,
    householdFailing: false,
    calls: {},
    writes: [],
    searches: {},
    unhandled: [],

    handle(method, pathname, search, body) {
      const key = `${method} ${pathname}`
      engine.calls[key] = (engine.calls[key] ?? 0) + 1
      const now = new Date().toISOString()

      if (method !== 'GET') engine.writes.push({ method, pathname, body })
      engine.searches[key] = search.toString()

      // An exact `METHOD /path`, or a prefix ending in `*` (`PUT /api/places/*`):
      // a write whose identity the test cannot know ahead of time.
      for (const [pattern, reply] of Object.entries(engine.refusals)) {
        if (pattern === key || (pattern.endsWith('*') && key.startsWith(pattern.slice(0, -1)))) return reply
      }

      if (method === 'GET' && pathname === '/api/auth/me') {
        return engine.signedIn
          ? { status: 200, body: { ...ME, name: engine.settings.name } }
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
        return {
          status: 200,
          body: {
            id: 'h1',
            name: engine.householdName,
            timezone: engine.householdTimezone,
            members: engine.members,
          },
        }
      }
      if (method === 'GET' && pathname === '/api/changes') {
        if (engine.changesFailing) {
          return { status: 503, body: { detail: 'The engine could not read the household.' } }
        }
        const changes: Changes = {
          server_time: now,
          events: engine.events,
          event_skips: engine.skips,
          checklists: engine.checklists,
          checklist_items: engine.items,
          checklist_ticks: engine.ticks,
        }
        return { status: 200, body: changes }
      }

      if (method === 'POST' && pathname === '/api/events') {
        const sent = body as Partial<Event>
        if (!sent.title || sent.title.trim() === '') {
          return { status: 400, body: { title: ['title cannot be blank.'] } }
        }
        // A retried create with the same `origin_guid` is a no-op, answered 200
        // with the row that is already there (the real engine's idempotency).
        const existing = sent.origin_guid
          ? engine.events.find((event) => event.origin_guid === sent.origin_guid)
          : undefined
        if (existing) return { status: 200, body: existing }
        const created = makeEvent({
          ...sent,
          title: sent.title,
          created_at: now,
          updated_at: now,
        })
        engine.events.push(created)
        return { status: 201, body: created }
      }

      let match = pathname.match(/^\/api\/events\/([^/]+)$/)
      if (match && method === 'PATCH') {
        const event = engine.events.find((candidate) => candidate.id === match![1])
        if (!event) return { status: 404, body: { detail: 'No such event.' } }
        Object.assign(event, body as Partial<Event>, { updated_at: now })
        return { status: 200, body: event }
      }
      if (match && method === 'DELETE') {
        const event = engine.events.find((candidate) => candidate.id === match![1])
        if (event && event.deleted_at === null) {
          event.deleted_at = now
          event.updated_at = now
        }
        return { status: 204 }
      }

      match = pathname.match(/^\/api\/events\/([^/]+)\/skips$/)
      if (match && method === 'GET') {
        return { status: 200, body: engine.skips.filter((skip) => skip.event === match![1]) }
      }
      if (match && method === 'POST') {
        if (!engine.events.some((event) => event.id === match![1])) {
          return { status: 404, body: { detail: `No event with id ${match[1]}. No skip was read or written.` } }
        }
        const skipDate = (body as { skip_date: string }).skip_date
        const existing = engine.skips.find((skip) => skip.event === match![1] && skip.skip_date === skipDate)
        if (existing) {
          existing.deleted_at = null
          existing.updated_at = now
          return { status: 200, body: existing }
        }
        const created: Mutable<EventSkip> = {
          id: uuid(),
          event: match[1],
          skip_date: skipDate,
          created_at: now,
          updated_at: now,
          deleted_at: null,
        }
        engine.skips.push(created)
        return { status: 201, body: created }
      }

      match = pathname.match(/^\/api\/events\/([^/]+)\/skips\/(\d{4}-\d{2}-\d{2})$/)
      if (match && method === 'DELETE') {
        for (const skip of engine.skips) {
          if (skip.event === match[1] && skip.skip_date === match[2] && skip.deleted_at === null) {
            skip.deleted_at = now
            skip.updated_at = now
          }
        }
        return { status: 204 }
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
      if (match && method === 'PATCH') {
        const list = engine.checklists.find((candidate) => candidate.id === match![1])
        if (!list) return { status: 404, body: { detail: 'No such list.' } }
        Object.assign(list, body as Partial<Checklist>, { updated_at: now })
        return { status: 200, body: list }
      }
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
        // ADR 0055: a tick on the shared Groceries list is a purchase.
        const tickedItem = engine.items.find((candidate) => candidate.id === match![2])
        const tickedList = engine.checklists.find((candidate) => candidate.id === match![1])
        if (tickedItem && tickedList && tickedList.name.trim().toLowerCase() === 'groceries') {
          engine.purchases.push(
            makePurchase({
              item: tickedItem.text,
              bought_on: day,
              source: 'GROCERIES_TICK',
              tick: match[2],
              logged_by: ME.name,
              logged_at: now,
            }),
          )
        }
        return { status: 204 }
      }

      match = pathname.match(/^\/api\/checklists\/([^/]+)\/items\/([^/]+)\/tick\/(-?\d+)$/)
      if (match && method === 'DELETE') {
        const day = Number(match[3])
        for (const tick of engine.ticks) {
          if (tick.item === match[2] && tick.day === day) tick.deleted_at = now
        }
        // An untick on the tick's own local day removes the entry it made; the
        // caller's day arrives as `?today=`, and without it the engine falls
        // back to the UTC date, as the real one does.
        const callerToday = search.has('today')
          ? Number(search.get('today'))
          : Math.floor(Date.now() / 86_400_000)
        if (callerToday === day) {
          for (const entry of engine.purchases) {
            if (entry.source === 'GROCERIES_TICK' && entry.tick === match[2] && entry.bought_on === day) {
              entry.deleted_at = now
            }
          }
        }
        return { status: 204 }
      }

      if (pathname === '/api/purchases/' && method === 'GET') {
        const q = (search.get('q') ?? '').trim()
        const source = search.get('source')
        const limit = Number(search.get('limit') ?? 100)
        const wanted = wordsOf(q)
        const matched = engine.purchases
          .filter((entry) => entry.deleted_at === null)
          .filter((entry) => source === null || entry.source === source)
          .filter((entry) => {
            if (wanted.length === 0) return true
            const have = wordsOf(entry.item)
            return wanted.every((word) => have.includes(word))
          })
          .sort((a, b) => b.bought_on - a.bought_on)
        const results = matched.slice(0, limit)
        let message: string | null = null
        if (results.length === 0) {
          message = q ? `I have no record of buying ${q}.` : 'Nothing has been logged as bought yet.'
        }
        return { status: 200, body: { results, truncated: matched.length > limit, message } }
      }
      if (pathname === '/api/purchases/' && method === 'POST') {
        const sent = body as Partial<Purchase>
        if (!sent.item || sent.item.trim() === '') {
          return { status: 400, body: { item: ['This field may not be blank.'] } }
        }
        const existing = sent.sync_id ? engine.purchases.find((entry) => entry.sync_id === sent.sync_id) : undefined
        if (existing) return { status: 200, body: existing }
        const created = makePurchase({
          item: sent.item.trim(),
          bought_on: sent.bought_on,
          store: sent.store ?? null,
          price_cents: sent.price_cents ?? null,
          price_note: sent.price_cents == null ? null : PRICE_NOTE,
          quantity_note: sent.quantity_note ?? null,
          visibility: sent.visibility === 'private' ? 'private' : 'shared',
          logged_by: ME.name,
          logged_at: now,
          sync_id: sent.sync_id ?? null,
        })
        engine.purchases.push(created)
        return { status: 201, body: created }
      }
      const purchaseMatch = pathname.match(/^\/api\/purchases\/([^/]+)$/)
      if (purchaseMatch && method === 'PATCH') {
        const entry = engine.purchases.find(
          (candidate) => candidate.id === purchaseMatch[1] && candidate.deleted_at === null,
        )
        if (!entry) return { status: 404, body: { detail: 'No such entry. Nothing was changed.' } }
        const sent = body as Partial<Purchase>
        Object.assign(entry, sent, { bought_on_date: isoForDay(sent.bought_on ?? entry.bought_on) })
        entry.price_note = entry.price_cents == null ? null : PRICE_NOTE
        return { status: 200, body: entry }
      }
      if (purchaseMatch && method === 'DELETE') {
        const entry = engine.purchases.find((candidate) => candidate.id === purchaseMatch[1])
        if (!entry) return { status: 404, body: { detail: 'No such entry. Nothing was changed.' } }
        entry.deleted_at = now
        return { status: 204 }
      }

      if (method === 'GET' && pathname === '/api/ledger/spend') {
        return { status: 200, body: spendWithTargets(engine) }
      }

      const assistantReply = handleAssistant(engine, method, pathname, body)
      if (assistantReply) return assistantReply

      const settingsReply = handleSettings(engine, ME.user_id, ME.email, method, pathname, body)
      if (settingsReply) return settingsReply

      const tableReply = handleTables(engine, method, pathname, search, body)
      if (tableReply) return tableReply

      engine.unhandled.push(key)
      return {
        status: 501,
        body: { detail: `The fake engine has no route for ${key}. Add it to src/test/engine.ts.` },
      }
    },
  }
  return engine
}

export function emptySpend(month = new Date().toISOString().slice(0, 7)): Spend {
  return {
    month,
    currency: 'USD',
    accounts: [],
    categories: [],
    uncategorised_cents: 0,
    uncategorised_unverified: false,
    excluded: {
      not_spending_cents: 0,
      not_spending_categories: [],
      own_account_moves_cents: 0,
      early_charges_moved_cents: 0,
      early_charges_counted_here_cents: 0,
      early_charges_counted_next_month_cents: 0,
    },
    complete: false,
  }
}

/** The spend body, with each category's target read from the live target rows
 * in force for the month (the latest one effective on or before its first day). */
function spendWithTargets(engine: Engine): Spend {
  const first = `${engine.spend.month}-01`
  const targets = (engine.tables['ledger/budget_targets'] ?? []).filter(
    (row) => row.deleted_at == null && String(row.effective_from_month) <= first,
  )
  return {
    ...engine.spend,
    categories: engine.spend.categories.map((line) => {
      const rows = targets
        .filter((row) => row.category === line.category)
        .sort((a, b) => String(a.effective_from_month).localeCompare(String(b.effective_from_month)))
      const latest = rows[rows.length - 1]
      return latest ? { ...line, target_cents: latest.amount_cents as number } : line
    }),
  }
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
    await engine.delay?.(method, url.pathname, url.searchParams)
    let body: unknown = undefined
    if (method !== 'GET' && method !== 'DELETE') {
      const text = await input.text()
      body = text === '' ? undefined : JSON.parse(text)
    }
    return toResponse(engine.handle(method, url.pathname, url.searchParams, body))
  }
}
