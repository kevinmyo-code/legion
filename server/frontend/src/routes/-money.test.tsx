import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { createEngine, type Engine } from '@/test/engine'
import { CARD, CHECKING, makeTxn, seedLedger, seedSpend } from '@/test/ledger-seed'
import { pressTab, renderApp } from '@/test/render-app'

/**
 * `/money`, driven the way a person drives it: render the route, answer HTTP from
 * the fake engine, press things, read the screen. The trust rules are the point:
 * a partial fetch is never totalled, `unverified` is a word in the Status column
 * and beside any figure that has one added in, a category is optimistic and put
 * back with a sentence, and nothing on the page lets a statement be handed in.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

function engineWith(options: { pageSize?: number } = {}): Engine {
  return createEngine({ tables: seedLedger(), spend: seedSpend(), ...options })
}

async function rowOf(description: string): Promise<HTMLElement> {
  const button = await screen.findByRole('button', { name: `Details of ${description}` })
  return button.closest('tr') as HTMLElement
}

async function pick(description: string, category: string) {
  fireEvent.click(await screen.findByRole('button', { name: new RegExp(`^Category for ${description}:`) }))
  const list = await screen.findByRole('listbox', { name: 'Categories' })
  fireEvent.click(within(list).getByRole('option', { name: category }))
}

function showing(): string {
  return screen.getByText(/^Showing /).textContent ?? ''
}

describe('at family width', () => {
  test('is the bigger-screen card, with no table in the page and no ledger read', async () => {
    const engine = engineWith()
    renderApp('/money', engine, 'family')

    expect(await screen.findByText('This page is made for a bigger screen.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Transactions' })).not.toBeInTheDocument()
    expect(Object.keys(engine.calls).filter((key) => key.includes('ledger'))).toEqual([])
  })
})

describe('transactions', () => {
  test('lists the ledger with a rail item, the source of each category, and no way to add a statement', async () => {
    const engine = engineWith()
    renderApp('/money', engine)

    const shell = await rowOf('Shell 5521')
    expect(within(shell).getByText('None')).toBeInTheDocument()
    expect(within(shell).getByRole('button', { name: /^Category for Shell 5521: none/ })).toHaveTextContent(
      'Choose category',
    )
    // Two rows share this merchant; the list is newest first, so the first is the card's.
    const trader = (await screen.findAllByRole('button', { name: "Details of Trader Joe's #552" }))[0].closest('tr') as HTMLElement
    expect(within(trader).getByText('Bank file')).toBeInTheDocument()
    expect(within(trader).getByRole('button', { name: /: Groceries$/ })).toBeInTheDocument()
    expect(within(trader).getByText('BofA card')).toBeInTheDocument()

    expect(
      within(screen.getByRole('navigation', { name: 'Sections' })).getByRole('link', { name: 'Money' }),
    ).toHaveAttribute('aria-current', 'page')
    // Statements are gone: nothing on the page offers to hand one in or key one in.
    expect(screen.queryByRole('button', { name: /statement/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /add a transaction/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/upload/i)).not.toBeInTheDocument()
    expect(engine.unhandled).toEqual([])
  })

  test('a partial fetch says "Still loading N" and prints no count, no net and no month', async () => {
    const engine = engineWith({ pageSize: 5 })
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    // Page one is answered; every later page waits.
    engine.delay = (_method, pathname, search) =>
      pathname === '/api/ledger/transactions/' && search.has('since') ? held : undefined
    renderApp('/money', engine)

    expect(await screen.findByText(/Still loading transactions: 5 so far/)).toBeInTheDocument()
    expect(screen.queryByText(/^Showing /)).not.toBeInTheDocument()
    expect(screen.queryByText(/Net of these rows/)).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Transactions' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Needs a category/ })).not.toBeInTheDocument()

    release()
    expect(await screen.findByText('Showing 15 of 15 transactions.')).toBeInTheDocument()
    expect(screen.queryByText(/Still loading/)).not.toBeInTheDocument()
  })

  test('a page that is exactly full and followed by an empty one still completes', async () => {
    const engine = engineWith({ pageSize: 15 })
    renderApp('/money', engine)
    expect(await screen.findByText('Showing 15 of 15 transactions.')).toBeInTheDocument()
  })

  test('only a window of a long ledger is in the page', async () => {
    const engine = engineWith()
    engine.tables['ledger/transactions'] = Array.from({ length: 2000 }, (_, index) =>
      makeTxn({
        txn_date: '2026-09-15',
        description: `Row ${index}`,
        amount_cents: -100,
        created_at: `2026-09-15T12:${String(Math.floor(index / 60) % 60).padStart(2, '0')}:${String(index % 60).padStart(2, '0')}Z`,
        stored: 'Groceries',
        account_last4: CHECKING,
      }),
    )
    renderApp('/money', engine)

    expect(await screen.findByText('Showing 2,000 of 2,000 transactions.')).toBeInTheDocument()
    const rendered = screen.getAllByRole('button', { name: /^Details of Row / })
    expect(rendered.length).toBeGreaterThan(5)
    expect(rendered.length).toBeLessThan(60)
  })

  test('the Status column says "unverified" in words on a card row and is blank on a verified one', async () => {
    renderApp('/money', engineWith())

    const shell = await rowOf('Shell 5521')
    expect(within(shell).getByText('unverified')).toBeInTheDocument()
    const water = await rowOf('City Water & Power')
    expect(within(water).queryByText('unverified')).not.toBeInTheDocument()
    expect(within(water).queryByText(/verified/i)).not.toBeInTheDocument()
  })

  test('setting a category is optimistic, then settles through the engine and re-reads spend', async () => {
    const engine = engineWith()
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    renderApp('/money', engine)
    await rowOf('Shell 5521')
    const spendBefore = engine.calls['GET /api/ledger/spend'] ?? 0

    engine.delay = (method) => (method === 'PUT' ? held : undefined)
    await pick('Shell 5521', 'Fuel')

    // The engine has not answered, and the row already says Fuel and "You".
    const shell = await rowOf('Shell 5521')
    expect(await within(shell).findByRole('button', { name: /: Fuel$/ })).toBeInTheDocument()
    expect(within(shell).getByText('You')).toBeInTheDocument()
    expect(engine.tables['ledger/transaction_categories']).toHaveLength(0)

    release()
    await waitFor(() => expect(engine.tables['ledger/transaction_categories']).toHaveLength(1))
    const [stored] = engine.tables['ledger/transaction_categories']
    expect(stored).toMatchObject({ category: 'Fuel', source: 'person' })
    await waitFor(() => expect(engine.calls['GET /api/ledger/spend']).toBeGreaterThan(spendBefore))
    expect(within(await rowOf('Shell 5521')).getByText('You')).toBeInTheDocument()
  })

  test('a refused category is put back, and a sentence says nothing was saved', async () => {
    const engine = engineWith()
    engine.refusals['PUT /api/ledger/transaction_categories/*'] = {
      status: 400,
      body: { category: ['That category is not on the list.'] },
    }
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    renderApp('/money', engine)
    await rowOf('Shell 5521')
    engine.delay = (method) => (method === 'PUT' ? held : undefined)

    await pick('Shell 5521', 'Fuel')
    expect(await within(await rowOf('Shell 5521')).findByRole('button', { name: /: Fuel$/ })).toBeInTheDocument()

    release()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Nothing was saved. category: That category is not on the list.')
    expect(alert).toHaveTextContent('"Shell 5521" is back to no category.')
    const shell = await rowOf('Shell 5521')
    expect(within(shell).getByRole('button', { name: /: none$/ })).toHaveTextContent('Choose category')
    expect(within(shell).getByText('None')).toBeInTheDocument()
    expect(engine.tables['ledger/transaction_categories']).toHaveLength(0)
  })

  test('when the engine cannot be reached the category is put back too', async () => {
    const engine = engineWith()
    renderApp('/money', engine)
    await rowOf('Shell 5521')
    engine.down = true

    await pick('Shell 5521', 'Fuel')
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not reach the engine. Nothing was saved. "Shell 5521" is back to no category.',
    )
    expect(within(await rowOf('Shell 5521')).getByText('None')).toBeInTheDocument()
  })

  test("taking back your own choice deletes the override and the bank file's category shows again", async () => {
    const engine = engineWith()
    // Corner Market's bank file says Groceries; here a person chose Fun over it.
    const corner = engine.tables['ledger/transactions'].find((row) => row.description === 'Corner Market')!
    engine.tables['ledger/transaction_categories'] = [
      {
        id: 'c1',
        transaction_id: corner.id,
        category: 'Fun',
        source: 'person',
        created_at: '2026-10-02T00:00:00Z',
        updated_at: '2026-10-02T00:00:00Z',
        deleted_at: null,
        origin_guid: null,
      },
    ]
    renderApp('/money', engine)

    const before = await rowOf('Corner Market')
    expect(within(before).getByText('You')).toBeInTheDocument()
    fireEvent.click(within(before).getByRole('button', { name: /^Category for Corner Market: Fun/ }))
    fireEvent.click(await screen.findByRole('option', { name: 'Undo my choice' }))

    await waitFor(() => expect(engine.tables['ledger/transaction_categories'][0].deleted_at).not.toBeNull())
    await waitFor(() =>
      expect(within(screen.getByRole('button', { name: 'Details of Corner Market' }).closest('tr')!).getByText('Bank file')).toBeInTheDocument(),
    )
  })

  test('a category from the bank file offers no "undo": only choosing another replaces it', async () => {
    renderApp('/money', engineWith())
    const row = (await screen.findAllByRole('button', { name: "Details of Trader Joe's #552" }))[0].closest('tr') as HTMLElement
    fireEvent.click(within(row).getByRole('button', { name: /^Category for Trader/ }))
    await screen.findByRole('listbox', { name: 'Categories' })
    expect(screen.queryByRole('option', { name: 'Undo my choice' })).not.toBeInTheDocument()
  })

  test('the combobox narrows by typing and picks with the keyboard', async () => {
    const engine = engineWith()
    renderApp('/money', engine)
    await rowOf('Shell 5521')
    fireEvent.click(screen.getByRole('button', { name: /^Category for Shell 5521:/ }))
    const field = await screen.findByRole('combobox', { name: 'Find a category' })
    fireEvent.change(field, { target: { value: 'uti' } })
    expect(within(screen.getByRole('listbox', { name: 'Categories' })).getAllByRole('option')).toHaveLength(1)
    fireEvent.keyDown(field, { key: 'Enter' })
    await waitFor(() =>
      expect(engine.tables['ledger/transaction_categories'][0]).toMatchObject({ category: 'Utilities' }),
    )
  })

  test('the filter chips narrow the list: account, month, category, needs a category, unverified', async () => {
    renderApp('/money', engineWith())
    await rowOf('Shell 5521')
    expect(showing()).toBe('Showing 15 of 15 transactions.')

    expect(screen.getByRole('button', { name: /Needs a category/ })).toHaveTextContent('7')
    expect(screen.getByRole('button', { name: /Unverified/ })).toHaveTextContent('8')

    fireEvent.change(screen.getByRole('combobox', { name: 'Account' }), { target: { value: CARD } })
    expect(showing()).toBe('Showing 8 of 15 transactions.')
    fireEvent.change(screen.getByRole('combobox', { name: 'Account' }), { target: { value: CHECKING } })
    expect(showing()).toBe('Showing 7 of 15 transactions.')
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    fireEvent.change(screen.getByRole('combobox', { name: 'Month' }), { target: { value: '2026-09' } })
    expect(showing()).toBe('Showing 7 of 15 transactions.')
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    fireEvent.change(screen.getByRole('combobox', { name: 'Category' }), { target: { value: 'Groceries' } })
    expect(showing()).toBe('Showing 3 of 15 transactions.')
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))

    fireEvent.click(screen.getByRole('button', { name: /Needs a category/ }))
    expect(showing()).toBe('Showing 7 of 15 transactions.')
    expect(screen.getByRole('button', { name: /Needs a category/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('button', { name: 'Details of City Water & Power' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Unverified/ }))
    // Card rows with no category: Shell, Spotify, Costco, Blue Door, Amazon, Pump (not the checking transfer).
    expect(showing()).toBe('Showing 6 of 15 transactions.')
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(showing()).toBe('Showing 15 of 15 transactions.')
  })

  test('a filter that matches nothing says so in words', async () => {
    renderApp('/money', engineWith())
    await rowOf('Shell 5521')
    fireEvent.change(screen.getByRole('combobox', { name: 'Account' }), { target: { value: CHECKING } })
    fireEvent.click(screen.getByRole('button', { name: /Unverified/ }))
    expect(screen.getByText('No transactions match these filters.')).toBeInTheDocument()
  })

  test('the net of the rows shown says "unverified" when an unverified row is in it, and not when none is', async () => {
    renderApp('/money', engineWith())
    await rowOf('Shell 5521')

    const net = () => screen.getByText('Net of these rows').parentElement as HTMLElement
    expect(within(net()).getByText('unverified')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('combobox', { name: 'Account' }), { target: { value: CHECKING } })
    // 2,310.55 - 86.40 - 112.37 - 45.00 - 94.12 - 1,850.00 - 51.20 = 71.46
    expect(net()).toHaveTextContent('$71.46')
    expect(within(net()).queryByText('unverified')).not.toBeInTheDocument()
  })

  test('the detail panel shows verification_note verbatim and says how the category got there', async () => {
    renderApp('/money', engineWith())
    fireEvent.click(await screen.findByRole('button', { name: 'Details of Shell 5521' }))

    const panel = await screen.findByRole('complementary', { name: 'Selected transaction' })
    expect(within(panel).getByText('-$48.10')).toBeInTheDocument()
    expect(
      within(panel).getByText(
        'Unverified: from a bank activity export that states no balance or total to check it against. It is replaced when the statement for this date passes the gate.',
      ),
    ).toBeInTheDocument()
    expect(within(panel).getByText('unverified')).toBeInTheDocument()
    // A rule exists for SHELL; it has not been applied to this row.
    expect(within(panel).getByText('No category yet. A rule for "SHELL" says Fuel.')).toBeInTheDocument()

    // A verified row: no note, and it says it was checked.
    fireEvent.click(screen.getByRole('button', { name: 'Details of City Water & Power' }))
    expect(within(panel).getByText('Verified.')).toBeInTheDocument()
    expect(within(panel).queryByText('unverified')).not.toBeInTheDocument()
    expect(within(panel).getByText('The bank file said Utilities.')).toBeInTheDocument()
  })

  test('the history line names the bank file, your choice and the rule', async () => {
    const engine = engineWith()
    const trader = engine.tables['ledger/transactions'].find((row) => row.description === "Trader Joe's #552" && row.txn_date === '2026-10-02')!
    engine.tables['ledger/transaction_categories'] = [
      {
        id: 'c2',
        transaction_id: trader.id,
        category: 'Fun',
        source: 'person',
        created_at: '2026-10-02T00:00:00Z',
        updated_at: '2026-10-02T00:00:00Z',
        deleted_at: null,
        origin_guid: null,
      },
    ]
    renderApp('/money', engine)
    fireEvent.click((await screen.findAllByRole('button', { name: "Details of Trader Joe's #552" }))[0])
    const panel = await screen.findByRole('complementary', { name: 'Selected transaction' })
    expect(
      within(panel).getByText('You chose Fun. The bank file said Groceries. A rule for "TRADER JOE" says Groceries.'),
    ).toBeInTheDocument()
  })

  test('an unreachable ledger says it could not reach the engine, never "none"', async () => {
    const engine = engineWith()
    engine.failingTables.add('ledger/transactions')
    renderApp('/money', engine)
    expect(
      await screen.findByText(/Could not reach the engine, so this is not the real list of transactions/),
    ).toBeInTheDocument()
    expect(screen.queryByText(/No transactions yet/)).not.toBeInTheDocument()
  })

  test('an empty ledger says so in words', async () => {
    const engine = engineWith()
    engine.tables['ledger/transactions'] = []
    renderApp('/money', engine)
    expect(await screen.findByText('No transactions yet. They arrive with the daily bank pull.')).toBeInTheDocument()
  })

  test('?need opens the list already narrowed to rows with no category', async () => {
    renderApp('/money?need=true', engineWith())
    await rowOf('Shell 5521')
    expect(showing()).toBe('Showing 7 of 15 transactions.')
    expect(screen.getByRole('button', { name: /Needs a category/ })).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('budgets', () => {
  async function openBudgets(engine = engineWith()) {
    const view = renderApp('/money?tab=budgets', engine)
    await screen.findByRole('meter', { name: 'Groceries' })
    return { engine, ...view }
  }

  test('a meter per category with a target, "unverified" where the engine says so, and the cents as sent', async () => {
    await openBudgets()

    const groceries = screen.getByRole('meter', { name: 'Groceries' })
    expect(groceries).toHaveAttribute('aria-valuetext', '$381.10 of $500.00')
    const row = groceries.closest('li') as HTMLElement
    expect(within(row).getByText('unverified')).toBeInTheDocument()

    const utilities = screen.getByRole('meter', { name: 'Utilities' }).closest('li') as HTMLElement
    expect(within(utilities).queryByText('unverified')).not.toBeInTheDocument()

    // A category with spend and no target is not hidden: it says so.
    const fun = screen.getByText('Fun').closest('li') as HTMLElement
    expect(within(fun).getByText('No target set')).toBeInTheDocument()
    expect(within(fun).getByText('unverified')).toBeInTheDocument()
  })

  test('says what is left out, and that the month is not final', async () => {
    await openBudgets()

    expect(
      screen.getByText('$41.20 nobody has categorised yet is not in these figures.'),
    ).toBeInTheDocument()
    expect(screen.getByText(/Not final: some days this month are not yet covered/)).toBeInTheDocument()
    expect(
      screen.getByText('$1,200.00 was filed under categories marked not spending (Transfers).'),
    ).toBeInTheDocument()
    expect(screen.getByText('$500.00 moved between your own accounts.')).toBeInTheDocument()
    expect(screen.getByText(/\$1,850\.00 of Housing dated in the last days of September 2026/)).toBeInTheDocument()
  })

  test('per-card figures carry "unverified" beside the card that has it, and as-of from the newest row', async () => {
    await openBudgets()
    const section = screen.getByRole('region', { name: 'Spent this month' })
    const card = within(section).getByText('BofA card').closest('li') as HTMLElement
    expect(within(card).getByText('$642.55')).toBeInTheDocument()
    expect(within(card).getByText('unverified')).toBeInTheDocument()
    expect(within(card).getByText(/As of Oct 3, 2026/)).toBeInTheDocument()
    const checking = within(section).getByText('BofA checking').closest('li') as HTMLElement
    expect(within(checking).getByText('$1,284.10')).toBeInTheDocument()
    expect(within(checking).queryByText('unverified')).not.toBeInTheDocument()
  })

  test('editing a target PUTs a budget_targets row effective from this month, and the meter follows', async () => {
    const { engine } = await openBudgets()

    fireEvent.click(screen.getByRole('button', { name: 'Edit target for Groceries' }))
    const field = await screen.findByLabelText(/Monthly target for Groceries/)
    expect(field).toHaveValue('500.00')
    fireEvent.change(field, { target: { value: '600' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(screen.getByRole('meter', { name: 'Groceries' })).toHaveAttribute('aria-valuetext', '$381.10 of $600.00'),
    )
    const rows = engine.tables['ledger/budget_targets'].filter((row) => row.category === 'Groceries')
    expect(rows).toHaveLength(2)
    expect(rows.find((row) => row.effective_from_month === '2026-10-01')).toMatchObject({
      amount_cents: 60000,
      currency: 'USD',
    })
    // The August target is not rewritten.
    expect(rows.find((row) => row.effective_from_month === '2026-08-01')).toMatchObject({ amount_cents: 50000 })

    // Editing again changes that same row, never a third.
    fireEvent.click(screen.getByRole('button', { name: 'Edit target for Groceries' }))
    fireEvent.change(await screen.findByLabelText(/Monthly target for Groceries/), { target: { value: '650.50' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(screen.getByRole('meter', { name: 'Groceries' })).toHaveAttribute('aria-valuetext', '$381.10 of $650.50'),
    )
    expect(engine.tables['ledger/budget_targets'].filter((row) => row.category === 'Groceries')).toHaveLength(2)
  })

  test('a category with no target can be given one', async () => {
    const { engine } = await openBudgets()
    fireEvent.click(screen.getByRole('button', { name: 'Set target for Fun' }))
    fireEvent.change(await screen.findByLabelText(/Monthly target for Fun/), { target: { value: '40' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.getByRole('meter', { name: 'Fun' })).toBeInTheDocument())
    expect(engine.tables['ledger/budget_targets'].find((row) => row.category === 'Fun')).toMatchObject({
      amount_cents: 4000,
      effective_from_month: '2026-10-01',
    })
  })

  test('a refused target stays open and says nothing was saved; a non-amount is not sent', async () => {
    const { engine } = await openBudgets()
    fireEvent.click(screen.getByRole('button', { name: 'Edit target for Utilities' }))
    const field = await screen.findByLabelText(/Monthly target for Utilities/)

    fireEvent.change(field, { target: { value: 'lots' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing was saved.')
    expect(Object.keys(engine.calls).filter((key) => key.startsWith('PUT /api/ledger/budget_targets'))).toEqual([])

    engine.refusals['PUT /api/ledger/budget_targets/*'] = {
      status: 400,
      body: { amount_cents: ['Targets must be at least a cent.'] },
    }
    fireEvent.change(field, { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText(/Nothing was saved. amount_cents: Targets must be at least a cent./)).toBeInTheDocument()
    expect(screen.getByLabelText(/Monthly target for Utilities/)).toBeInTheDocument()
  })

  test('no card activity says so, not zero', async () => {
    const engine = engineWith()
    engine.spend = { ...seedSpend(), accounts: [], categories: [], uncategorised_cents: 0 }
    renderApp('/money?tab=budgets', engine)
    expect(await screen.findByText(/No card activity has reached the engine for October 2026 yet/)).toBeInTheDocument()
  })

  test('an unreachable spend says it could not reach the engine', async () => {
    const engine = engineWith()
    engine.refusals['GET /api/ledger/spend'] = { status: 503, body: { detail: 'down' } }
    renderApp('/money?tab=budgets', engine)
    expect(
      await screen.findByText(/Could not reach the engine, so this is not the real list of this month's spend/),
    ).toBeInTheDocument()
  })
})

describe('categories', () => {
  test('the not-spending switch PUTs excluded_from_spend, and says in words what each state is', async () => {
    const engine = engineWith()
    renderApp('/money?tab=categories', engine)

    const transfers = await screen.findByRole('switch', { name: 'Not spending: Transfers' })
    expect(transfers).toBeChecked()
    expect(within(transfers.closest('label')!).getByText('Not spending')).toBeInTheDocument()

    const utilities = screen.getByRole('switch', { name: 'Not spending: Utilities' })
    expect(utilities).not.toBeChecked()
    expect(within(utilities.closest('label')!).getByText('Counts as spending')).toBeInTheDocument()

    fireEvent.click(utilities)
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Not spending: Utilities' })).toBeChecked())
    expect(engine.tables['ledger/categories'].find((row) => row.name === 'Utilities')).toMatchObject({
      excluded_from_spend: true,
      is_food_category: false,
    })

    fireEvent.click(screen.getByRole('switch', { name: 'Not spending: Utilities' }))
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Not spending: Utilities' })).not.toBeChecked())
    expect(engine.tables['ledger/categories'].find((row) => row.name === 'Utilities')).toMatchObject({
      excluded_from_spend: false,
    })
  })

  test('a refused switch stays where it was and says nothing was saved', async () => {
    const engine = engineWith()
    engine.refusals['PUT /api/ledger/categories/*'] = { status: 400, body: { detail: 'Nothing was saved. Not today.' } }
    renderApp('/money?tab=categories', engine)

    fireEvent.click(await screen.findByRole('switch', { name: 'Not spending: Fuel' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing was saved. Not today.')
    expect(screen.getByRole('switch', { name: 'Not spending: Fuel' })).not.toBeChecked()
  })

  test('adding a category, and a name that cannot change once it exists', async () => {
    const engine = engineWith()
    renderApp('/money?tab=categories', engine)
    await screen.findByRole('cell', { name: 'Groceries' })

    fireEvent.click(screen.getByRole('button', { name: /Add a category/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Add a category' })
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Pets' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('cell', { name: 'Pets' })).toBeInTheDocument()
    expect(engine.tables['ledger/categories'].find((row) => row.name === 'Pets')).toMatchObject({
      is_food_category: false,
      excluded_from_spend: false,
    })

    fireEvent.click(screen.getByRole('button', { name: 'Edit "Pets"' }))
    const edit = await screen.findByRole('dialog')
    expect(within(edit).getByLabelText('Name')).toBeDisabled()
  })
})

describe('rules', () => {
  test('each rule shows how many loaded transactions its text matches', async () => {
    renderApp('/money?tab=rules', engineWith())

    const trader = (await screen.findByText('TRADER JOE')).closest('tr') as HTMLElement
    await waitFor(() => expect(within(trader).getByText('2')).toBeInTheDocument())
    const shell = screen.getByText('SHELL').closest('tr') as HTMLElement
    expect(within(shell).getByText('1')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Matches in loaded rows' })).toBeInTheDocument()
  })

  test('adding a rule files it under a category from the list', async () => {
    const engine = engineWith()
    renderApp('/money?tab=rules', engine)
    await screen.findByText('TRADER JOE')

    fireEvent.click(screen.getByRole('button', { name: /Add a rule/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Add a rule' })
    fireEvent.change(within(dialog).getByLabelText('When the description contains'), { target: { value: 'amazon' } })
    fireEvent.change(within(dialog).getByLabelText('File it under'), { target: { value: 'Fun' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    const row = (await screen.findByText('amazon')).closest('tr') as HTMLElement
    await waitFor(() => expect(within(row).getByText('1')).toBeInTheDocument())
    expect(engine.tables['ledger/category_rules'].find((rule) => rule.substring === 'amazon')).toMatchObject({
      category: 'Fun',
    })
  })

  test('while the ledger is still loading, a match count says so rather than printing a number', async () => {
    const engine = engineWith({ pageSize: 5 })
    engine.delay = (_method, pathname, search) =>
      pathname === '/api/ledger/transactions/' && search.has('since') ? new Promise(() => {}) : undefined
    renderApp('/money?tab=rules', engine)

    const trader = (await screen.findByText('TRADER JOE')).closest('tr') as HTMLElement
    expect(within(trader).getByText('Still loading')).toBeInTheDocument()
  })
})

describe('files', () => {
  test('states a file in words, the gate reason verbatim, and offers no upload', async () => {
    renderApp('/money?tab=files', engineWith())

    const held = (await screen.findByText('BofA_card_Sep.csv')).closest('tr') as HTMLElement
    expect(within(held).getByText('Held for review')).toBeInTheDocument()
    expect(
      within(held).getByText(/The lines add to -\$1,204\.10 but the statement prints -\$1,254\.10\. Nothing was written\./),
    ).toBeInTheDocument()

    const unreadable = screen.getByText('BofA_card_Aug.pdf').closest('tr') as HTMLElement
    expect(within(unreadable).getByText('Could not be read')).toBeInTheDocument()
    const dupe = screen.getByText('BofA_checking_Sep_copy.csv').closest('tr') as HTMLElement
    expect(within(dupe).getByText('Duplicate')).toBeInTheDocument()
    const ok = screen.getByText('BofA_checking_Sep.csv').closest('tr') as HTMLElement
    expect(within(ok).getByText('Ingested')).toBeInTheDocument()

    expect(screen.queryByRole('button', { name: /add|statement|upload/i })).not.toBeInTheDocument()
  })

  test('an engine with no files says so', async () => {
    const engine = engineWith()
    engine.tables['ingest/files'] = []
    renderApp('/money?tab=files', engine)
    expect(await screen.findByText(/No files have come in yet/)).toBeInTheDocument()
  })

  test('the tabs move between sections and keep their place in the address', async () => {
    const engine = engineWith()
    const { router } = renderApp('/money', engine)
    pressTab(await screen.findByRole('tab', { name: 'Files' }))
    expect(await screen.findByText('BofA_card_Sep.csv')).toBeInTheDocument()
    expect(router.state.location.search).toMatchObject({ tab: 'files' })
  })
})

describe('home', () => {
  test('workbench Home shows what each card spent, "unverified" in words, and what needs a decision', async () => {
    renderApp('/', engineWith())

    const spent = (await screen.findByRole('heading', { name: 'Spent this month' })).closest('section') as HTMLElement
    const card = (await within(spent).findByText('BofA card')).closest('li') as HTMLElement
    expect(within(card).getByText('$642.55')).toBeInTheDocument()
    expect(within(card).getByText('unverified')).toBeInTheDocument()
    expect(within(card).getByText(/As of Oct 3, 2026/)).toBeInTheDocument()
    expect(within(spent).getByRole('link', { name: 'Open Money' })).toBeInTheDocument()

    const decision = screen.getByRole('heading', { name: 'Needs a decision' }).closest('section') as HTMLElement
    expect(await within(decision).findByText('7 transactions need a category')).toBeInTheDocument()
    expect(within(decision).getByRole('link', { name: 'Review' })).toHaveAttribute('href', '/money?need=true')
    expect(await within(decision).findByText('1 file held for review')).toBeInTheDocument()
    expect(within(decision).getByText(/BofA_card_Sep\.csv\. Nothing from it was added\./)).toBeInTheDocument()
    expect(within(decision).getByRole('link', { name: 'See why' })).toHaveAttribute('href', '/money?tab=files')
  })

  test('the count waits while the ledger is partway in, then appears', async () => {
    const engine = engineWith({ pageSize: 5 })
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    engine.delay = (_method, pathname, search) =>
      pathname === '/api/ledger/transactions/' && search.has('since') ? held : undefined
    renderApp('/', engine)

    const decision = (await screen.findByRole('heading', { name: 'Needs a decision' })).closest('section') as HTMLElement
    expect(await within(decision).findByText(/Still loading transactions: 5 so far/)).toBeInTheDocument()
    expect(within(decision).queryByText(/need a category/)).not.toBeInTheDocument()

    release()
    expect(await within(decision).findByText('7 transactions need a category')).toBeInTheDocument()
  })

  test('a Home with nothing to decide says so in words', async () => {
    const engine = engineWith()
    engine.tables['ledger/transactions'] = []
    engine.tables['ingest/files'] = []
    renderApp('/', engine)
    const decision = (await screen.findByRole('heading', { name: 'Needs a decision' })).closest('section') as HTMLElement
    expect(await within(decision).findByText('Every transaction has a category.')).toBeInTheDocument()
    expect(await within(decision).findByText('No bank file is held for review.')).toBeInTheDocument()
  })

  test('an unreachable ledger on Home says it could not reach the engine, not "nothing to decide"', async () => {
    const engine = engineWith()
    engine.failingTables.add('ledger/transactions')
    engine.refusals['GET /api/ledger/spend'] = { status: 503, body: { detail: 'down' } }
    renderApp('/', engine)
    expect(await screen.findByText(/how many transactions need a category is not known/)).toBeInTheDocument()
    expect(
      await screen.findByText(/this is not the real figure for what was spent this month/),
    ).toBeInTheDocument()
    expect(screen.queryByText('Every transaction has a category.')).not.toBeInTheDocument()
  })

  test('family Home has neither money panel, and reads no ledger', async () => {
    const engine = engineWith()
    renderApp('/', engine, 'family')
    await screen.findByText('Nothing due today.')
    expect(screen.queryByRole('heading', { name: 'Needs a decision' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Spent this month' })).not.toBeInTheDocument()
    expect(Object.keys(engine.calls).filter((key) => key.includes('ledger'))).toEqual([])
  })
})
