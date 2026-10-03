import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'

import { CHANGES_KEY } from '@/api/queries'
import { invalidateSpend } from '@/api/ledger'
import { HOME_COPY, NOTHING_TO_SHOW_PINNED } from '@/lib/home-copy'
import { OVERDUE_SHOWN } from '@/screens/home-family'
import { PINS_KEY } from '@/lib/pins'
import { seedSpend } from '@/test/ledger-seed'
import { stubSurface } from '@/test/surface'
import {
  ME,
  createEngine,
  makeChecklist,
  makeEvent,
  makeItem,
  seedHousehold,
  todayAt,
  type Engine,
} from '@/test/engine'
import { renderApp } from '@/test/render-app'

/**
 * Mia's Home (web-revamp 10, spec D6): the day in the order she asks the
 * questions, each section answering for itself. Every test drives the real
 * route against the fake engine and reads what a person reads.
 */

const SPEND_DOWN = { status: 503, body: { detail: 'The engine could not read the ledger.' } }

function household(extra: Parameters<typeof createEngine>[0] = {}): Engine {
  return createEngine({ ...seedHousehold(), spend: seedSpend(), ...extra })
}

async function home(engine: Engine) {
  const view = renderApp('/', engine, 'family')
  await screen.findByRole('heading', { name: 'On today' }, { timeout: 8000 })
  return view
}

function section(name: string): HTMLElement {
  return screen.getByRole('heading', { name }).closest('section') as HTMLElement
}

describe('the sections', () => {
  test('arrive in the order of spec D6, under a greeting and the date', async () => {
    await home(household())
    await screen.findByText('$642.55')

    expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(
      /^(Good morning|Good afternoon|Good evening|Hello)/,
    )
    const order = screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent)
    expect(order).toEqual(['On today', 'Still not done', 'To do today', 'Lists', 'Spent this month'])
    // The "+" is the one the event sheet opens from, and it is on the page.
    expect(screen.getByRole('button', { name: 'New event' })).toBeInTheDocument()
  })

  test('greets the signed-in member by name when the household lists them, and the date', async () => {
    await home(
      household({
        members: [
          { role: 'owner', user_id: 'someone-else', email: 'k@example.test', name: 'Kevin', joined_at: '2026-01-01T00:00:00Z' },
          { role: 'member', user_id: ME.user_id, email: ME.email, name: 'Mia', joined_at: '2026-01-02T00:00:00Z' },
        ],
      }),
    )
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 }).textContent).toMatch(/, Mia$/))
    const date = new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })
    expect(screen.getByText(date)).toBeInTheDocument()
  })

  test('On today holds events and the viewer\'s own private ones, and tasks are in To do instead', async () => {
    await home(
      household({
        events: [
          makeEvent({ title: 'Dentist', starts_at: todayAt(15, 15) }),
          makeEvent({ title: 'Therapy', starts_at: todayAt(16, 30), visibility: 'private' }),
          makeEvent({ title: 'Reply to the picnic email', kind: 'task', starts_at: todayAt(18, 0) }),
        ],
      }),
    )
    const onToday = section('On today')
    expect(within(onToday).getByText('Dentist')).toBeInTheDocument()
    expect(within(onToday).getByText('Therapy')).toBeInTheDocument()
    expect(within(onToday).queryByText('Reply to the picnic email')).not.toBeInTheDocument()
    expect(within(section('To do today')).getByText('Reply to the picnic email')).toBeInTheDocument()
  })

  test('Still not done shows three rows and "and N more" linking to the calendar', async () => {
    const overdue = [1, 2, 3, 4, 5].map((n) =>
      makeEvent({ title: `Overdue ${n}`, kind: 'task', starts_at: todayAt(18, 0, -n) }),
    )
    await home(household({ events: overdue }))
    const still = section('Still not done')
    expect(within(still).getAllByRole('checkbox')).toHaveLength(OVERDUE_SHOWN)
    expect(within(still).getByText('5 past their date')).toBeInTheDocument()
    // An overdue row says which DAY it was due, not only a clock time.
    expect(within(still).getAllByText(/^Was due [A-Z][a-z]{2}, [A-Z][a-z]{2} \d+, /)).toHaveLength(OVERDUE_SHOWN)
    const more = within(still).getByRole('link', { name: 'and 2 more' })
    expect(more).toHaveAttribute('href', '/calendar')
  })

  test('exactly three overdue rows has no "and N more"', async () => {
    const overdue = [1, 2, 3].map((n) =>
      makeEvent({ title: `Overdue ${n}`, kind: 'task', starts_at: todayAt(18, 0, -n) }),
    )
    await home(household({ events: overdue }))
    expect(within(section('Still not done')).queryByRole('link', { name: /more/ })).not.toBeInTheDocument()
  })

  test('To do today ticks a checklist item at once and the engine receives the tick', async () => {
    const engine = household()
    await home(engine)
    const toDo = section('To do today')
    // The picnic task and Water the plants are open; Take vitamins is ticked.
    expect(within(toDo).getByText('2 left')).toBeInTheDocument()

    const box = within(toDo).getByRole('checkbox', { name: /Mark "Water the plants" done/ })
    expect(box).not.toBeChecked()
    fireEvent.click(box)
    await waitFor(() => expect(box).toBeChecked())
    expect(within(toDo).getByText('1 left')).toBeInTheDocument()
    await waitFor(() =>
      expect(engine.writes.some((write) => write.method === 'POST' && write.pathname.endsWith('/tick'))).toBe(true),
    )
  })
})

describe('three sentences per section', () => {
  test('no two of the fifteen are the same', () => {
    const all = Object.values(HOME_COPY).flatMap((copy) => [copy.empty, copy.unreachable, copy.stale('2 minutes ago')])
    expect(new Set(all).size).toBe(all.length)
    expect(all).toHaveLength(15)
  })

  test('empty: each section says there is nothing, in its own words', async () => {
    await home(createEngine({}))
    expect(await screen.findByText(HOME_COPY.spend.empty)).toBeInTheDocument()
    expect(within(section('On today')).getByText(HOME_COPY['on-today'].empty)).toBeInTheDocument()
    expect(within(section('Still not done')).getByText(HOME_COPY.overdue.empty)).toBeInTheDocument()
    expect(within(section('To do today')).getByText(HOME_COPY['to-do'].empty)).toBeInTheDocument()
    expect(within(section('Lists')).getByText(HOME_COPY.pinned.empty)).toBeInTheDocument()
  })

  test('unreachable: each section says it could not reach the engine, and nothing says it is empty or $0', async () => {
    const engine = household()
    engine.changesFailing = true
    engine.refusals['GET /api/ledger/spend'] = SPEND_DOWN
    renderApp('/', engine, 'family')

    expect(await screen.findByText(HOME_COPY.spend.unreachable)).toBeInTheDocument()
    expect(within(section('On today')).getByText(HOME_COPY['on-today'].unreachable)).toBeInTheDocument()
    expect(within(section('Still not done')).getByText(HOME_COPY.overdue.unreachable)).toBeInTheDocument()
    expect(within(section('To do today')).getByText(HOME_COPY['to-do'].unreachable)).toBeInTheDocument()
    expect(within(section('Lists')).getByText(HOME_COPY.pinned.unreachable)).toBeInTheDocument()

    for (const empty of [
      HOME_COPY['on-today'].empty,
      HOME_COPY.overdue.empty,
      HOME_COPY['to-do'].empty,
      HOME_COPY.pinned.empty,
      HOME_COPY.spend.empty,
    ]) {
      expect(screen.queryByText(empty)).not.toBeInTheDocument()
    }
    expect(screen.queryByText(/\$\d/)).not.toBeInTheDocument()
  })

  test('stale: a failed refresh keeps what was read and says how old it is, section by section', async () => {
    const engine = household()
    const { queryClient } = await home(engine)
    await screen.findByText('$642.55')
    expect(screen.queryByText(/^Could not refresh\./)).not.toBeInTheDocument()

    engine.changesFailing = true
    engine.refusals['GET /api/ledger/spend'] = SPEND_DOWN
    await act(async () => {
      await Promise.allSettled([
        queryClient.invalidateQueries({ queryKey: CHANGES_KEY }),
        invalidateSpend(queryClient),
      ])
    })

    // The rows are still there.
    expect(within(section('On today')).getByText('Soccer pickup')).toBeInTheDocument()
    expect(screen.getByText('$642.55')).toBeInTheDocument()
    // And each section says its own sentence about its own data.
    const stale = /^Could not refresh\. /
    expect(await within(section('On today')).findByText(stale)).toHaveTextContent(/What is on today was last read just now/)
    expect(within(section('Still not done')).getByText(stale)).toHaveTextContent(/overdue things was last read/)
    expect(within(section('To do today')).getByText(stale)).toHaveTextContent(/What is due today was last read/)
    expect(screen.getByText(/These lists were last read/)).toBeInTheDocument()
    expect(screen.getByText(/These figures were last read just now and may have changed since\./)).toBeInTheDocument()
  })

  test('one read failing does not take the other section down with it', async () => {
    const engine = household()
    engine.refusals['GET /api/ledger/spend'] = SPEND_DOWN
    await home(engine)
    expect(await screen.findByText(HOME_COPY.spend.unreachable)).toBeInTheDocument()
    expect(within(section('On today')).getByText('Soccer pickup')).toBeInTheDocument()
  })
})

describe('pinned lists', () => {
  test('a pinned list shows its first three open items and a count of the rest', async () => {
    const engine = household()
    const groceries = engine.checklists.find((list) => list.name === 'Groceries')!
    window.localStorage.setItem(PINS_KEY, JSON.stringify([groceries.id]))
    await home(engine)

    expect(screen.getByRole('heading', { name: 'Pinned lists' })).toBeInTheDocument()
    const card = screen.getByRole('link', { name: /Groceries/ })
    // Six items, one ticked (Eggs): five open, three shown.
    expect(within(card).getByText('Oat milk')).toBeInTheDocument()
    expect(within(card).getByText('Spinach')).toBeInTheDocument()
    expect(within(card).getByText('Bananas')).toBeInTheDocument()
    expect(within(card).queryByText('Eggs')).not.toBeInTheDocument()
    expect(within(card).queryByText('Greek yogurt')).not.toBeInTheDocument()
    expect(within(card).getByText('and 2 more')).toBeInTheDocument()
    expect(within(card).getByText('Shared')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Hardware store/ })).not.toBeInTheDocument()
  })

  test('nothing pinned: unscheduled lists with open items, most recently changed first, at most two', async () => {
    const stamp = (day: number) => `2026-09-${String(day).padStart(2, '0')}T00:00:00Z`
    const old = makeChecklist({ name: 'Oldest', updated_at: stamp(1) })
    const mid = makeChecklist({ name: 'Middle', updated_at: stamp(5) })
    // The list row is old but an item was added to it last: that is the recent one.
    const fresh = makeChecklist({ name: 'Freshest', updated_at: stamp(2) })
    const daily = makeChecklist({ name: 'Morning', schedule_kind: 'DAILY', schedule_every: 1, updated_at: stamp(20) })
    const done = makeChecklist({ name: 'All done', updated_at: stamp(21) })
    const engine = createEngine({
      checklists: [old, mid, fresh, daily, done],
      items: [
        makeItem(old, 'a'),
        makeItem(mid, 'b'),
        makeItem(fresh, 'c', { updated_at: stamp(9) }),
        makeItem(daily, 'd'),
        makeItem(done, 'e'),
      ],
      spend: seedSpend(),
    })
    // "All done" has its one item ticked, so it has nothing open. For a plain list
    // any live tick counts, whichever day it was made on.
    const doneItem = engine.items.find((item) => item.checklist === done.id)!
    engine.ticks.push({
      id: 'tick-done',
      item: doneItem.id,
      day: 1,
      ticked_at: stamp(21),
      updated_at: stamp(21),
      deleted_at: null,
      sync_id: null,
      value: null,
      source: 'USER_REPORTED',
    })

    await home(engine)
    expect(screen.getByRole('heading', { name: 'Lists' })).toBeInTheDocument()
    const links = screen.getAllByRole('link', { name: /Oldest|Middle|Freshest|Morning|All done/ })
    expect(links.map((link) => link.textContent)).toEqual([
      expect.stringContaining('Freshest'),
      expect.stringContaining('Middle'),
    ])
  })

  test('a pin naming a list that is gone is ignored, and the fallback applies', async () => {
    window.localStorage.setItem(PINS_KEY, JSON.stringify(['no-such-list']))
    await home(household())
    expect(screen.getByRole('heading', { name: 'Lists' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Groceries/ })).toBeInTheDocument()
  })

  test('lists with nothing open and nothing pinned say that, not "no lists"', async () => {
    const list = makeChecklist({ name: 'Empty' })
    await home(createEngine({ checklists: [list], spend: seedSpend() }))
    expect(within(section('Lists')).getByText(NOTHING_TO_SHOW_PINNED)).toBeInTheDocument()
  })

  test('storage that throws means no pins, and Home still renders', async () => {
    const real = Storage.prototype.getItem
    Storage.prototype.getItem = () => {
      throw new Error('storage is blocked')
    }
    try {
      await home(household())
      expect(screen.getByRole('heading', { name: 'Lists' })).toBeInTheDocument()
    } finally {
      Storage.prototype.getItem = real
    }
  })
})

describe('the spend card', () => {
  test('one row per account, "unverified" beside the card figure only, and when it was updated', async () => {
    await home(household())
    const card = section('Spent this month')
    const checking = (await within(card).findByText('BofA checking')).closest('span.flex') as HTMLElement
    const bofaCard = within(card).getByText('BofA card').closest('span.flex') as HTMLElement

    expect(within(checking).getByText('$1,284.10')).toBeInTheDocument()
    expect(within(checking).queryByText('unverified')).not.toBeInTheDocument()
    expect(within(bofaCard).getByText('$642.55')).toBeInTheDocument()
    expect(within(bofaCard).getByText('unverified')).toBeInTheDocument()
    expect(within(bofaCard).getByText(/^Updated /)).toBeInTheDocument()
    // The amount and the word sit in the one element, in the same font.
    const amount = within(bofaCard).getByText('$642.55')
    expect(amount.contains(within(bofaCard).getByText('unverified'))).toBe(true)
  })

  test('a tap opens the category lines: spend against target, "unverified" where true', async () => {
    await home(household())
    fireEvent.click(await within(section('Spent this month')).findByRole('button'))

    const sheet = await screen.findByRole('dialog', { name: 'Spent this month' })
    const lines = within(sheet).getByRole('list', { name: 'Spending by category' })

    const groceries = within(lines).getByRole('meter', { name: 'Groceries' })
    expect(groceries).toHaveAttribute('aria-valuetext', '$381.10 of $500.00')
    const groceriesRow = groceries.closest('li') as HTMLElement
    expect(within(groceriesRow).getByText('unverified')).toBeInTheDocument()

    const utilitiesRow = within(lines).getByRole('meter', { name: 'Utilities' }).closest('li') as HTMLElement
    expect(within(utilitiesRow).queryByText('unverified')).not.toBeInTheDocument()

    const funRow = within(lines).getByText('Fun').closest('li') as HTMLElement
    expect(within(funRow).getByText('No target set')).toBeInTheDocument()
    expect(within(funRow).getByText('unverified')).toBeInTheDocument()

    // What is left out, and how much of the card is unchecked, in words.
    expect(within(sheet).getByText(/\$41\.20 nobody has categorised yet/)).toBeInTheDocument()
    expect(within(sheet).getByText(/BofA card: \$210\.00 of \$642\.55 is unverified/)).toBeInTheDocument()

    fireEvent.click(within(sheet).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  test('could not reach the engine says so and shows no amount, never $0', async () => {
    const engine = household()
    engine.refusals['GET /api/ledger/spend'] = SPEND_DOWN
    await home(engine)
    const card = section('Spent this month')
    expect(await within(card).findByText(HOME_COPY.spend.unreachable)).toBeInTheDocument()
    expect(within(card).queryByText(/\$/)).not.toBeInTheDocument()
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()
  })

  test('a month with no card activity says so rather than printing zeros', async () => {
    await home(createEngine({ ...seedHousehold() }))
    expect(await screen.findByText(HOME_COPY.spend.empty)).toBeInTheDocument()
    expect(within(section('Spent this month')).queryByText(/\$/)).not.toBeInTheDocument()
  })

  test('there is no chart on the card', async () => {
    await home(household())
    await screen.findByText('$642.55')
    expect(section('Spent this month').querySelector('svg.recharts-surface')).toBeNull()
  })
})

describe('surfaces', () => {
  test('the workbench Home is not this one', async () => {
    stubSurface('workbench')
    const engine = household()
    renderApp('/', engine, 'workbench')
    await screen.findByRole('heading', { name: 'Today and the next 7 days' })
    expect(screen.queryByRole('heading', { name: 'Pinned lists' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Lists' })).toBeInTheDocument() // the workbench's own Lists panel
    expect(screen.queryByText(/^(Good morning|Good afternoon|Good evening|Hello)/)).not.toBeInTheDocument()
    // No pin button anywhere on the workbench Home.
    expect(screen.queryByRole('button', { name: /Pin .* to Home/ })).not.toBeInTheDocument()
  })
})
