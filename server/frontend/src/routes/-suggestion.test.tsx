import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { expect, test } from 'vitest'

import { createEngine, makeEvent, todayAt, type Engine } from '@/test/engine'
import { renderApp } from '@/test/render-app'

/** The suggestion kind end to end: shown apart and in words on the calendar,
 * absent from Home, and carrying exactly two actions. */

function engineWithSuggestion(): Engine {
  return createEngine({
    events: [
      makeEvent({ title: 'Dentist', kind: 'event', starts_at: todayAt(15, 15) }),
      makeEvent({
        title: 'Riverfest',
        kind: 'suggestion',
        starts_at: todayAt(11, 0),
        location: 'Riverside Park',
        notes: 'https://example.test/riverfest Free entry',
      }),
    ],
  })
}

async function calendar(engine: Engine) {
  renderApp('/calendar', engine, 'family')
  await screen.findByRole('heading', { name: 'Calendar' })
  return screen.findByRole('button', { name: 'Edit Riverfest' })
}

test('Home does not list a suggestion among today, and the desk agenda does not either', async () => {
  renderApp('/', engineWithSuggestion(), 'family')
  await screen.findByRole('heading', { name: 'On today' })
  await screen.findByText('Dentist')
  expect(screen.queryByText('Riverfest')).not.toBeInTheDocument()
})

test('the desk Home agenda does not list one', async () => {
  renderApp('/', engineWithSuggestion(), 'workbench')
  await screen.findByRole('heading', { name: 'Today and the next 7 days' })
  await screen.findByText('Dentist')
  expect(screen.queryByText('Riverfest')).not.toBeInTheDocument()
})

test('the calendar day lists it apart, with the word and no checkbox', async () => {
  await calendar(engineWithSuggestion())
  expect(screen.getByText('Suggestions, not in your plans')).toBeInTheDocument()
  expect(screen.getAllByText('Suggestion').length).toBeGreaterThan(0)
  const row = screen.getByRole('button', { name: 'Edit Riverfest' }).closest('li')!
  expect(within(row).queryByRole('checkbox')).toBeNull()
})

test('Add to my plans sends kind "event" and nothing else', async () => {
  const engine = engineWithSuggestion()
  fireEvent.click(await calendar(engine))
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByText(/Riverside Park/)).toBeInTheDocument()
  expect(within(dialog).getByRole('link', { name: 'Open the source page' })).toHaveAttribute(
    'href',
    'https://example.test/riverfest',
  )
  // Exactly two actions besides closing.
  expect(within(dialog).getAllByRole('button').map((b) => b.textContent)).toEqual([
    'Close',
    'Not interested',
    'Add to my plans',
  ])
  fireEvent.click(within(dialog).getByRole('button', { name: 'Add to my plans' }))
  await waitFor(() => expect(engine.writes.filter((w) => w.method === 'PATCH')).toHaveLength(1))
  expect(engine.writes.find((w) => w.method === 'PATCH')!.body).toEqual({ kind: 'event' })
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
})

test('Not interested deletes it', async () => {
  const engine = engineWithSuggestion()
  fireEvent.click(await calendar(engine))
  fireEvent.click(await screen.findByRole('button', { name: 'Not interested' }))
  await waitFor(() => expect(engine.writes.filter((w) => w.method === 'DELETE')).toHaveLength(1))
})

test('a refusal says what did not happen and keeps the sheet open', async () => {
  const engine = engineWithSuggestion()
  const id = engine.events.find((e) => e.title === 'Riverfest')!.id
  engine.refusals[`PATCH /api/events/${id}`] = { status: 400, body: { detail: 'The engine was busy.' } }
  fireEvent.click(await calendar(engine))
  fireEvent.click(await screen.findByRole('button', { name: 'Add to my plans' }))
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent(/not/i)
  expect(alert).toHaveTextContent('The engine was busy.')
  expect(screen.getByRole('dialog')).toBeInTheDocument()
})

test('a structured suggestion shows venue, city, price and links its url', async () => {
  const engine = createEngine({
    events: [
      makeEvent({
        title: 'Jazz night',
        kind: 'suggestion',
        starts_at: todayAt(19, 0),
        location: 'The Hall, 1 Main St, Austin TX',
        notes: 'see https://wrong.test/notes',
        structured_meta: { city: 'Austin', venue: 'The Hall', address: '1 Main St', url: 'https://right.test/jazz', price: '$15' },
      }),
    ],
  })
  renderApp('/calendar', engine, 'family')
  await screen.findByRole('heading', { name: 'Calendar' })
  // The row itself is the page link now; the sheet sits behind its own button.
  fireEvent.click(await screen.findByRole('button', { name: 'Add or dismiss Jazz night' }))
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByText('The Hall')).toBeInTheDocument()
  expect(within(dialog).getByText('Austin')).toBeInTheDocument()
  expect(within(dialog).getByText('Price: $15')).toBeInTheDocument()
  const link = within(dialog).getByRole('link', { name: 'Open the source page' })
  expect(link).toHaveAttribute('href', 'https://right.test/jazz')
  expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  expect(link).toHaveAttribute('target', '_blank')
})

test('an unsafe structured url is not linked and notes are not searched', async () => {
  const engine = createEngine({
    events: [
      makeEvent({
        title: 'Odd',
        kind: 'suggestion',
        starts_at: todayAt(19, 0),
        notes: 'https://notes.test/',
        structured_meta: { city: 'Austin', url: 'javascript:alert(1)' },
      }),
    ],
  })
  renderApp('/calendar', engine, 'family')
  await screen.findByRole('heading', { name: 'Calendar' })
  fireEvent.click(await screen.findByRole('button', { name: 'Edit Odd' }))
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).queryByRole('link')).toBeNull()
})

function engineWithPage(url: unknown): Engine {
  return createEngine({
    events: [
      makeEvent({
        title: 'Jazz night',
        kind: 'suggestion',
        starts_at: todayAt(19, 0),
        notes: 'https://notes.test/jazz',
        structured_meta: { city: 'Austin', venue: 'The Hall', url },
      }),
    ],
  })
}

async function calendarDay(engine: Engine) {
  renderApp('/calendar', engine, 'family')
  await screen.findByRole('heading', { name: 'Calendar' })
  await screen.findByText('Suggestions, not in your plans')
}

test('a suggestion row with an http(s) page is a real link to it, in a new tab', async () => {
  await calendarDay(engineWithPage('https://right.test/jazz'))
  const link = screen.getByRole('link', { name: 'Open event page for Jazz night' })
  expect(link.tagName).toBe('A')
  expect(link).toHaveAttribute('href', 'https://right.test/jazz')
  expect(link).toHaveAttribute('target', '_blank')
  expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  // The affordance is visible in words, not only in the accessible name.
  expect(within(link).getByText('Open event page')).toBeInTheDocument()
  // The row no longer offers to "Edit" itself; the sheet is behind its own button.
  expect(screen.queryByRole('button', { name: 'Edit Jazz night' })).toBeNull()
})

test('the actions button sits outside the link and opens the sheet without navigating', async () => {
  const engine = engineWithPage('https://right.test/jazz')
  await calendarDay(engine)
  const link = screen.getByRole('link', { name: 'Open event page for Jazz night' })
  expect(within(link).queryAllByRole('button')).toHaveLength(0)
  const linkClicks: Event[] = []
  link.addEventListener('click', (e) => linkClicks.push(e))
  const actions = screen.getByRole('button', { name: 'Add or dismiss Jazz night' })
  expect(actions.closest('a')).toBeNull()
  const before = window.location.href
  fireEvent.click(actions)
  const dialog = await screen.findByRole('dialog')
  expect(linkClicks).toHaveLength(0)
  expect(window.location.href).toBe(before)
  // Both actions stay reachable, and pressing one writes without touching the link.
  fireEvent.click(within(dialog).getByRole('button', { name: 'Add to my plans' }))
  await waitFor(() => expect(engine.writes.filter((w) => w.method === 'PATCH')).toHaveLength(1))
  expect(linkClicks).toHaveLength(0)
  expect(window.location.href).toBe(before)
})

test.each([
  ['javascript:', 'javascript:alert(1)'],
  ['file:', 'file:///etc/passwd'],
  ['blank', '   '],
  ['missing', null],
])('a %s page leaves the row opening the sheet, with no link', async (_name, url) => {
  await calendarDay(engineWithPage(url))
  expect(screen.queryByRole('link', { name: /Open event page/ })).toBeNull()
  expect(screen.queryByText('Open event page')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Add or dismiss Jazz night' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Edit Jazz night' }))
  expect(await screen.findByRole('dialog')).toBeInTheDocument()
})
