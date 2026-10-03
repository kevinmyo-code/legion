import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { expect, test } from 'vitest'

import { todayEpochDay } from '@/lib/day'
import { createEngine, makeEvent, todayAt, type Engine } from '@/test/engine'
import { renderApp } from '@/test/render-app'
import type { Surface } from '@/lib/surface'

/**
 * `/calendar` (web-revamp 13, spec D10), read through the real route and the
 * fake engine: the desk's week and month, the phone's month and day agenda, the
 * rule each follows (overlap side by side, all-day in the lane, a skip hides an
 * occurrence, the done box PATCHes, private rows say "Only you"), and the three
 * sentences a read can end in.
 */

function todayKey(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function open(engine: Engine, surface: Surface, path = '/calendar') {
  const view = renderApp(path, engine, surface)
  await screen.findByRole('heading', { name: 'Calendar' })
  return view
}

const column = (day = todayEpochDay()) => document.querySelector(`[data-day="${day}"]`) as HTMLElement
const lane = (day = todayEpochDay()) => document.querySelector(`[data-lane-day="${day}"]`) as HTMLElement

const sheet = () => screen.getByRole('dialog')

test('the desk opens on the week, with Calendar in the rail and the week named in words', async () => {
  await open(createEngine({}), 'workbench')
  const rail = screen.getByRole('navigation', { name: 'Sections' })
  expect(within(rail).getByRole('link', { name: 'Calendar' })).toHaveAttribute('aria-current', 'page')
  expect(screen.getByRole('radio', { name: 'Week' })).toBeChecked()
  // Seven day headings, today marked.
  expect(document.querySelectorAll('[data-day]')).toHaveLength(7)
  expect(document.querySelector('[aria-current="date"]')).not.toBeNull()
})

test('overlapping events sit side by side in the week view', async () => {
  const engine = createEngine({
    events: [
      makeEvent({ title: 'Planning with Mia', starts_at: todayAt(10, 0), ends_at: todayAt(11, 30) }),
      makeEvent({ title: 'Call the plumber', starts_at: todayAt(10, 30), ends_at: todayAt(11, 30) }),
      makeEvent({ title: 'Lunch', starts_at: todayAt(13, 0), ends_at: todayAt(14, 0) }),
    ],
  })
  await open(engine, 'workbench')
  const planning = within(column()).getByRole('button', { name: /^Edit Planning with Mia/ })
  const plumber = within(column()).getByRole('button', { name: /^Edit Call the plumber/ })
  const lunch = within(column()).getByRole('button', { name: /^Edit Lunch/ })
  // Two at once: half the column each, the second one starting at the half.
  expect(planning.style.width).toBe('calc(50% - 4px)')
  expect(plumber.style.width).toBe('calc(50% - 4px)')
  expect(planning.style.left).toBe('calc(0% + 2px)')
  expect(plumber.style.left).toBe('calc(50% + 2px)')
  // A lone event takes the whole column.
  expect(lunch.style.width).toBe('calc(100% - 4px)')
  // Vertical position is the clock: 10:00 is four hours below the 6:00 line.
  expect(planning.style.top).toBe(`${4 * 56 + 1}px`)
})

test('an all-day event sits in the lane, not on the hour grid', async () => {
  const engine = createEngine({
    events: [makeEvent({ title: 'Rent due', all_day: true, starts_at: `${todayKey()}T00:00:00Z` })],
  })
  await open(engine, 'workbench')
  expect(within(lane()).getByRole('button', { name: 'Edit Rent due' })).toBeInTheDocument()
  expect(within(column()).queryByText('Rent due')).not.toBeInTheDocument()
})

test('a task sits in the lane with its time, its Canvas line and its words, and the box PATCHes done', async () => {
  const task = makeEvent({
    title: 'COSC 4320 · HW 4 due',
    kind: 'task',
    starts_at: todayAt(23, 59),
    visibility: 'private',
    origin_guid: 'canvas:1',
    structured_meta: { canvas_assignment_id: 1, canvas_submitted: true },
  })
  const engine = createEngine({ events: [task] })
  await open(engine, 'workbench')

  const chip = within(lane()).getByText('HW 4 due').closest('div.flex-col') as HTMLElement
  expect(within(chip).getByText('Only you')).toBeInTheDocument()
  expect(within(chip).getByText('Canvas says submitted')).toBeInTheDocument()
  expect(within(chip).getByText('11:59 PM')).toBeInTheDocument()
  // Not on the clock face, and not offered for editing: Canvas owns the row.
  expect(within(column()).queryByText('HW 4 due')).not.toBeInTheDocument()
  expect(within(lane()).queryByRole('button', { name: /^Edit/ })).not.toBeInTheDocument()

  fireEvent.click(within(chip).getByRole('checkbox', { name: 'Mark "HW 4 due" done' }))
  await waitFor(() =>
    expect(engine.writes).toContainEqual({
      method: 'PATCH',
      pathname: `/api/events/${task.id}`,
      body: { done: true },
    }),
  )
  await waitFor(() =>
    expect(within(lane()).getByRole('checkbox', { name: 'Mark "HW 4 due" not done' })).toBeInTheDocument(),
  )
})

test('private and shared blocks each say so in words', async () => {
  const engine = createEngine({
    events: [
      makeEvent({ title: 'Dentist', starts_at: todayAt(15, 0), ends_at: todayAt(16, 0) }),
      makeEvent({ title: 'Therapy', starts_at: todayAt(17, 0), ends_at: todayAt(18, 0), visibility: 'private' }),
    ],
  })
  await open(engine, 'workbench')
  const dentist = within(column()).getByRole('button', { name: /^Edit Dentist/ })
  const therapy = within(column()).getByRole('button', { name: /^Edit Therapy/ })
  expect(within(dentist).getByText('Shared')).toBeInTheDocument()
  expect(within(therapy).getByText('Only you')).toBeInTheDocument()
})

test('a skipped occurrence is not drawn, and the other days of the series are', async () => {
  const series = makeEvent({
    title: 'Water the ferns',
    starts_at: todayAt(8, 0, -14),
    ends_at: todayAt(8, 30, -14),
    repeat_kind: 'DAILY',
    repeat_every: 1,
  })
  const engine = createEngine({
    events: [series],
    skips: [
      {
        id: 's1',
        event: series.id,
        skip_date: todayKey(),
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-01T00:00:00Z',
        deleted_at: null,
      },
    ],
  })
  await open(engine, 'workbench')
  expect(within(column()).queryByText('Water the ferns')).not.toBeInTheDocument()
  // Seven columns, one skipped: six blocks.
  expect(screen.getAllByRole('button', { name: /^Edit Water the ferns/ })).toHaveLength(6)
})

test('pressing an empty time opens the sheet on that day at that time', async () => {
  const engine = createEngine({})
  await open(engine, 'workbench')
  const col = column()
  // jsdom has no layout: say where the column is, as a browser would.
  col.getBoundingClientRect = () => ({ top: 100, left: 0, right: 100, bottom: 1108, width: 100, height: 1008, x: 0, y: 100, toJSON() {} })
  fireEvent.click(col, { clientY: 100 + 3 * 56 + 14 }) // three and a quarter hours below 6:00: 9:00 to the half hour

  await screen.findByRole('dialog', { name: 'New event' })
  expect(within(sheet()).getByLabelText('Date')).toHaveValue(todayKey())
  expect(within(sheet()).getByLabelText('Starts')).toHaveValue('09:00')
})

test('pressing an event opens it for editing', async () => {
  const engine = createEngine({
    events: [makeEvent({ title: 'Dentist', starts_at: todayAt(15, 0), ends_at: todayAt(16, 0) })],
  })
  await open(engine, 'workbench')
  fireEvent.click(within(column()).getByRole('button', { name: /^Edit Dentist/ }))
  await screen.findByRole('dialog', { name: 'Edit event' })
  expect(within(sheet()).getByLabelText('Title')).toHaveValue('Dentist')
})

test('the week steps back and forward, and Today comes home', async () => {
  await open(createEngine({}), 'workbench')
  const label = () => screen.getByRole('heading', { level: 2 }).textContent
  const thisWeek = label()
  fireEvent.click(screen.getByRole('button', { name: 'Next week' }))
  expect(label()).not.toBe(thisWeek)
  fireEvent.click(screen.getByRole('button', { name: 'Previous week' }))
  fireEvent.click(screen.getByRole('button', { name: 'Previous week' }))
  expect(label()).not.toBe(thisWeek)
  fireEvent.click(screen.getByRole('button', { name: 'Today' }))
  expect(label()).toBe(thisWeek)
})

test('the month view lists a day, says how many it hides, and opens a day in the week', async () => {
  const events = ['A', 'B', 'C', 'D', 'E'].map((title, index) =>
    makeEvent({ title: `Event ${title}`, starts_at: todayAt(8 + index, 0), ends_at: todayAt(8 + index, 30) }),
  )
  await open(createEngine({ events }), 'workbench')
  fireEvent.click(screen.getByRole('radio', { name: 'Month' }))

  const cell = column()
  expect(within(cell).getByRole('button', { name: 'Edit Event A' })).toBeInTheDocument()
  expect(within(cell).queryByText('Event D')).not.toBeInTheDocument()
  fireEvent.click(within(cell).getByRole('button', { name: /^Show all 5 on/ }))

  // Back in the week view, every one of them.
  expect(screen.getByRole('radio', { name: 'Week' })).toBeChecked()
  expect(within(column()).getAllByRole('button', { name: /^Edit Event / })).toHaveLength(5)
})

test('the month view adds to a day from that day', async () => {
  await open(createEngine({}), 'workbench')
  fireEvent.click(screen.getByRole('radio', { name: 'Month' }))
  fireEvent.click(within(column()).getByRole('button', { name: /^Add an event on/ }))
  await screen.findByRole('dialog', { name: 'New event' })
  expect(within(sheet()).getByLabelText('Date')).toHaveValue(todayKey())
})

test('an empty range says so in words, not as bare hour lines', async () => {
  await open(createEngine({}), 'workbench')
  expect(screen.getByText(/Nothing on the calendar this week\./)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('radio', { name: 'Month' }))
  expect(screen.getByText(/Nothing on the calendar this month\./)).toBeInTheDocument()
})

test('a calendar that could not be read says so, never an empty week', async () => {
  const engine = createEngine({})
  engine.changesFailing = true
  renderApp('/calendar', engine, 'workbench')
  expect(await screen.findByText(/Could not reach the engine, so this is not the real calendar\./)).toBeInTheDocument()
  expect(screen.queryByText(/Nothing on the calendar/)).not.toBeInTheDocument()
})

test('the phone gets the month and the day agenda, with Calendar in the tab bar', async () => {
  const engine = createEngine({
    events: [
      makeEvent({ title: 'Dentist', starts_at: todayAt(15, 15) }),
      makeEvent({ title: 'Therapy', starts_at: todayAt(16, 30), visibility: 'private' }),
    ],
  })
  await open(engine, 'family')
  const tabs = screen.getByRole('navigation', { name: 'Tabs' })
  expect(within(tabs).getByRole('link', { name: 'Calendar' })).toHaveAttribute('aria-current', 'page')
  // The desk's controls are not in the tree at phone width.
  expect(screen.queryByRole('radio', { name: 'Week' })).not.toBeInTheDocument()
  expect(document.querySelector('[data-day]')).toBeNull()

  const dentist = (await screen.findByText('Dentist')).closest('li') as HTMLElement
  expect(within(dentist).getByText('Shared')).toBeInTheDocument()
  const therapy = screen.getByText('Therapy').closest('li') as HTMLElement
  expect(within(therapy).getByText('Only you')).toBeInTheDocument()

  // Add, from the selected day.
  fireEvent.click(screen.getByRole('button', { name: /^Add an event on/ }))
  await screen.findByRole('dialog', { name: 'New event' })
  expect(within(sheet()).getByLabelText('Date')).toHaveValue(todayKey())
})

test('the "+" is on the phone calendar too, and a skipped occurrence is not in its agenda', async () => {
  const series = makeEvent({
    title: 'Water the ferns',
    starts_at: todayAt(8, 0, -7),
    repeat_kind: 'DAILY',
    repeat_every: 1,
  })
  const engine = createEngine({
    events: [series],
    skips: [
      {
        id: 's1',
        event: series.id,
        skip_date: todayKey(),
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-01T00:00:00Z',
        deleted_at: null,
      },
    ],
  })
  await open(engine, 'family')
  expect(screen.getByRole('button', { name: 'New event' })).toBeInTheDocument()
  expect(await screen.findByText('Nothing on the calendar this day.')).toBeInTheDocument()
  expect(screen.queryByText('Water the ferns')).not.toBeInTheDocument()
})

test('the desk Home shows today and the next 7 days, one block a day, and says "Nothing planned"', async () => {
  const engine = createEngine({
    events: [
      makeEvent({ title: 'Dentist', starts_at: todayAt(15, 15) }),
      makeEvent({ title: 'Farmers market', starts_at: todayAt(9, 0, 3) }),
      makeEvent({ title: 'Therapy', starts_at: todayAt(16, 0, 3), visibility: 'private' }),
    ],
  })
  renderApp('/', engine, 'workbench')
  const panel = (await screen.findByRole('heading', { name: 'Today and the next 7 days' })).closest(
    'section',
  ) as HTMLElement
  expect(within(panel).getAllByRole('listitem').length).toBeGreaterThanOrEqual(8)
  expect(within(panel).getByText('Today')).toBeInTheDocument()
  expect(within(panel).getByText('Dentist')).toBeInTheDocument()
  expect(within(panel).getByText('Farmers market')).toBeInTheDocument()
  expect(within(panel).getByText('Only you')).toBeInTheDocument()
  expect(within(panel).getAllByText('Nothing planned').length).toBe(6)
  // The phone's sections are not on the desk.
  expect(screen.queryByRole('heading', { name: 'On today' })).not.toBeInTheDocument()
})

test('the desk Home agenda says when the whole stretch is empty', async () => {
  renderApp('/', createEngine({}), 'workbench')
  expect(await screen.findByText('Nothing on the calendar for the next 7 days.')).toBeInTheDocument()
})

test('the desk Home has a labelled New event button, and the phone Home a "+"', async () => {
  const wide = renderApp('/', createEngine({}), 'workbench')
  fireEvent.click(await screen.findByRole('button', { name: 'New event' }))
  await screen.findByRole('dialog', { name: 'New event' })
  expect(screen.getByRole('button', { name: 'New event', hidden: true })).toHaveTextContent('New event')
  wide.unmount()

  renderApp('/', createEngine({}), 'family')
  const plus = await screen.findByRole('button', { name: 'New event' })
  expect(plus).toHaveTextContent('')
  expect(plus.className).toContain('size-14')
})
