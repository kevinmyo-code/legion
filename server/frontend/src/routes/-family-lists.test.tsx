import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'

import { PINS_KEY, readPins } from '@/lib/pins'
import { createEngine, makeChecklist, makeItem, seedHousehold, type Engine } from '@/test/engine'
import { renderApp } from '@/test/render-app'
import type { Surface } from '@/lib/surface'

/**
 * `/lists` as the family surface reads it (web-revamp 10, spec D11): the list
 * header says who sees it, a ticked item drops into a collapsed "Ticked" section
 * instead of vanishing, and a list can be pinned to this device's Home.
 */

async function lists(engine: Engine = createEngine(seedHousehold()), surface: Surface = 'family') {
  const view = renderApp('/lists', engine, surface)
  await screen.findByRole('heading', { name: 'Groceries' })
  return view
}

function card(name: string): HTMLElement {
  return screen.getByRole('heading', { name }).closest('div.rounded-sheet') as HTMLElement
}

describe('the Ticked section', () => {
  test('is collapsed: a ticked item is counted, not listed', async () => {
    await lists()
    const groceries = card('Groceries')
    // Six items, "Eggs" already ticked.
    expect(within(groceries).getByRole('button', { name: /Ticked 1/ })).toHaveAttribute('aria-expanded', 'false')
    expect(within(groceries).queryByText('Eggs')).not.toBeInTheDocument()
    expect(within(groceries).getByText('Oat milk')).toBeInTheDocument()
    expect(within(groceries).getByText('5 left')).toBeInTheDocument()
  })

  test('ticking drops the item into Ticked and unticking brings it back', async () => {
    const engine = createEngine(seedHousehold())
    await lists(engine)
    const groceries = card('Groceries')

    fireEvent.click(within(groceries).getByRole('checkbox', { name: 'Mark "Oat milk" done' }))
    const ticked = await within(groceries).findByRole('button', { name: /Ticked 2/ })
    expect(within(groceries).queryByText('Oat milk')).not.toBeInTheDocument()
    expect(within(groceries).getByText('4 left')).toBeInTheDocument()

    fireEvent.click(ticked)
    expect(ticked).toHaveAttribute('aria-expanded', 'true')
    const undo = within(groceries).getByRole('checkbox', { name: 'Mark "Oat milk" not done' })
    expect(undo).toBeChecked()

    // A mis-tap is undone from there.
    fireEvent.click(undo)
    await waitFor(() => expect(within(groceries).getByRole('button', { name: /Ticked 1/ })).toBeInTheDocument())
    expect(within(groceries).getByRole('checkbox', { name: 'Mark "Oat milk" done' })).not.toBeChecked()
    expect(engine.writes.map((write) => write.method)).toContain('DELETE')
  })

  test('ticking the last open item says nothing is left, not that the list is empty', async () => {
    const list = makeChecklist({ name: 'Groceries' })
    const engine = createEngine({ checklists: [list], items: [makeItem(list, 'Eggs')] })
    await lists(engine)
    const groceries = card('Groceries')
    fireEvent.click(within(groceries).getByRole('checkbox', { name: 'Mark "Eggs" done' }))
    expect(await within(groceries).findByText('Nothing left on this list.')).toBeInTheDocument()
    expect(within(groceries).queryByText('Nothing on this list yet.')).not.toBeInTheDocument()
    expect(within(groceries).getByRole('button', { name: /Ticked 1/ })).toBeInTheDocument()
  })

  test('a list with nothing ticked has no Ticked section at all', async () => {
    await lists()
    expect(within(card('Hardware store')).queryByRole('button', { name: /Ticked/ })).not.toBeInTheDocument()
  })

  test('says ticked, never bought', async () => {
    await lists()
    expect(screen.queryByText(/bought/i)).not.toBeInTheDocument()
  })
})

describe('the header', () => {
  test('carries the Shared / Only you mark in words', async () => {
    const engine = createEngine({
      ...seedHousehold(),
      checklists: [
        makeChecklist({ name: 'Groceries' }),
        makeChecklist({ name: 'Secrets', sort_order: 1, visibility: 'private' }),
      ],
    })
    await lists(engine)
    expect(within(card('Groceries')).getByText('Shared')).toBeInTheDocument()
    expect(within(card('Secrets')).getByText('Only you')).toBeInTheDocument()
  })
})

describe('pinning', () => {
  test.each<Surface>(['family', 'workbench'])('a list header has a pin button at %s width', async (surface) => {
    await lists(undefined, surface)
    expect(within(card('Groceries')).getByRole('button', { name: 'Pin "Groceries" to Home' })).toBeInTheDocument()
  })

  test('pinning says Pinned in words, persists on this device, and unpins', async () => {
    const engine = createEngine(seedHousehold())
    await lists(engine)
    const groceries = card('Groceries')
    const pin = within(groceries).getByRole('button', { name: 'Pin "Groceries" to Home' })
    expect(pin).toHaveAttribute('aria-pressed', 'false')
    expect(pin).toHaveTextContent('Pin to Home')

    fireEvent.click(pin)
    await waitFor(() => expect(pin).toHaveAttribute('aria-pressed', 'true'))
    expect(pin).toHaveTextContent('Pinned')
    const id = engine.checklists.find((list) => list.name === 'Groceries')!.id
    expect(readPins()).toEqual([id])

    fireEvent.click(pin)
    await waitFor(() => expect(pin).toHaveAttribute('aria-pressed', 'false'))
    expect(readPins()).toEqual([])
  })

  test('a pin made here is what Home shows', async () => {
    const engine = createEngine(seedHousehold())
    const { router } = await lists(engine)
    fireEvent.click(within(card('Hardware store')).getByRole('button', { name: 'Pin "Hardware store" to Home' }))
    await router.navigate({ to: '/' })
    expect(await screen.findByRole('heading', { name: 'Pinned lists' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Hardware store/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Groceries/ })).not.toBeInTheDocument()
  })

  test('a fourth pin is refused in words, and says why', async () => {
    const names = ['One', 'Two', 'Three', 'Four']
    const made = names.map((name, index) => makeChecklist({ name, sort_order: index }))
    window.localStorage.setItem(PINS_KEY, JSON.stringify(made.slice(0, 3).map((list) => list.id)))
    const engine = createEngine({ checklists: made })
    renderApp('/lists', engine, 'family')
    await screen.findByRole('heading', { name: 'Four' })

    const pin = within(card('Four')).getByRole('button', { name: 'Pin "Four" to Home' })
    fireEvent.click(pin)
    expect(await screen.findByText('Home shows 3 pinned lists. Unpin one first.')).toBeInTheDocument()
    expect(pin).toHaveAttribute('aria-pressed', 'false')
    expect(readPins()).toHaveLength(3)
  })

  test('a device that will not keep the pin says nothing changed', async () => {
    await lists()
    const real = Storage.prototype.setItem
    Storage.prototype.setItem = () => {
      throw new Error('quota')
    }
    try {
      fireEvent.click(within(card('Groceries')).getByRole('button', { name: 'Pin "Groceries" to Home' }))
      expect(await screen.findByText('This device would not keep the pin, so nothing changed.')).toBeInTheDocument()
    } finally {
      Storage.prototype.setItem = real
    }
    expect(within(card('Groceries')).getByRole('button', { name: 'Pin "Groceries" to Home' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })
})

describe('the built-in Groceries list', () => {
  test('offers no delete, no privacy toggle, and no delete nudge when everything is ticked; an ordinary list keeps them', async () => {
    const groceries = makeChecklist({ name: 'Groceries', system_key: 'groceries' })
    const hardware = makeChecklist({ name: 'Hardware store', sort_order: 1 })
    const engine = createEngine({
      checklists: [groceries, hardware],
      items: [makeItem(groceries, 'Eggs'), makeItem(hardware, 'Nails')],
    })
    await lists(engine)
    const builtIn = card('Groceries')
    const ordinary = card('Hardware store')
    expect(within(builtIn).queryByRole('button', { name: /Delete/i })).not.toBeInTheDocument()
    expect(within(builtIn).queryByRole('button', { name: /Make it only yours/i })).not.toBeInTheDocument()
    expect(within(builtIn).getByText('Shared')).toBeInTheDocument()
    expect(within(ordinary).getByRole('button', { name: /Delete/i })).toBeInTheDocument()
    expect(within(ordinary).getByRole('button', { name: /Make it only yours/i })).toBeInTheDocument()
  })

  test('a list merely named Groceries is an ordinary list: deletable', async () => {
    const lookalike = makeChecklist({ name: 'Groceries' })
    const engine = createEngine({ checklists: [lookalike], items: [makeItem(lookalike, 'Eggs')] })
    await lists(engine)
    expect(within(card('Groceries')).getByRole('button', { name: /Delete/i })).toBeInTheDocument()
  })
})
