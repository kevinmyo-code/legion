import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { seedAspects } from '@/test/aspects-seed'
import { createEngine } from '@/test/engine'
import { renderApp } from '@/test/render-app'

/**
 * `/fleet`. What is writable follows the engine's schema: service history and
 * maintenance schedules are full CRUD, a vehicle is edit-only and only when it
 * has a key, drives are read only, and `obd_samples` is never asked for.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

const engineWith = (tables = seedAspects()) => createEngine({ tables })

describe('at family width', () => {
  test('is the bigger-screen card and reads nothing', async () => {
    const engine = engineWith()
    renderApp('/fleet', engine, 'family')
    expect(await screen.findByText('This page is made for a bigger screen.')).toBeInTheDocument()
    expect(Object.keys(engine.calls).filter((key) => key.includes('/api/fleet'))).toEqual([])
  })
})

describe('at workbench width', () => {
  test('shows the first vehicle with its service, schedule and drives, and never asks for OBD samples', async () => {
    const engine = engineWith()
    renderApp('/fleet', engine)

    expect(await screen.findByRole('heading', { name: 'Daily' })).toBeInTheDocument()
    expect(screen.getByText('2018 Honda Civic EX')).toBeInTheDocument()
    // In the history and in the schedule.
    expect(await screen.findAllByRole('cell', { name: 'Brake fluid flush' })).toHaveLength(2)
    expect(await screen.findByText('14.2')).toBeInTheDocument()
    expect(Object.keys(engine.calls).some((key) => key.includes('obd_samples'))).toBe(false)
    expect(engine.unhandled).toEqual([])
  })

  test('says whether a service was observed or asserted, in words', async () => {
    renderApp('/fleet', engineWith())
    const service = await waitForSection('Service history')
    const oil = (await within(service).findByRole('cell', { name: 'Oil change' })).closest('tr') as HTMLElement
    expect(within(oil).getByText('Observed')).toBeInTheDocument()
    const rotation = within(service).getByRole('cell', { name: 'Tire rotation' }).closest('tr') as HTMLElement
    expect(within(rotation).getByText('Asserted, not checked')).toBeInTheDocument()
    expect(within(rotation).getByText('No cost recorded')).toBeInTheDocument()
  })

  test('a seeded maintenance interval is called a guess and an estimate; a confirmed one is not', async () => {
    renderApp('/fleet', engineWith())
    const schedule = await waitForSection('Maintenance schedule')
    const rotation = (await within(schedule).findByRole('cell', { name: 'Tire rotation' })).closest('tr') as HTMLElement
    expect(within(rotation).getByText('Every 7,500 miles')).toBeInTheDocument()
    expect(within(rotation).getByText('estimate')).toBeInTheDocument()
    expect(within(rotation).getByText("LEGION's guess")).toBeInTheDocument()

    const oil = within(schedule).getByRole('cell', { name: 'Oil change' }).closest('tr') as HTMLElement
    expect(within(oil).queryByText('estimate')).not.toBeInTheDocument()
    expect(within(oil).getByText('Confirmed by you')).toBeInTheDocument()
    // Matched by name against the vehicle's own history, never inferred.
    expect(within(oil).getByText(/Aug|Sep|Oct|Jul/)).toBeInTheDocument()
    const cabin = within(schedule).getByRole('cell', { name: 'Cabin air filter' }).closest('tr') as HTMLElement
    expect(within(cabin).getByText('Marked never done')).toBeInTheDocument()
  })

  test('a drive with no fuel reading says so and is not zero; drives have no edit controls', async () => {
    renderApp('/fleet', engineWith())
    const drives = await waitForSection('Recent drives')
    expect(await within(drives).findByText('No fuel reading')).toBeInTheDocument()
    expect(within(drives).queryByText(/0 gal/)).not.toBeInTheDocument()
    expect(within(drives).queryByRole('button', { name: /Edit|Delete/ })).not.toBeInTheDocument()
    expect(within(drives).getByText('Dongle link lost')).toBeInTheDocument()
  })

  test('a vehicle with no key on the engine says it cannot be edited and offers no edit button', async () => {
    renderApp('/fleet', engineWith())
    expect(await screen.findByRole('button', { name: 'Edit vehicle' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Project' }))
    expect(await screen.findByRole('heading', { name: 'Project' })).toBeInTheDocument()
    expect(screen.getByText('This vehicle has no key on the engine yet, so it cannot be edited here.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit vehicle' })).not.toBeInTheDocument()
    // A vehicle with nothing recorded says so for each panel.
    expect(await screen.findAllByText(/No service recorded for this vehicle yet\./)).not.toHaveLength(0)
  })

  test('editing a vehicle sends the whole row back, so fields the form does not show are not cleared', async () => {
    const engine = engineWith()
    const civic = engine.tables['fleet/vehicles'].find((row) => row.name === 'Daily')!
    civic.last_obd_mac = 'AA:BB:CC:DD:EE:FF'
    renderApp('/fleet', engine)

    fireEvent.click(await screen.findByRole('button', { name: 'Edit vehicle' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit Daily' })
    fireEvent.change(within(dialog).getByLabelText('Trim (optional)'), { target: { value: 'Sport' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(civic.trim).toBe('Sport'))
    expect(civic).toMatchObject({ last_obd_mac: 'AA:BB:CC:DD:EE:FF', confirmed: true, archived: false })
  })

  test('an odometer reading without its moment is refused before the engine is asked', async () => {
    const engine = engineWith()
    renderApp('/fleet', engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Edit vehicle' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Odometer read on (optional)'), { target: { value: '' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Nothing was saved. An odometer reading needs the moment it was read',
    )
    expect(engine.calls['PUT /api/fleet/vehicles/']).toBeUndefined()
  })

  test('service history round-trips; a typed entry is asserted and cost is exact cents', async () => {
    const engine = engineWith()
    renderApp('/fleet', engine)
    const service = () => screen.getByRole('heading', { name: 'Service history' }).closest('section') as HTMLElement

    fireEvent.click(await within(await waitForSection('Service history')).findByRole('button', { name: /Add a service/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Service'), { target: { value: 'Wipers' } })
    fireEvent.change(within(dialog).getByLabelText('Cost, dollars (optional)'), { target: { value: '24.50' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const stored = engine.tables['fleet/service_history'].find((row) => row.service_name === 'Wipers')
    const civic = engine.tables['fleet/vehicles'].find((row) => row.name === 'Daily')!
    expect(stored).toMatchObject({ cost_cents: 2450, kind: 'ASSERTED', vehicle_id: civic.id })
    expect(await within(service()).findByRole('cell', { name: 'Wipers' })).toBeInTheDocument()
    expect(within(service()).getByText('$24.50')).toBeInTheDocument()

    fireEvent.click(within(service()).getByRole('button', { name: /Edit Wipers/ }))
    const edit = await screen.findByRole('dialog')
    fireEvent.change(within(edit).getByLabelText('Cost, dollars (optional)'), { target: { value: '19.99' } })
    fireEvent.click(within(edit).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(stored).toMatchObject({ cost_cents: 1999 }))

    fireEvent.click(within(service()).getByRole('button', { name: /Delete Wipers/ }))
    fireEvent.click(within(service()).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(stored?.deleted_at).toBeTruthy())
    await waitFor(() => expect(within(service()).queryByRole('cell', { name: 'Wipers' })).not.toBeInTheDocument())
  })

  test('a cost that is not an amount is refused with what did not happen', async () => {
    const engine = engineWith()
    renderApp('/fleet', engine)
    fireEvent.click(await within(await waitForSection('Service history')).findByRole('button', { name: /Add a service/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Service'), { target: { value: 'Wipers' } })
    fireEvent.change(within(dialog).getByLabelText('Cost, dollars (optional)'), { target: { value: '24.505' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Nothing was saved. Cost must be an amount like 49.99')
    expect(engine.tables['fleet/service_history']).toHaveLength(4)
  })

  test('editing an observed service says it becomes asserted', async () => {
    renderApp('/fleet', engineWith())
    const service = await waitForSection('Service history')
    fireEvent.click(await within(service).findByRole('button', { name: /Edit Oil change/ }))
    expect(await screen.findByText(/LEGION observed this service\. Saving a change makes it asserted/)).toBeInTheDocument()
  })

  test('maintenance schedules use the two-part key, and saving one confirms its source', async () => {
    const engine = engineWith()
    renderApp('/fleet', engine)
    const schedule = await waitForSection('Maintenance schedule')
    fireEvent.click(await within(schedule).findByRole('button', { name: /Add a schedule/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Service'), { target: { value: 'Coolant flush' } })
    fireEvent.change(within(dialog).getByLabelText('Every, months (optional)'), { target: { value: '60' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const civic = engine.tables['fleet/vehicles'].find((row) => row.name === 'Daily')!
    expect(engine.calls[`PUT /api/fleet/maintenance_schedules/${civic.id}/Coolant%20flush/`]).toBe(1)
    const stored = engine.tables['fleet/maintenance_schedules'].find((row) => row.service_name === 'Coolant flush')
    expect(stored).toMatchObject({ interval_months: 60, interval_miles: null, interval_source: 'CONFIRMED' })

    // Editing a seeded one confirms it: the name is its key and is read only.
    fireEvent.click(within(schedule).getByRole('button', { name: /Edit the Tire rotation schedule/ }))
    const edit = await screen.findByRole('dialog')
    expect(within(edit).getByLabelText('Service')).toBeDisabled()
    fireEvent.change(within(edit).getByLabelText('Every, miles (optional)'), { target: { value: '6000' } })
    fireEvent.click(within(edit).getByRole('button', { name: 'Save' }))
    const rotation = engine.tables['fleet/maintenance_schedules'].find((row) => row.service_name === 'Tire rotation')
    await waitFor(() => expect(rotation).toMatchObject({ interval_miles: 6000, interval_source: 'CONFIRMED' }))
    expect(await within(schedule).findByText('Every 6,000 miles')).toBeInTheDocument()
  })

  test('a schedule with neither interval is refused by the engine and the sentence is shown', async () => {
    const engine = engineWith()
    engine.refusals['PUT /api/fleet/maintenance_schedules/*'] = {
      status: 400,
      body: { non_field_errors: ['A maintenance schedule needs at least one interval.'] },
    }
    renderApp('/fleet', engine)
    const schedule = await waitForSection('Maintenance schedule')
    fireEvent.click(await within(schedule).findByRole('button', { name: /Add a schedule/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Service'), { target: { value: 'Mystery' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Nothing was saved. non_field_errors: A maintenance schedule needs at least one interval.',
    )
  })

  test('could not reach, empty and stale are different sentences for the vehicle list', async () => {
    const failing = engineWith()
    failing.failingTables.add('fleet/vehicles')
    const first = renderApp('/fleet', failing)
    expect(await screen.findByText(/Could not reach the engine, so this is not the real list of vehicles\./)).toBeInTheDocument()
    expect(screen.queryByText(/No vehicles yet/)).not.toBeInTheDocument()
    first.unmount()

    renderApp('/fleet', engineWith({}))
    expect(await screen.findByText(/No vehicles yet\. They are added from the phone\./)).toBeInTheDocument()
  })
})

async function waitForSection(name: string): Promise<HTMLElement> {
  const heading = await screen.findByRole('heading', { name })
  return heading.closest('section') as HTMLElement
}
