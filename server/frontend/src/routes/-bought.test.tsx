import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'

import { CANT_READ_LOG } from '@/api/purchases'
import { todayEpochDay } from '@/lib/day'
import { dayLabel } from '@/lib/purchases'
import { createEngine, makeChecklist, makeItem, makePurchase, seedHousehold, type Engine } from '@/test/engine'
import { renderApp } from '@/test/render-app'
import type { Surface } from '@/lib/surface'

/**
 * The bought log (purchase-log 07): Home's search pill, the search screen, the
 * "Log it" form, the desk's table, and the Groceries "last bought" line. Each
 * test drives the real route against the fake engine and reads what a person
 * reads.
 */

const today = todayEpochDay()

function shelf(): Engine {
  return createEngine({
    ...seedHousehold(),
    purchases: [
      makePurchase({ item: 'Shampoo', bought_on: today - 14, logged_by: 'Mia', source: 'GROCERIES_TICK' }),
      makePurchase({
        item: 'Head & Shoulders shampoo',
        bought_on: today - 56,
        logged_by: 'Kevin',
        logged_by_me: false,
        store: 'HEB',
        price_cents: 899,
        price_note: 'entered by hand; never checked against the bank or added into ledger figures',
      }),
      makePurchase({ item: 'Dove shampoo', bought_on: today - 112, logged_by: null, logged_by_me: false, source: 'GROCERIES_BACKFILL' }),
      makePurchase({
        item: 'Contact lens solution',
        bought_on: today - 4,
        visibility: 'private',
        store: 'Target',
        price_cents: 949,
      }),
      makePurchase({ item: 'Eggs', bought_on: today - 7, logged_by: 'Kevin', logged_by_me: false, source: 'GROCERIES_TICK' }),
    ],
  })
}

async function open(path: string, engine: Engine, surface: Surface = 'family') {
  const view = renderApp(path, engine, surface)
  return view
}

function type(label: string | RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

describe('Home', () => {
  test('has the "When did we last buy...?" pill, and it opens the search', async () => {
    await open('/', shelf())
    const pill = await screen.findByRole('link', { name: 'When did we last buy...?' })
    fireEvent.click(pill)
    expect(await screen.findByRole('heading', { name: 'Bought' })).toBeInTheDocument()
    expect(screen.getByLabelText('When did we last buy')).toBeInTheDocument()
  })
})

describe('the search', () => {
  test('before anything is typed it lists what was bought recently, newest first', async () => {
    await open('/bought', shelf())
    const recent = await screen.findByRole('heading', { name: 'Recently bought' })
    const rows = within(recent.closest('section')!).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Contact lens solution')
    expect(rows[1]).toHaveTextContent('Eggs')
    expect(screen.getByRole('link', { name: /Log something by hand/ })).toBeInTheDocument()
  })

  test('answers with a sentence naming the exact entry, its date and who, then the others', async () => {
    await open('/bought', shelf())
    await screen.findByRole('heading', { name: 'Recently bought' })
    type('When did we last buy', 'shampoo')

    const answer = await screen.findByText(`Shampoo, bought ${dayLabel(today - 14, today)} by Mia`)
    const card = answer.closest('[role="status"]') as HTMLElement
    expect(within(card).getByText('Groceries tick')).toBeInTheDocument()
    expect(within(card).getByText(/2 more entries match, newest first/)).toBeInTheDocument()

    // The loose matches are named as logged, with where each came from.
    const others = screen.getByRole('list', { name: 'Other entries matching shampoo' })
    expect(within(others).getByText('Head & Shoulders shampoo')).toBeInTheDocument()
    expect(within(others).getByText('Dove shampoo')).toBeInTheDocument()
    expect(within(others).getByText('Old Groceries tick')).toBeInTheDocument()
    // A price says it was entered by hand, in words, in the same row.
    expect(within(others).getByText('$8.99')).toBeInTheDocument()
    expect(within(others).getByText('entered by hand')).toBeInTheDocument()
    // A backfilled entry says who is not recorded.
    expect(within(others).getByText('Logged by: not recorded')).toBeInTheDocument()
  })

  test('with no match says there is no record, never "never bought", and offers to log it', async () => {
    await open('/bought', shelf())
    await screen.findByRole('heading', { name: 'Recently bought' })
    type('When did we last buy', 'toothbrush')
    expect(await screen.findByText('No record of buying toothbrush.')).toBeInTheDocument()
    expect(screen.getByText(/It may still have been bought/)).toBeInTheDocument()
    expect(screen.queryByText(/never bought/i)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Log toothbrush' })).toBeInTheDocument()
  })

  test('says the log cannot be read, and does not call it empty or "no record"', async () => {
    const engine = shelf()
    engine.refusals['GET /api/purchases/'] = { status: 503, body: { detail: 'down' } }
    await open('/bought?q=shampoo', engine)
    expect(await screen.findByText(CANT_READ_LOG)).toBeInTheDocument()
    expect(screen.queryByText(/No record of buying/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Nothing has been logged/)).not.toBeInTheDocument()
  })

  test('an empty log says it is empty in words', async () => {
    await open('/bought', createEngine({ ...seedHousehold() }))
    expect(await screen.findByText(/Nothing has been logged as bought yet/)).toBeInTheDocument()
  })

  test("a private entry says only you can see it, on the lock's own line", async () => {
    await open('/bought?q=contact', shelf())
    const answer = await screen.findByText(/^Contact lens solution, bought/)
    expect(within(answer.closest('[role="status"]') as HTMLElement).getByText('Only you can see this')).toBeInTheDocument()
  })

  test("another member's entry cannot be edited or deleted, one's own can", async () => {
    await open('/bought', shelf())
    await screen.findByRole('heading', { name: 'Recently bought' })
    const eggs = screen.getByRole('button', { name: /Eggs/ })
    fireEvent.click(eggs)
    expect(screen.queryByRole('button', { name: 'Delete this entry' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit this entry' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Contact lens solution/ }))
    expect(screen.getByRole('button', { name: 'Delete this entry' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit this entry' })).toBeInTheDocument()
  })

  test('deleting an own entry asks first and takes it out of the log', async () => {
    const engine = shelf()
    await open('/bought', engine)
    await screen.findByRole('heading', { name: 'Recently bought' })
    fireEvent.click(screen.getByRole('button', { name: /Contact lens solution/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete this entry' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete entry' }))
    await waitFor(() => expect(screen.queryByText('Contact lens solution')).not.toBeInTheDocument())
    expect(engine.writes.some((write) => write.method === 'DELETE' && write.pathname.startsWith('/api/purchases/'))).toBe(true)
  })

  test('editing an own entry saves through PATCH only on a yes', async () => {
    const engine = shelf()
    await open('/bought', engine)
    await screen.findByRole('heading', { name: 'Recently bought' })
    fireEvent.click(screen.getByRole('button', { name: /Contact lens solution/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Edit this entry' }))
    expect(screen.getByLabelText('Price (optional)')).toHaveValue('9.49')
    type(/^Price/, '10.25')
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(engine.writes.find((write) => write.method === 'PATCH')).toBeDefined())
    expect(engine.purchases.find((entry) => entry.item === 'Contact lens solution')?.price_cents).toBe(1025)
  })
})

describe('Log it', () => {
  test('saves what was typed, with the price in whole cents, and goes back to the answer', async () => {
    const engine = shelf()
    await open('/bought/log?item=Toothbrush', engine)
    expect(await screen.findByLabelText('What was bought')).toHaveValue('Toothbrush')
    expect(screen.getByLabelText('Bought on')).toHaveValue(
      new Date().toLocaleDateString('en-CA'),
    )
    type('What was bought', 'Toothbrush')
    type(/^Store/, 'HEB')
    type(/^Price/, '3.49')
    type(/^Quantity/, '2-pack')
    // A price says it is entered by hand before it is even saved.
    expect(screen.getByText(/A price is entered by hand/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Log it' }))

    expect(await screen.findByText(/^Toothbrush, bought/)).toBeInTheDocument()
    const posted = engine.writes.find((write) => write.method === 'POST' && write.pathname === '/api/purchases/')
    expect(posted?.body).toMatchObject({
      item: 'Toothbrush',
      bought_on: today,
      store: 'HEB',
      price_cents: 349,
      quantity_note: '2-pack',
      visibility: 'shared',
    })
  })

  test('private is a switch that says what it means, and sends visibility private', async () => {
    const engine = shelf()
    await open('/bought/log', engine)
    await screen.findByLabelText('What was bought')
    expect(screen.getByText('Shared with the household')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('switch', { name: 'Only me' }))
    expect(screen.getByText(/Only you can see this\./)).toBeInTheDocument()
    type('What was bought', 'Razor blades')
    fireEvent.click(screen.getByRole('button', { name: 'Log it' }))
    await screen.findByText(/^Razor blades, bought/)
    expect(engine.writes.find((write) => write.method === 'POST')?.body).toMatchObject({
      item: 'Razor blades',
      visibility: 'private',
    })
  })

  test('with the engine unreachable says nothing was logged and keeps every typed field', async () => {
    const engine = shelf()
    await open('/bought/log', engine)
    await screen.findByLabelText('What was bought')
    type('What was bought', 'Shampoo')
    type(/^Store/, 'Target')
    type(/^Price/, '8.99')
    engine.down = true
    fireEvent.click(screen.getByRole('button', { name: 'Log it' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("Can't reach the bought log right now. Nothing was logged.")
    expect(screen.getByLabelText('What was bought')).toHaveValue('Shampoo')
    expect(screen.getByLabelText(/^Store/)).toHaveValue('Target')
    expect(screen.getByLabelText(/^Price/)).toHaveValue('8.99')
    expect(engine.purchases).toHaveLength(5)
  })

  test("a refusal shows the engine's own sentence under what did not happen", async () => {
    const engine = shelf()
    engine.refusals['POST /api/purchases/'] = { status: 400, body: { detail: 'That date is in the future.' } }
    await open('/bought/log', engine)
    await screen.findByLabelText('What was bought')
    type('What was bought', 'Shampoo')
    fireEvent.click(screen.getByRole('button', { name: 'Log it' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing was logged. That date is in the future.')
    expect(screen.getByLabelText('What was bought')).toHaveValue('Shampoo')
  })

  test('refuses an empty item and a price that is not money before asking the engine', async () => {
    const engine = shelf()
    await open('/bought/log', engine)
    await screen.findByLabelText('What was bought')
    fireEvent.click(screen.getByRole('button', { name: 'Log it' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Say what was bought. Nothing was logged.')
    type('What was bought', 'Shampoo')
    type(/^Price/, 'eight')
    fireEvent.click(screen.getByRole('button', { name: 'Log it' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Price should look like 8.99')
    expect(engine.writes).toHaveLength(0)
  })
})

describe('Groceries lines', () => {
  async function lists(engine: Engine) {
    await open('/lists', engine)
    return (await screen.findByRole('heading', { name: 'Groceries' })).closest('div.rounded-sheet') as HTMLElement
  }

  test('say when and by whom the exact item was last bought, and say nothing for one with no record', async () => {
    const list = makeChecklist({ name: 'Groceries' })
    const engine = createEngine({
      checklists: [list],
      items: [makeItem(list, 'Eggs'), makeItem(list, 'Oat milk')],
      purchases: [makePurchase({ item: 'Eggs', bought_on: today - 7, logged_by: 'Kevin', source: 'GROCERIES_TICK' })],
    })
    const card = await lists(engine)
    expect(await within(card).findByText(`last bought ${dayLabel(today - 7, today)} · Kevin`)).toBeInTheDocument()
    // No record for Oat milk: nothing is said, in particular not "never bought".
    expect(within(card).queryByText(/Oat milk.*bought/)).not.toBeInTheDocument()
    expect(within(card).getAllByText(/last bought/)).toHaveLength(1)
  })

  test('another list keeps no bought line at all', async () => {
    const groceries = makeChecklist({ name: 'Groceries', sort_order: 0 })
    const hardware = makeChecklist({ name: 'Hardware store', sort_order: 1 })
    const engine = createEngine({
      checklists: [groceries, hardware],
      items: [makeItem(groceries, 'Eggs'), makeItem(hardware, 'Eggs')],
      purchases: [makePurchase({ item: 'Eggs', bought_on: today - 7, logged_by: 'Kevin' })],
    })
    await lists(engine)
    const other = screen.getByRole('heading', { name: 'Hardware store' }).closest('div.rounded-sheet') as HTMLElement
    expect(await screen.findAllByText(/last bought/)).toHaveLength(1)
    expect(within(other).queryByText(/bought/)).not.toBeInTheDocument()
  })

  test('says it cannot check when the log cannot be read, never that nothing was bought', async () => {
    const list = makeChecklist({ name: 'Groceries' })
    const engine = createEngine({ checklists: [list], items: [makeItem(list, 'Eggs')] })
    engine.refusals['GET /api/purchases/'] = { status: 503, body: { detail: 'down' } }
    const card = await lists(engine)
    expect(await within(card).findByText("last bought: can't check right now")).toBeInTheDocument()
  })

  test('a tick is a purchase, and an untick the same day sends the local day and removes it', async () => {
    const list = makeChecklist({ name: 'Groceries' })
    const engine = createEngine({ checklists: [list], items: [makeItem(list, 'Shampoo')] })
    const card = await lists(engine)

    fireEvent.click(within(card).getByRole('checkbox', { name: 'Mark "Shampoo" done' }))
    await waitFor(() => expect(engine.purchases.filter((entry) => entry.deleted_at === null)).toHaveLength(1))
    expect(engine.purchases[0]).toMatchObject({ item: 'Shampoo', source: 'GROCERIES_TICK', bought_on: today })
    fireEvent.click(await within(card).findByRole('button', { name: /Ticked 1/ }))
    expect(await within(card).findByText('last bought today · Mia')).toBeInTheDocument()

    fireEvent.click(within(card).getByRole('checkbox', { name: 'Mark "Shampoo" not done' }))
    await waitFor(() => expect(engine.purchases.filter((entry) => entry.deleted_at === null)).toHaveLength(0))
    expect(new URLSearchParams(engine.searches[`DELETE /api/checklists/${list.id}/items/${engine.items[0].id}/tick/${today}`]).get('today')).toBe(String(today))
  })
})

describe('the desk', () => {
  test('has a Bought rail item, a table with a source chip and a price in words, and the form beside it', async () => {
    await open('/bought', shelf(), 'workbench')
    expect(await screen.findByRole('heading', { name: 'Bought', level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Bought' })).toBeInTheDocument()
    const table = await screen.findByRole('table')
    expect(within(table).getByText('Head & Shoulders shampoo')).toBeInTheDocument()
    expect(within(table).getByText('Old Groceries tick')).toBeInTheDocument()
    expect(within(table).getByText('not recorded')).toBeInTheDocument()
    expect(within(table).getAllByText('entered by hand').length).toBeGreaterThan(0)
    expect(within(table).getByText('Only you can see this')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Log something bought' })).toBeInTheDocument()
  })

  test('filters by source on the engine, and searches with the answer above the table', async () => {
    await open('/bought', shelf(), 'workbench')
    await screen.findByRole('table')
    fireEvent.click(screen.getByRole('button', { name: 'Old Groceries ticks' }))
    expect(await screen.findByText('Dove shampoo')).toBeInTheDocument()
    expect(screen.queryByText('Eggs')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'All' }))
    await screen.findByText('Eggs')
    type('Search what was bought', 'shampoo')
    expect(await screen.findByText(`Shampoo, bought ${dayLabel(today - 14, today)} by Mia`)).toBeInTheDocument()
  })

  test('logs from the panel and the new entry appears in the table', async () => {
    const engine = shelf()
    await open('/bought', engine, 'workbench')
    await screen.findByRole('table')
    type('What was bought', 'Light bulbs')
    fireEvent.click(screen.getByRole('button', { name: 'Log it' }))
    expect(await screen.findByText(/^Logged: Light bulbs/)).toBeInTheDocument()
    expect(await within(screen.getByRole('table')).findByText('Light bulbs')).toBeInTheDocument()
    expect(screen.getByLabelText('What was bought')).toHaveValue('')
  })

  test('says in words that the log cannot be read, not that it is empty', async () => {
    const engine = shelf()
    engine.refusals['GET /api/purchases/'] = { status: 503, body: { detail: 'down' } }
    await open('/bought', engine, 'workbench')
    expect(await screen.findByText(CANT_READ_LOG)).toBeInTheDocument()
    expect(screen.queryByText(/Nothing has been logged/)).not.toBeInTheDocument()
  })

  test('is not a phone tab', async () => {
    await open('/', shelf(), 'family')
    await screen.findByRole('link', { name: 'When did we last buy...?' })
    const tabs = screen.getByRole('navigation', { name: 'Tabs' })
    expect(within(tabs).queryByRole('link', { name: 'Bought' })).not.toBeInTheDocument()
  })
})
