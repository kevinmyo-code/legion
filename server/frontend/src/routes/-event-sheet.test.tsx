import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { expect, test } from 'vitest'

import type { Event } from '@/api/types'
import { createEngine, makeEvent, todayAt, type Engine } from '@/test/engine'
import { renderApp } from '@/test/render-app'
import type { Surface } from '@/lib/surface'

/**
 * The event sheet (web-revamp 09): one form, opened from a "+" on Home or from a
 * row, writing through the engine and never optimistically. Every test drives
 * what a person does and reads what the engine was sent, so "the skip went
 * first, then the POST" is read off the wire and not inferred from a hook.
 */

function todayKey(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function daily(): Event {
  return makeEvent({
    title: 'Water the ferns',
    starts_at: todayAt(9, 0, -7),
    ends_at: todayAt(9, 30, -7),
    repeat_kind: 'DAILY',
    repeat_every: 1,
    remind_minutes_before: 15,
  })
}

async function home(engine: Engine, surface: Surface = 'family') {
  const view = renderApp('/', engine, surface)
  await screen.findByRole('heading', { name: 'On today' })
  return view
}

function onToday(): HTMLElement {
  return screen.getByRole('heading', { name: 'On today' }).closest('section') as HTMLElement
}

const sheet = () => screen.getByRole('dialog')

function type(label: string | RegExp, value: string) {
  fireEvent.change(within(sheet()).getByLabelText(label), { target: { value } })
}

function press(name: string | RegExp) {
  fireEvent.click(within(sheet()).getByRole('button', { name }))
}

async function openNew() {
  fireEvent.click(screen.getByRole('button', { name: 'New event' }))
  await screen.findByRole('dialog')
}

async function openSeries() {
  fireEvent.click(within(onToday()).getByRole('button', { name: 'Edit Water the ferns' }))
  await screen.findByRole('dialog', { name: 'Edit event' })
}

const writesTo = (engine: Engine, method: string, prefix: string) =>
  engine.writes.filter((w) => w.method === method && w.pathname.startsWith(prefix))

test('the "+" on Home opens a bottom sheet at family width and a side panel at workbench width', async () => {
  const family = createEngine({})
  const { unmount } = await home(family, 'family')
  await openNew()
  expect(sheet()).toHaveAttribute('data-surface', 'family')
  expect(sheet().className).toContain('bottom-0')
  unmount()

  const wide = createEngine({})
  await home(wide, 'workbench')
  await openNew()
  expect(sheet()).toHaveAttribute('data-surface', 'workbench')
  expect(sheet().className).toContain('right-0')
})

test('creating a one-off event sends the form, defaults to Shared, and the row appears', async () => {
  const engine = createEngine({})
  await home(engine)
  await openNew()

  expect(within(sheet()).getByRole('radio', { name: 'Shared' })).toBeChecked()
  type('Title', 'Parent-teacher night')
  type('Location (optional)', 'Maplewood Elementary')
  type('Notes (optional)', 'Bring the form')
  type('Starts', '18:30')
  type('Ends (optional)', '19:30')
  press('Add')

  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  const [write] = writesTo(engine, 'POST', '/api/events')
  const body = write.body as Record<string, unknown>
  expect(body).toMatchObject({
    title: 'Parent-teacher night',
    kind: 'event',
    all_day: false,
    location: 'Maplewood Elementary',
    notes: 'Bring the form',
    visibility: 'shared',
    repeat_kind: null,
    remind_minutes_before: null,
  })
  const startsAt = new Date(body.starts_at as string)
  expect([startsAt.getHours(), startsAt.getMinutes()]).toEqual([18, 30])
  expect(new Date(body.ends_at as string).getHours()).toBe(19)
  // And it is on Home, with the word that says who sees it.
  const row = (await within(onToday()).findByText('Parent-teacher night')).closest('li') as HTMLElement
  expect(within(row).getByText('Shared')).toBeInTheDocument()
})

test('an all-day event is stored on UTC midnight of its date', async () => {
  const engine = createEngine({})
  await home(engine)
  await openNew()
  type('Title', 'Bins out')
  fireEvent.click(within(sheet()).getByRole('switch', { name: 'All day' }))
  expect(within(sheet()).queryByLabelText('Starts')).not.toBeInTheDocument()
  press('Add')
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  const body = writesTo(engine, 'POST', '/api/events')[0].body as Record<string, unknown>
  expect(body.all_day).toBe(true)
  expect(body.starts_at).toBe(`${todayKey()}T00:00:00.000Z`)
  expect(body.ends_at).toBeNull()
})

test('a weekly event takes weekday chips and an end after N', async () => {
  const engine = createEngine({})
  await home(engine)
  await openNew()
  type('Title', 'Swim lesson')
  fireEvent.click(within(sheet()).getByRole('radio', { name: 'Weekly' }))
  fireEvent.click(within(sheet()).getByRole('button', { name: 'Mon' }))
  fireEvent.click(within(sheet()).getByRole('button', { name: 'Wed' }))
  type('Repeat ends', 'after')
  type('Times', '6')
  press('Add')

  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(writesTo(engine, 'POST', '/api/events')[0].body).toMatchObject({
    repeat_kind: 'WEEKLY',
    repeat_every: 1,
    repeat_days_of_week: 'MONDAY,WEDNESDAY',
    repeat_end_kind: 'AFTER_COUNT',
    repeat_end_count: 6,
  })
})

test('the sheet checks what it can before it asks the engine', async () => {
  const engine = createEngine({})
  await home(engine)
  await openNew()

  press('Add')
  expect(await within(sheet()).findByText('Give the event a title first.')).toBeInTheDocument()

  type('Title', 'Swim lesson')
  fireEvent.click(within(sheet()).getByRole('radio', { name: 'Weekly' }))
  press('Add')
  expect(await within(sheet()).findByText('Choose at least one day for a weekly repeat.')).toBeInTheDocument()

  fireEvent.click(within(sheet()).getByRole('radio', { name: 'Never' }))
  type('Starts', '18:00')
  type('Ends (optional)', '17:00')
  press('Add')
  expect(await within(sheet()).findByText('The end has to be after the start.')).toBeInTheDocument()

  expect(engine.writes).toEqual([])
})

test('a server refusal is shown verbatim, the sheet stays open, and nothing is claimed saved', async () => {
  const engine = createEngine({})
  engine.refusals['POST /api/events'] = {
    status: 400,
    body: { remind_minutes_before: ['7 is not a reminder lead time this engine offers.'] },
  }
  await home(engine)
  await openNew()
  type('Title', 'Dentist')
  press('Add')

  const alert = await within(sheet()).findByRole('alert')
  expect(alert).toHaveTextContent(/^Nothing was saved\./)
  expect(alert).toHaveTextContent('7 is not a reminder lead time this engine offers.')
  expect(sheet()).toBeInTheDocument()
  expect(engine.events).toHaveLength(0)
  // The button is usable again, so the person can fix it and try once more.
  expect(within(sheet()).getByRole('button', { name: 'Add' })).toBeEnabled()
})

test('a reminder and "Only me" go to the engine and come back into the form', async () => {
  const engine = createEngine({})
  await home(engine)
  await openNew()
  type('Title', 'Therapy')
  type('Reminder', '30')
  fireEvent.click(within(sheet()).getByRole('radio', { name: 'Only me' }))
  press('Add')
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(writesTo(engine, 'POST', '/api/events')[0].body).toMatchObject({
    remind_minutes_before: 30,
    visibility: 'private',
  })

  const row = (await within(onToday()).findByText('Therapy')).closest('li') as HTMLElement
  expect(within(row).getByText('Only you')).toBeInTheDocument()
  fireEvent.click(within(row).getByRole('button', { name: 'Edit Therapy' }))
  await screen.findByRole('dialog', { name: 'Edit event' })
  expect(within(sheet()).getByLabelText('Reminder')).toHaveValue('30')
  expect(within(sheet()).getByRole('radio', { name: 'Only me' })).toBeChecked()
})

test('editing a one-off event PATCHes it, and sends visibility only when it changed', async () => {
  const dentist = makeEvent({ title: 'Dentist', starts_at: todayAt(15, 15) })
  const engine = createEngine({ events: [dentist] })
  await home(engine)
  fireEvent.click(within(onToday()).getByRole('button', { name: 'Edit Dentist' }))
  await screen.findByRole('dialog', { name: 'Edit event' })
  type('Title', 'Dentist, Mia')
  press('Save')
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

  const [patch] = writesTo(engine, 'PATCH', `/api/events/${dentist.id}`)
  expect(patch.body).toMatchObject({ title: 'Dentist, Mia' })
  expect(patch.body).not.toHaveProperty('visibility')
  expect(engine.events[0].title).toBe('Dentist, Mia')
})

test('"Just this one" on an edit sends the skip, then the one-off keyed by series and date', async () => {
  const series = daily()
  const engine = createEngine({ events: [series] })
  await home(engine)
  await openSeries()
  type('Title', 'Water the ferns (misting)')
  press('Save')

  // A repeating series asks which, and nothing is written until it is answered.
  const which = await within(sheet()).findByRole('group', { name: 'Which events' })
  expect(engine.writes).toEqual([])
  fireEvent.click(within(which).getByRole('button', { name: 'Just this one' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

  expect(engine.writes.map((w) => `${w.method} ${w.pathname}`)).toEqual([
    `POST /api/events/${series.id}/skips`,
    'POST /api/events',
  ])
  expect(engine.writes[0].body).toEqual({ skip_date: todayKey() })
  expect(engine.writes[1].body).toMatchObject({
    title: 'Water the ferns (misting)',
    origin_guid: `${series.id}:${todayKey()}`,
    repeat_kind: null,
    repeat_every: null,
    repeat_end_kind: null,
    remind_minutes_before: 15,
    kind: 'event',
  })
  // The series is untouched; today is the one-off now.
  expect(engine.events.find((e) => e.id === series.id)?.title).toBe('Water the ferns')
  expect(within(onToday()).getByText('Water the ferns (misting)')).toBeInTheDocument()
  expect(within(onToday()).queryByText('Water the ferns')).not.toBeInTheDocument()
})

test('a retry after the one-off failed does not duplicate the skip or the one-off, and says what did happen', async () => {
  const series = daily()
  const engine = createEngine({ events: [series] })
  await home(engine)
  await openSeries()
  type('Title', 'Water the ferns (misting)')

  engine.refusals['POST /api/events'] = { status: 400, body: { detail: 'The engine was busy.' } }
  press('Save')
  fireEvent.click(await within(sheet()).findByRole('button', { name: 'Just this one' }))
  const alert = await within(sheet()).findByRole('alert')
  // True: the occurrence is out of the series. Not "nothing was saved".
  expect(alert).toHaveTextContent('was taken out of the repeating series, but the changed copy was not saved')
  expect(alert).toHaveTextContent('The engine was busy.')
  expect(alert).not.toHaveTextContent(/Nothing was saved/)
  expect(engine.skips).toHaveLength(1)

  delete engine.refusals['POST /api/events']
  press('Save')
  fireEvent.click(await within(sheet()).findByRole('button', { name: 'Just this one' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

  expect(engine.skips.filter((s) => s.deleted_at === null)).toHaveLength(1)
  expect(engine.events.filter((e) => e.origin_guid === `${series.id}:${todayKey()}`)).toHaveLength(1)
})

test('"All of them" on an edit PATCHes the series', async () => {
  const series = daily()
  const engine = createEngine({ events: [series] })
  await home(engine)
  await openSeries()
  type('Title', 'Water all the ferns')
  type('Starts', '08:00')
  type('Ends (optional)', '08:30')
  press('Save')
  fireEvent.click(await within(sheet()).findByRole('button', { name: 'All of them' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

  expect(engine.writes.map((w) => `${w.method} ${w.pathname}`)).toEqual([`PATCH /api/events/${series.id}`])
  const body = engine.writes[0].body as Record<string, unknown>
  expect(body).toMatchObject({ title: 'Water all the ferns', repeat_kind: 'DAILY', repeat_every: 1 })
  // The series keeps its own start date: the date field was the occurrence's.
  const startsAt = new Date(body.starts_at as string)
  expect([startsAt.getHours(), startsAt.getMinutes()]).toEqual([8, 0])
  expect(startsAt.getDate()).toBe(new Date(series.starts_at as string).getDate())
})

test('"Just this one" on a delete sends only the skip', async () => {
  const series = daily()
  const engine = createEngine({ events: [series] })
  await home(engine)
  await openSeries()
  press('Delete')
  fireEvent.click(await within(sheet()).findByRole('button', { name: 'Just this one' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

  expect(engine.writes).toEqual([
    { method: 'POST', pathname: `/api/events/${series.id}/skips`, body: { skip_date: todayKey() } },
  ])
  expect(engine.events[0].deleted_at).toBeNull()
  await waitFor(() => expect(within(onToday()).queryByText('Water the ferns')).not.toBeInTheDocument())
})

test('"All of them" on a delete deletes the series', async () => {
  const series = daily()
  const engine = createEngine({ events: [series] })
  await home(engine)
  await openSeries()
  press('Delete')
  fireEvent.click(await within(sheet()).findByRole('button', { name: 'All of them' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

  expect(engine.writes.map((w) => `${w.method} ${w.pathname}`)).toEqual([`DELETE /api/events/${series.id}`])
  expect(engine.events[0].deleted_at).not.toBeNull()
})

test('deleting a one-off asks first, and says what leaves', async () => {
  const dentist = makeEvent({ title: 'Dentist', starts_at: todayAt(15, 15) })
  const engine = createEngine({ events: [dentist] })
  await home(engine)
  fireEvent.click(within(onToday()).getByRole('button', { name: 'Edit Dentist' }))
  await screen.findByRole('dialog', { name: 'Edit event' })
  press('Delete')
  const confirm = await within(sheet()).findByRole('group', { name: 'Confirm delete' })
  expect(confirm).toHaveTextContent('It leaves the calendar for everyone who sees it.')
  expect(engine.writes).toEqual([])
  fireEvent.click(within(confirm).getByRole('button', { name: 'Keep it' }))
  expect(engine.writes).toEqual([])
  press('Delete')
  fireEvent.click(await within(sheet()).findByRole('button', { name: 'Delete event' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(engine.writes.map((w) => w.method)).toEqual(['DELETE'])
})

test('a Canvas row is not offered for editing: the poller owns it', async () => {
  const canvas = makeEvent({
    title: 'COSC 4320 · HW 4 due',
    kind: 'task',
    starts_at: todayAt(23, 59),
    visibility: 'private',
    origin_guid: 'canvas:123',
    structured_meta: { canvas_assignment_id: 123 },
  })
  const engine = createEngine({ events: [canvas] })
  await home(engine)
  expect(within(onToday()).queryByRole('button', { name: /^Edit / })).not.toBeInTheDocument()
  expect(within(onToday()).getByRole('checkbox', { name: /Mark "HW 4 due"/ })).toBeInTheDocument()
})
