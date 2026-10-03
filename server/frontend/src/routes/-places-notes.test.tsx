import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { seedAspects } from '@/test/aspects-seed'
import { createEngine } from '@/test/engine'
import { renderApp } from '@/test/render-app'

afterEach(() => {
  vi.unstubAllGlobals()
})

const engineWith = (tables = seedAspects()) => createEngine({ tables })

describe('/places', () => {
  test('at family width is the bigger-screen card and reads nothing', async () => {
    const engine = engineWith()
    renderApp('/places', engine, 'family')
    expect(await screen.findByText('This page is made for a bigger screen.')).toBeInTheDocument()
    expect(engine.calls['GET /api/places/']).toBeUndefined()
  })

  test('lists the places with their coordinates', async () => {
    const engine = engineWith()
    renderApp('/places', engine)
    const home = (await screen.findByRole('cell', { name: 'Home' })).closest('tr') as HTMLElement
    expect(within(home).getByText('29.76040')).toBeInTheDocument()
    expect(within(home).getByText('-95.36980')).toBeInTheDocument()
    expect(engine.unhandled).toEqual([])
  })

  test('round-trips: add keys the place by its label, edit moves it, delete removes it', async () => {
    const engine = engineWith()
    renderApp('/places', engine)
    await screen.findByRole('cell', { name: 'Home' })

    fireEvent.click(screen.getByRole('button', { name: /Add a place/ }))
    let dialog = await screen.findByRole('dialog', { name: 'Add a place' })
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Cafe' } })
    fireEvent.change(within(dialog).getByLabelText('Latitude'), { target: { value: '29.75' } })
    fireEvent.change(within(dialog).getByLabelText('Longitude'), { target: { value: '-95.36' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(engine.calls['PUT /api/places/Cafe/']).toBe(1)
    const stored = engine.tables.places.find((row) => row.label === 'Cafe')
    expect(stored).toMatchObject({ latitude: 29.75, longitude: -95.36 })
    expect(await screen.findByRole('cell', { name: 'Cafe' })).toBeInTheDocument()

    // The name is the key: shown, not editable, and the form says why.
    fireEvent.click(screen.getByRole('button', { name: 'Edit "Cafe"' }))
    dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Name')).toBeDisabled()
    expect(within(dialog).getByText(/A place's name is its key and cannot be changed here\./)).toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('Latitude'), { target: { value: '29.8' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(stored).toMatchObject({ latitude: 29.8, label: 'Cafe' }))

    fireEvent.click(await screen.findByRole('button', { name: 'Delete "Cafe"' }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(stored?.deleted_at).toBeTruthy())
    await waitFor(() => expect(screen.queryByRole('cell', { name: 'Cafe' })).not.toBeInTheDocument())
  })

  test('an out-of-range coordinate is refused by the engine and its sentence is shown', async () => {
    const engine = engineWith()
    engine.refusals['PUT /api/places/*'] = {
      status: 400,
      body: { latitude: ['latitude must be between -90 and 90 (got 200).'] },
    }
    renderApp('/places', engine)
    fireEvent.click(await screen.findByRole('button', { name: /Add a place/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Nowhere' } })
    fireEvent.change(within(dialog).getByLabelText('Latitude'), { target: { value: '200' } })
    fireEvent.change(within(dialog).getByLabelText('Longitude'), { target: { value: '0' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Nothing was saved. latitude: latitude must be between -90 and 90 (got 200).',
    )
    expect(engine.tables.places).toHaveLength(4)
  })

  test('says so in a sentence when there are none, and when the engine could not be reached', async () => {
    const first = renderApp('/places', engineWith({}))
    expect(await screen.findByText(/No places yet\. Tag one from the phone/)).toBeInTheDocument()
    first.unmount()

    const failing = engineWith()
    failing.failingTables.add('places')
    renderApp('/places', failing)
    expect(await screen.findByText(/Could not reach the engine, so this is not the real list of places\./)).toBeInTheDocument()
    expect(screen.queryByText(/No places yet/)).not.toBeInTheDocument()
  })
})

describe('/notes', () => {
  test('at family width is the bigger-screen card and reads nothing', async () => {
    const engine = engineWith()
    renderApp('/notes', engine, 'family')
    expect(await screen.findByText('This page is made for a bigger screen.')).toBeInTheDocument()
    expect(engine.calls['GET /api/voice_notes/']).toBeUndefined()
  })

  test('shows the newest note with its summary and transcript, and says audio is not available', async () => {
    renderApp('/notes', engineWith())
    const note = await screen.findByRole('article', { name: 'Kitchen plan with Mia' })
    expect(within(note).getByText('Audio is not available on the web.')).toBeInTheDocument()
    expect(within(note).getByText(/Agreed to order the cabinets this month/)).toBeInTheDocument()
    expect(within(note).getByText(/Written by a model from the transcript, so it can be wrong\./)).toBeInTheDocument()
    expect(within(note).getByText(/Okay, so the cabinets first\./)).toBeInTheDocument()
    expect(within(note).getByText(/^Meeting, .*, 24 min$/)).toBeInTheDocument()
  })

  test('the audio sentence is on every note, not only the first', async () => {
    renderApp('/notes', engineWith())
    await screen.findByRole('article', { name: 'Kitchen plan with Mia' })
    fireEvent.click(screen.getByRole('button', { name: /Idea for the garage shelves/ }))
    const note = await screen.findByRole('article', { name: 'Idea for the garage shelves' })
    expect(within(note).getByText('Audio is not available on the web.')).toBeInTheDocument()
  })

  test('an interrupted, untitled note with no summary says each of those in words', async () => {
    renderApp('/notes', engineWith())
    fireEvent.click(await screen.findByRole('button', { name: /Untitled recording/ }))
    const note = await screen.findByRole('article', { name: 'Untitled recording' })
    expect(within(note).getByText(/This recording was interrupted, so the transcript and summary may be incomplete\./)).toBeInTheDocument()
    expect(within(note).getByText('No summary was written for this note.')).toBeInTheDocument()
  })

  test('is read only: nothing to edit or delete', async () => {
    renderApp('/notes', engineWith())
    await screen.findByRole('article', { name: 'Kitchen plan with Mia' })
    expect(screen.queryByRole('button', { name: /Delete|Edit/ })).not.toBeInTheDocument()
  })

  test('empty and could-not-reach are different sentences', async () => {
    const first = renderApp('/notes', engineWith({}))
    expect(await screen.findByText(/No voice notes yet\. A note recorded on the phone appears here as text/)).toBeInTheDocument()
    first.unmount()

    const failing = engineWith()
    failing.failingTables.add('voice_notes')
    renderApp('/notes', failing)
    expect(await screen.findByText(/Could not reach the engine, so this is not the real list of voice notes\./)).toBeInTheDocument()
    expect(screen.queryByText(/No voice notes yet/)).not.toBeInTheDocument()
  })
})
