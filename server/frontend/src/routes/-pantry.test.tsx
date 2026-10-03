import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { seedAspects } from '@/test/aspects-seed'
import { createEngine } from '@/test/engine'
import { renderApp } from '@/test/render-app'

/**
 * `/pantry`, driven the way a person drives it: render the route, answer HTTP
 * from the fake engine, press things, read the screen. The trust rules are the
 * point: macros are labelled estimates, an unaccounted amount is never tax, and
 * a receipt the gate could not verify says so in words.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

function engineWith(tables = seedAspects(), options: { pageSize?: number } = {}) {
  return createEngine({ tables, ...options })
}

async function receiptRow(store: string): Promise<HTMLElement> {
  const cell = await screen.findByRole('cell', { name: store })
  return cell.closest('tr') as HTMLElement
}

describe('at family width', () => {
  test('is the bigger-screen card, with no table in the page and no table read', async () => {
    const engine = engineWith()
    renderApp('/pantry', engine, 'family')

    expect(await screen.findByText('This page is made for a bigger screen.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Home' })).toBeInTheDocument()
    expect(screen.queryByText('Grocery staples')).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(Object.keys(engine.calls).filter((key) => key.includes('pantry'))).toEqual([])
  })
})

describe('at workbench width', () => {
  test('lists the staples and the receipts, and has a rail item', async () => {
    const engine = engineWith()
    renderApp('/pantry', engine)

    expect(await screen.findByRole('cell', { name: 'Oat milk' })).toBeInTheDocument()
    expect(await screen.findByRole('cell', { name: "Trader Joe's" })).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: 'Sections' })).getByRole('link', { name: 'Pantry' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(engine.unhandled).toEqual([])
  })

  test('a receipt line says "estimate" beside every macro figure, and says when there is none', async () => {
    renderApp('/pantry', engineWith())

    const row = await receiptRow("Trader Joe's")
    fireEvent.click(within(row).getByRole('button', { name: /Show lines/ }))

    const oat = (await screen.findByRole('cell', { name: 'Oat milk, 64 oz' })).closest('tr') as HTMLElement
    expect(within(oat).getByText(/120 kcal, 3 g protein, 16 g carbs, 5 g fat/)).toBeInTheDocument()
    expect(within(oat).getByText('estimate')).toBeInTheDocument()

    // Three of the four lines carry macros and each has its own word; the bag has none.
    expect(screen.getAllByText('estimate')).toHaveLength(3)
    const bag = (await screen.findByRole('cell', { name: 'Reusable bag' })).closest('tr') as HTMLElement
    expect(within(bag).getByText('No macro estimate')).toBeInTheDocument()
    expect(within(bag).queryByText('estimate')).not.toBeInTheDocument()
  })

  test('an unaccounted amount is called unaccounted, never tax', async () => {
    renderApp('/pantry', engineWith())

    const row = await receiptRow('Costco Wholesale')
    // On the row itself, not only behind the expander.
    expect(within(row).getByText('$21.50 unaccounted')).toBeInTheDocument()

    fireEvent.click(within(row).getByRole('button', { name: /Show lines/ }))
    expect(await screen.findByText(/\$21\.50 unaccounted\./)).toBeInTheDocument()
    // The printed tax is "not stated": the gap was not folded into it.
    const tax = (await screen.findByText('Tax printed')).parentElement as HTMLElement
    expect(within(tax).getByText('not stated')).toBeInTheDocument()
  })

  test('a receipt the gate could not verify says "unverified" in words; a verified one does not', async () => {
    renderApp('/pantry', engineWith())

    const costco = await receiptRow('Costco Wholesale')
    expect(within(costco).getByText('unverified')).toBeInTheDocument()

    const trader = await receiptRow("Trader Joe's")
    expect(within(trader).queryByText('unverified')).not.toBeInTheDocument()
    expect(within(trader).getByText('Verified, read by a model')).toBeInTheDocument()
    const corner = await receiptRow('Corner Market')
    expect(within(corner).getByText('Verified, read by a parser')).toBeInTheDocument()
  })

  test('staples round-trip: add, edit, delete, each through the engine', async () => {
    const engine = engineWith()
    renderApp('/pantry', engine)
    await screen.findByRole('cell', { name: 'Oat milk' })

    // Add.
    fireEvent.click(screen.getByRole('button', { name: /Add a staple/ }))
    let dialog = await screen.findByRole('dialog', { name: 'Add a staple' })
    fireEvent.change(within(dialog).getByLabelText('Item'), { target: { value: 'Rice' } })
    fireEvent.change(within(dialog).getByLabelText('Times ticked'), { target: { value: '3' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('cell', { name: 'Rice' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const stored = engine.tables['pantry/grocery_staples'].find((row) => row.display_name === 'Rice')
    expect(stored).toMatchObject({ name: 'rice', times_bought: 3 })

    // Edit.
    fireEvent.click(screen.getByRole('button', { name: 'Edit "Rice"' }))
    dialog = await screen.findByRole('dialog', { name: 'Edit "Rice"' })
    fireEvent.change(within(dialog).getByLabelText('Times ticked'), { target: { value: '4' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(stored).toMatchObject({ times_bought: 4, name: 'rice' }))

    // Delete asks first, then the row goes.
    fireEvent.click(screen.getByRole('button', { name: 'Delete "Rice"' }))
    expect(screen.getByText('This staple is removed for everyone in the household.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(screen.queryByRole('cell', { name: 'Rice' })).not.toBeInTheDocument())
    expect(stored?.deleted_at).not.toBeNull()
  })

  test('a refused save keeps the form open and says what did not happen', async () => {
    const engine = engineWith()
    engine.refusals['PUT /api/pantry/grocery_staples/*'] = {
      status: 400,
      body: { name: ['This staple already exists.'] },
    }
    renderApp('/pantry', engine)
    await screen.findByRole('cell', { name: 'Oat milk' })

    fireEvent.click(screen.getByRole('button', { name: /Add a staple/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Item'), { target: { value: 'Eggs' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Nothing was saved. name: This staple already exists.',
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(engine.tables['pantry/grocery_staples']).toHaveLength(5)
  })

  test('a refused delete says nothing was deleted and keeps the row', async () => {
    const engine = engineWith()
    engine.refusals['DELETE /api/pantry/grocery_staples/*'] = {
      status: 500,
      body: { detail: 'The database is read only right now.' },
    }
    renderApp('/pantry', engine)
    await screen.findByRole('cell', { name: 'Oat milk' })

    fireEvent.click(screen.getByRole('button', { name: 'Delete "Oat milk"' }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Nothing was deleted. The database is read only right now.',
    )
    expect(screen.getByRole('cell', { name: 'Oat milk' })).toBeInTheDocument()
  })

  test('follows every page to the end: five staples across pages of two, ending on an exactly-full page', async () => {
    const tables = seedAspects()
    // Four rows with page size two: the second page is full and `next` is not
    // null, so the real last page is an empty one. Stopping at a full page would
    // lose nothing here but is the bug this guards: stopping at the FIRST page.
    tables['pantry/grocery_staples'] = tables['pantry/grocery_staples'].slice(0, 4)
    const engine = engineWith(tables, { pageSize: 2 })
    renderApp('/pantry', engine)

    for (const name of ['Oat milk', 'Eggs', 'Spinach', 'Bananas']) {
      expect(await screen.findByRole('cell', { name })).toBeInTheDocument()
    }
    expect(engine.calls['GET /api/pantry/grocery_staples/']).toBeGreaterThanOrEqual(3)
  })

  test('empty, could-not-reach and stale are three different sentences', async () => {
    // Empty: the read worked and there is nothing.
    const empty = engineWith({})
    const first = renderApp('/pantry', empty)
    expect(await screen.findByText(/No staples yet\./)).toBeInTheDocument()
    expect(await screen.findByText(/No receipts yet\./)).toBeInTheDocument()
    expect(screen.queryByText(/Could not reach/)).not.toBeInTheDocument()
    first.unmount()

    // Could not reach: the read failed and there is nothing earlier to show.
    const failing = engineWith()
    failing.failingTables.add('pantry/receipts')
    const second = renderApp('/pantry', failing)
    expect(await screen.findByText(/Could not reach the engine, so this is not the real list of receipts\./)).toBeInTheDocument()
    expect(screen.queryByText(/No receipts yet\./)).not.toBeInTheDocument()
    expect(await screen.findByRole('cell', { name: 'Oat milk' })).toBeInTheDocument()
    second.unmount()

    // Stale: an earlier read is on screen and the refresh failed.
    const stale = engineWith()
    const third = renderApp('/pantry', stale)
    await screen.findByRole('cell', { name: "Trader Joe's" })
    stale.failingTables.add('pantry/receipts')
    await third.queryClient.refetchQueries({ queryKey: ['rows', 'pantry/receipts'] })
    expect(await screen.findByText(/not what is there now/)).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: "Trader Joe's" })).toBeInTheDocument()
  })
})
