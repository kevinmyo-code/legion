import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { expect, test } from 'vitest'

import type { Event } from '@/api/types'
import { CHANGES_KEY } from '@/api/queries'
import { createEngine, makeChecklist, makeEvent, todayAt } from '@/test/engine'
import { renderApp } from '@/test/render-app'

/**
 * Shared and private, on the web (web-revamp 07). Whether a row is private is
 * the engine's fact (ADR 0052); what the web owes is to SAY it, in words, on
 * every row and list, to let the right person flip it, to show the engine's
 * refusal verbatim, and to drop a row the engine redacted.
 */

const WHO_MAY_SAY = 'Only the person who added this can make it private.'

function onToday(): HTMLElement {
  return screen.getByRole('heading', { name: 'On today' }).closest('section') as HTMLElement
}

test('an event row says Shared or Only you in words', async () => {
  const engine = createEngine({
    events: [
      makeEvent({ title: 'Dentist', starts_at: todayAt(15, 15) }),
      makeEvent({ title: 'Therapy', starts_at: todayAt(16, 30), visibility: 'private' }),
    ],
  })
  renderApp('/', engine, 'family')
  await screen.findByRole('heading', { name: 'On today' })

  const dentist = within(onToday()).getByText('Dentist').closest('li') as HTMLElement
  const therapy = within(onToday()).getByText('Therapy').closest('li') as HTMLElement
  expect(within(dentist).getByText('Shared')).toBeInTheDocument()
  expect(within(dentist).queryByText('Only you')).not.toBeInTheDocument()
  expect(within(therapy).getByText('Only you')).toBeInTheDocument()
  expect(within(therapy).queryByText('Shared')).not.toBeInTheDocument()
})

test('a row from an engine that predates the field is shared, and says so', async () => {
  const bare = makeEvent({ title: 'Farmers market', starts_at: todayAt(9, 0) }) as Partial<Event>
  delete bare.visibility
  const engine = createEngine({ events: [bare as Event] })
  renderApp('/', engine, 'family')
  await screen.findByRole('heading', { name: 'On today' })
  const row = within(onToday()).getByText('Farmers market').closest('li') as HTMLElement
  expect(within(row).getByText('Shared')).toBeInTheDocument()
})

test('a list header carries the mark, and pressing it PATCHes visibility', async () => {
  const groceries = makeChecklist({ name: 'Groceries' })
  const engine = createEngine({ checklists: [groceries] })
  renderApp('/lists', engine, 'family')

  const toggle = await screen.findByRole('button', { name: /"Groceries" is shared with the household/ })
  expect(within(toggle).getByText('Shared')).toBeInTheDocument()
  fireEvent.click(toggle)

  const flipped = await screen.findByRole('button', { name: /"Groceries" is only yours/ })
  expect(within(flipped).getByText('Only you')).toBeInTheDocument()
  expect(engine.writes).toContainEqual({
    method: 'PATCH',
    pathname: `/api/checklists/${groceries.id}`,
    body: { visibility: 'private' },
  })

  // And back: the owner may always share what is theirs.
  fireEvent.click(flipped)
  await screen.findByRole('button', { name: /"Groceries" is shared with the household/ })
  expect(engine.writes.at(-1)?.body).toEqual({ visibility: 'shared' })
})

test('the engine refusing to make a list private shows its sentence verbatim, and nothing changes', async () => {
  const groceries = makeChecklist({ name: 'Groceries' })
  const engine = createEngine({ checklists: [groceries] })
  engine.refusals['PATCH /api/checklists/*'] = { status: 403, body: { detail: WHO_MAY_SAY } }
  renderApp('/lists', engine, 'family')

  fireEvent.click(await screen.findByRole('button', { name: /"Groceries" is shared with the household/ }))
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent(WHO_MAY_SAY)
  expect(alert).toHaveTextContent(/^Nothing was saved\./)
  // The list is still shared: the mark did not flip and flip back.
  expect(screen.getByRole('button', { name: /"Groceries" is shared with the household/ })).toBeInTheDocument()
})

test('a redacted tombstone removes the row it replaces, and one on first load shows nothing', async () => {
  const dentist = makeEvent({ title: 'Dentist', starts_at: todayAt(15, 15) })
  const hidden = { id: 'never-seen', deleted_at: '2026-10-03T00:00:00Z', updated_at: '2026-10-03T00:00:00Z', redacted: true }
  const engine = createEngine({ events: [dentist, hidden as unknown as Event] })
  const { queryClient } = renderApp('/', engine, 'family')
  await screen.findByRole('heading', { name: 'On today' })
  expect(within(onToday()).getByText('Dentist')).toBeInTheDocument()

  // The member who owns the row makes it private: to everyone else the next pull
  // carries only `{id, deleted_at, updated_at, redacted: true}` in its place.
  engine.events[0] = {
    id: dentist.id,
    deleted_at: '2026-10-03T12:00:00Z',
    updated_at: '2026-10-03T12:00:00Z',
    redacted: true,
  } as unknown as (typeof engine.events)[number]
  await queryClient.invalidateQueries({ queryKey: CHANGES_KEY })

  await waitFor(() => expect(within(onToday()).queryByText('Dentist')).not.toBeInTheDocument())
  expect(within(onToday()).getByText('Nothing on the calendar today.')).toBeInTheDocument()
})

test('a redacted list tombstone drops the list', async () => {
  const groceries = makeChecklist({ name: 'Groceries' })
  const engine = createEngine({ checklists: [groceries] })
  const { queryClient } = renderApp('/lists', engine, 'family')
  await screen.findByRole('heading', { name: 'Groceries' })

  engine.checklists[0] = {
    id: groceries.id,
    deleted_at: '2026-10-03T12:00:00Z',
    updated_at: '2026-10-03T12:00:00Z',
    redacted: true,
  } as unknown as (typeof engine.checklists)[number]
  await queryClient.invalidateQueries({ queryKey: CHANGES_KEY })

  await waitFor(() => expect(screen.queryByRole('heading', { name: 'Groceries' })).not.toBeInTheDocument())
  expect(screen.getByText('No lists yet. Start one below.')).toBeInTheDocument()
})
