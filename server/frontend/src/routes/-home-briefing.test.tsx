import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import { TRUST_LEGEND } from '@/screens/home-briefing'
import { briefingEngine, briefingVisit } from '@/test/briefing-seed'
import { createEngine } from '@/test/engine'
import { renderApp } from '@/test/render-app'

/**
 * The Morning briefing on the desk (recruiter-demo ticket 14). Each section in
 * its four states: data, empty, could not be read, and a recruiter's visit.
 */

// A fixed morning, so "next" and "later today" do not depend on when the suite
// runs: Monday 2 November 2026, 8:18 local. Only Date is faked; timers run.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 10, 2, 8, 18))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const section = (name: string) => screen.getByRole('region', { name: new RegExp(name) })

/** The page has read the calendar: every section is on screen. */
async function ready() {
  await screen.findByRole('heading', { level: 1, name: /^Good morning/ })
  await screen.findByRole('region', { name: /Next up/ })
}

describe('a member with a full household', () => {
  test('greets by name and says the day in counts and times only', async () => {
    renderApp('/', briefingEngine(), 'workbench')
    expect(await screen.findByRole('heading', { level: 1, name: /, Kevin\.$/ })).toBeInTheDocument()
    const sentence = await screen.findByText(/^It is /)
    await waitFor(() => expect(sentence.textContent).toMatch(/want a look\.$/))
    // Three plans today, and the first one's time; no titles, no adjectives.
    expect(sentence.textContent).toMatch(/Three things on the calendar today, the first at /)
    expect(sentence.textContent).not.toMatch(/Dentist|urgent|busy|free/)
    expect(screen.getByText(TRUST_LEGEND)).toBeInTheDocument()
  })

  test('on the desk the dock is the one place to type, open on load, with the first questions', async () => {
    renderApp('/', briefingEngine(), 'workbench')
    // Kevin, 2026-11-03: "i still see 2 chat windows that i can type in". No ask bar in the page.
    const dock = await screen.findByRole('complementary', { name: 'Conversation with Dorothy' })
    await ready()
    expect(screen.queryByRole('search')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /^Ask / })).not.toBeInTheDocument()
    expect(screen.getAllByRole('textbox')).toEqual([within(dock).getByRole('textbox', { name: 'Message Dorothy' })])
    expect(screen.getAllByRole('log')).toHaveLength(1)
    expect(screen.queryByText(/Alfred/)).not.toBeInTheDocument()
    // The ask bar's questions live in the dock's empty state now.
    expect(within(dock).getByRole('button', { name: 'What did we spend on groceries?' })).toBeInTheDocument()
    expect(within(dock).getByRole('button', { name: 'When is the next car service due?' })).toBeInTheDocument()
  })

  test('on a phone Home has no conversation in the page; the orb opens the one sheet', async () => {
    renderApp('/', briefingEngine(), 'family')
    const orb = await screen.findByRole('button', { name: 'Ask Dorothy' })
    // One entry point and nothing to type into until it opens.
    expect(screen.queryByRole('log')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.click(orb)
    expect(await screen.findByRole('dialog', { name: 'Conversation with Dorothy' })).toBeInTheDocument()
    expect(screen.getAllByRole('log')).toHaveLength(1)
  })

  test('1 Next up: the next thing big, the rest of today and tomorrow beside it', async () => {
    renderApp('/', briefingEngine(), 'workbench')
    await ready()
    const next = section('Next up')
    expect(within(next).getByRole('article', { name: 'Next: Dentist, Mia' })).toHaveTextContent(/in 42 minutes/)
    const later = within(next).getByRole('list', { name: 'Later today and tomorrow' })
    expect(within(later).getByText('Team review call')).toBeInTheDocument()
    expect(within(later).getByText('Bins out')).toBeInTheDocument()
  })

  test('2 Worth a look: the car, the calendar, uncategorised charges and the bank link, each with its source', async () => {
    const engine = briefingEngine()
    renderApp('/', engine, 'workbench')
    await ready()
    const worth = section('Worth a look')
    await within(worth).findByText(/Oil change for the XJ in about 320 miles/)
    const oil = within(worth).getByText(/Oil change for the XJ/).closest('li') as HTMLElement
    expect(within(oil).getByText('Estimate')).toBeInTheDocument()
    expect(within(oil).getByText('Due soon')).toBeInTheDocument()
    expect(oil).toHaveTextContent('212,480 mi on the clock, from the odometer reading')

    expect(within(worth).getByText('Renew the car registration').closest('li')).toHaveTextContent(/Overdue.*From the calendar/)
    expect(within(worth).getByText('Pick up dry cleaning').closest('li')).toHaveTextContent('Due today')

    const charges = (await within(worth).findByText('3 charges, $64.20, need a category')).closest('li') as HTMLElement
    expect(within(charges).getByText('Unverified')).toBeInTheDocument()
    expect(within(charges).getByRole('link', { name: 'Review' })).toHaveAttribute('href', '/money?need=true')

    const bank = (await within(worth).findByText(/bank link to Bank of America needs signing in again/)).closest('li') as HTMLElement
    expect(within(bank).getByText('Paused')).toBeInTheDocument()
    expect(within(worth).getByText(/no source of recurring bills/)).toBeInTheDocument()
  })

  test('3 Money: the engine figure against the budget, a chart with a table, balances with trust words', async () => {
    renderApp('/', briefingEngine(), 'workbench')
    await ready()
    const money = section('Money this month')
    expect((await within(money).findAllByText('$1,366.72'))[0]).toHaveClass('tabular-nums')
    expect(within(money).getByText('of $2,400.00 budget')).toBeInTheDocument()
    expect(within(money).getByText(/That is\s+a subtraction, not a forecast/)).toBeInTheDocument()

    await within(money).findByRole('img', { name: /Running total of categorised spending/ })
    fireEvent.click(within(money).getByRole('button', { name: 'Show as a table' }))
    expect(within(money).getByRole('columnheader', { name: 'Spent so far' })).toBeInTheDocument()

    const balances = within(money).getByRole('list', { name: 'Balances' })
    const checking = within(balances).getByText('Checking').closest('li') as HTMLElement
    expect(checking).toHaveTextContent('$4,812.40')
    expect(within(checking).getByText('Verified')).toBeInTheDocument()
    expect(within(balances).getByText('BofA card').closest('li')).toHaveTextContent(/From the bank link.*-\$1,327\.18.*Verified/)
  })

  test("3 Money: the bank link's own balance wins, with its as-of time (Kevin: store them)", async () => {
    const engine = briefingEngine()
    engine.settings.bank = {
      ...engine.settings.bank,
      accounts: [
        {
          name: 'Adv Plus Banking',
          mask: '7823',
          type: 'credit',
          subtype: 'credit card',
          balance_current_cents: 141007,
          balance_available_cents: 358993,
          balance_currency: 'USD',
          balance_as_of: new Date(2026, 10, 2, 7, 5).toISOString(),
        },
      ],
    }
    renderApp('/', engine, 'workbench')
    await ready()
    const money = section('Money this month')
    const balances = await within(money).findByRole('list', { name: 'Balances' })
    await waitFor(() =>
      expect(within(balances).getByText('BofA card').closest('li')).toHaveTextContent(
        /Owed, from the bank as of 7:05 AM, Nov 2.*-\$1,410\.07.*Verified/,
      ),
    )
    expect(Object.keys(engine.calls).some((key) => key.includes('/api/ingest/plaid'))).toBe(true)
  })

  test('4 Lists: ticking an item on Groceries is the real tick write', async () => {
    const engine = briefingEngine()
    renderApp('/', engine, 'workbench')
    await ready()
    const lists = section('Lists')
    expect(within(lists).getByText('2 of 5 ticked')).toBeInTheDocument()
    fireEvent.click(within(lists).getByRole('checkbox', { name: 'Mark "Greek yoghurt" done' }))
    await waitFor(() =>
      expect(engine.writes.some((write) => write.method === 'POST' && write.pathname.endsWith('/tick'))).toBe(true),
    )
    expect(await within(lists).findByText('3 of 5 ticked')).toBeInTheDocument()
    expect(within(lists).getByText('Hardware store')).toBeInTheDocument()
  })

  test('5 Around the house: groceries with an estimate, notes, places, books and body', async () => {
    renderApp('/', briefingEngine(), 'workbench')
    await ready()
    const house = section('Around the house')
    const groceries = await within(house).findByRole('listitem', { name: 'Groceries' })
    await waitFor(() => expect(groceries).toHaveTextContent(/Groceries this week: \$84\.23 from 1 receipt/))
    expect(within(groceries).getByText('Estimate')).toBeInTheDocument()
    expect(within(groceries).getByText('Verified')).toBeInTheDocument()
    await waitFor(() => expect(within(house).getByRole('listitem', { name: 'Body' })).toHaveTextContent(/Slept/))
    expect(within(house).getByRole('listitem', { name: 'Places' })).toBeInTheDocument()
    expect(within(house).getByRole('listitem', { name: 'Books' })).toBeInTheDocument()
    expect(screen.getByText(/^That is everything for this morning\./)).toBeInTheDocument()
  })
})

describe('an empty household', () => {
  test('each section says it is empty, in its own words', async () => {
    renderApp('/', createEngine({ householdName: 'New House' }), 'workbench')
    await ready()
    expect(await screen.findByText(/^It is Monday 2 November\. Nothing on the calendar today\./)).toBeInTheDocument()
    expect(within(section('Next up')).getByText('Nothing on the calendar today.')).toBeInTheDocument()
    expect(within(section('Lists')).getByText('No lists yet. Start one on Lists.')).toBeInTheDocument()
    const money = section('Money this month')
    expect(await within(money).findByText(/No card activity has reached the engine/)).toBeInTheDocument()
    expect(await within(money).findByText('No statement, bank row or bank link on file states a balance yet.')).toBeInTheDocument()
    expect(await within(section('Worth a look')).findByText(/^Nothing wants a look/)).toBeInTheDocument()
    expect(await within(section('Around the house')).findByText('No places saved yet.')).toBeInTheDocument()
    expect(screen.getByText(/Nothing else is due in the next two weeks\./)).toBeInTheDocument()
  })
})

describe('reads that fail', () => {
  test('say not known, never empty and never $0', async () => {
    const engine = briefingEngine()
    engine.changesFailing = true
    engine.refusals['GET /api/ledger/spend'] = { status: 503 }
    engine.failingTables.add('fleet/vehicles')
    engine.failingTables.add('ledger/statements')
    renderApp('/', engine, 'workbench')
    await ready()
    expect(await screen.findByText(/what is on the calendar today is not known/)).toBeInTheDocument()
    expect(within(section('Next up')).getByText(/what is on today is not known/)).toBeInTheDocument()
    expect(within(section('Next up')).queryByText('Nothing on the calendar today.')).not.toBeInTheDocument()
    expect(within(section('Lists')).getByText('Could not reach the engine, so your lists are not shown.')).toBeInTheDocument()
    const worth = section('Worth a look')
    expect(await within(worth).findByText(/Could not read the car's records/)).toBeInTheDocument()
    expect(within(worth).getByText(/what is overdue or due today is not known/)).toBeInTheDocument()
    expect(within(worth).queryByText(/^Nothing wants a look/)).not.toBeInTheDocument()
    const money = section('Money this month')
    expect(await within(money).findByText(/what was spent this month is not known\. This is not zero\./)).toBeInTheDocument()
    expect(await within(money).findByText('Could not reach the engine, so the balances are not known.')).toBeInTheDocument()
  })
})

describe("a recruiter's demo visit", () => {
  test('banner, no name in the greeting, no body, no books, no bank link, and never asks for them', async () => {
    const engine = briefingVisit()
    renderApp('/', engine, 'workbench')
    expect(await screen.findByRole('note', { name: 'Demo visit' })).toBeInTheDocument()
    const heading = await screen.findByRole('heading', { level: 1 })
    expect(heading.textContent).toBe('Good morning.')
    expect(heading).not.toHaveTextContent(/Kevin|Mia/)

    const worth = section('Worth a look')
    await within(worth).findByText(/Oil change for the XJ/)
    expect(within(worth).queryByText(/bank link/)).not.toBeInTheDocument()
    const house = section('Around the house')
    await within(house).findByRole('listitem', { name: 'Places' })
    expect(within(house).queryByRole('listitem', { name: 'Body' })).not.toBeInTheDocument()
    expect(within(house).queryByRole('listitem', { name: 'Books' })).not.toBeInTheDocument()

    const asked = Object.keys(engine.calls)
    expect(asked.some((key) => key.includes('/api/ingest/plaid'))).toBe(false)
    expect(asked.some((key) => key.includes('/api/body/'))).toBe(false)
    expect(asked.some((key) => key.includes('/api/books/'))).toBe(false)
  })
})
