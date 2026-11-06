import { render, screen, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'

import { CAR_CARD, DAY_CARD, DIAGRAM_CARD, LIST_CARD, RECALLS_HALF, SERVICE_HALF, SPEND_CARD, STEPS_CARD, WEEK_CARD } from '@/test/cards-seed'

import { ToolCard } from './card'
import { cleanSvg } from './diagram'
import { asCard, centsWords, clockWords, dayWords, mergeCar } from './types'

const region = (name: string) => screen.getByRole('region', { name })

describe('tool cards', () => {
  test('bars: values from cents, a flag in words beside its value, how many are not shown', () => {
    render(<ToolCard card={SPEND_CARD} />)
    const card = region('Spending by category')
    expect(within(card).getByText('October 2026')).toBeInTheDocument()
    const rows = within(card).getAllByRole('listitem')
    expect(rows).toHaveLength(4)
    expect(rows[0]).toHaveTextContent('Housing$1,725.00')
    expect(rows[1]).toHaveTextContent('Groceries$412.36unverified')
    expect(rows[2]).not.toHaveTextContent('unverified')
    expect(within(card).getByText('2 more not shown.')).toBeInTheDocument()
    expect(within(card).getByText('Total $2,516.81. Includes $12.40 unverified.')).toBeInTheDocument()
  })

  test('timeline: all day first, clock times in words, the zone said, kinds in words', () => {
    render(<ToolCard card={DAY_CARD} />)
    const card = region("What's on")
    expect(within(card).getByText('Wed Nov 4, 2026, times in UTC-06:00')).toBeInTheDocument()
    const rows = within(card).getAllByRole('listitem')
    expect(rows.map((row) => row.textContent)).toEqual([
      'All dayBin day',
      '9:00 amFarmers market (suggestion, not a plan)',
      '3:30 pmto 4:15 pmDentist',
      '6:00 pmCall the plumber (to do)',
    ])
    expect(within(card).getByText('3 more not shown.')).toBeInTheDocument()
  })

  test('car: mileage on file, next service marked an estimate, codes with meanings, recalls', () => {
    render(<ToolCard card={CAR_CARD} />)
    const card = region('Cherokee')
    expect(within(card).getByText('120,400 mi, on file Fri Oct 30, 2026')).toBeInTheDocument()
    expect(card).toHaveTextContent('Oil change, due Tue Dec 1, 2026, at 121,000 mi')
    expect(within(card).getByText('estimate')).toBeInTheDocument()
    expect(card).toHaveTextContent('P0420 Catalyst System Efficiency Below Threshold (Bank 1)')
    expect(within(card).getByText('Air bags: The passenger air bag may not deploy.')).toBeInTheDocument()
  })

  test('car: nothing on file is said, not left blank', () => {
    render(<ToolCard card={{ ...CAR_CARD, mileage: null, next_service: null, codes: [], recalls: [], note: 'Recalls were not looked up, so whether there are any is not known.' }} />)
    const card = region('Cherokee')
    expect(within(card).getByText('No reading on file')).toBeInTheDocument()
    expect(within(card).getByText('Nothing timed from the records on file')).toBeInTheDocument()
    expect(within(card).getByText(/whether there are any is not known/)).toBeInTheDocument()
  })

  test('steps: status in words and milliseconds, a skipped step has no time', () => {
    render(<ToolCard card={STEPS_CARD} />)
    const card = region('Money check')
    const rows = within(card).getAllByRole('listitem')
    expect(rows.map((row) => row.textContent)).toEqual(['Spenddone42 ms', 'Groceriesdid not finish7 ms', 'Answernot run'])
    expect(within(card).getByText('Total 49 ms')).toBeInTheDocument()
    expect(within(card).getByText('Stopped part way.')).toBeInTheDocument()
  })

  test('checklist: ticked or not in words, for a screen reader too', () => {
    render(<ToolCard card={LIST_CARD} />)
    const card = region('Groceries')
    const rows = within(card).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Milk, ticked')
    expect(rows[1]).toHaveTextContent('Eggs, not ticked')
    expect(within(card).getByText('1 other list not shown.')).toBeInTheDocument()
  })
})

describe('cards over several days, and a car in parts', () => {
  test('timeline: a window of days puts each item under its own day', () => {
    render(<ToolCard card={WEEK_CARD} />)
    const card = region("What's on")
    expect(within(card).getByText('Wed Nov 4 to Wed Nov 11')).toBeInTheDocument()
    const days = within(card).getAllByRole('region')
    expect(days.map((day) => day.getAttribute('aria-label'))).toEqual([
      'Wed Nov 4, 2026',
      'Thu Nov 5, 2026',
      'Sat Nov 7, 2026',
    ])
    expect(within(days[0]).getAllByRole('listitem').map((row) => row.textContent)).toEqual([
      'All dayLibrary books due (to do)',
      '7:00 pmto 9:00 pmDinner with the Okafors',
    ])
  })

  test('car: a card from one call draws only the parts that call read', () => {
    render(<ToolCard card={SERVICE_HALF} />)
    const card = region('Wagon')
    expect(within(card).getByText('Mileage')).toBeInTheDocument()
    expect(within(card).queryByText('Recalls')).not.toBeInTheDocument()
    expect(within(card).queryByText('Codes')).not.toBeInTheDocument()
  })

  test('mergeCar: next_service then vehicle_recalls make one car, each part from the call that read it', () => {
    const merged = mergeCar(SERVICE_HALF, RECALLS_HALF)
    expect(merged.mileage).toBe(48_210)
    expect(merged.next_service?.label).toBe('Oil change')
    expect(merged.recalls).toEqual(RECALLS_HALF.recalls)
    expect(Object.keys(merged.parts ?? {})).toEqual(['service', 'recalls'])
    expect(merged.note).toBe(
      "Next service is an estimate from the records on file. Recalls are NHTSA's list for the model, not a check of this car's VIN.",
    )
    // A full card (car_check) replaces whatever came before it.
    expect(mergeCar(SERVICE_HALF, CAR_CARD)).toBe(CAR_CARD)
  })
})

describe('the card shape', () => {
  test('asCard keeps the five kinds and draws nothing for anything else', () => {
    for (const card of [SPEND_CARD, DAY_CARD, CAR_CARD, STEPS_CARD, LIST_CARD, DIAGRAM_CARD]) expect(asCard(card)).toBe(card)
    expect(asCard({ ...DIAGRAM_CARD, svg: '<div>not a picture</div>' })).toBeUndefined()
    expect(asCard({ ...DIAGRAM_CARD, highlight: [1] })).toBeUndefined()
    expect(asCard(undefined)).toBeUndefined()
    expect(asCard({ card: 'pie', title: 'x' })).toBeUndefined()
    expect(asCard({ card: 'bars', title: 'x', points: [{ label: 'a', value: 1.5 }] })).toBeUndefined()
    expect(asCard({ ...SPEND_CARD, title: undefined })).toBeUndefined()
  })

  test('words', () => {
    expect(centsWords(123_456)).toBe('$1,234.56')
    expect(centsWords(-5)).toBe('-$0.05')
    expect(clockWords('00:05')).toBe('12:05 am')
    expect(clockWords('12:00')).toBe('12:00 pm')
    expect(dayWords('2026-11-04')).toBe('Wed Nov 4, 2026')
  })
})

describe('diagram card', () => {
  test('draws the SVG, outlines the highlighted parts, and says them and the caption in words', () => {
    render(<ToolCard card={DIAGRAM_CARD} />)
    const card = region('Engine components, in layers')
    expect(within(card).getByText('LEGION')).toBeInTheDocument()
    expect(within(card).getByText(DIAGRAM_CARD.caption)).toBeInTheDocument()
    expect(within(card).getByText('Highlighted: /api/assistant/tool, household.tenancy')).toBeInTheDocument()
    const nodes = card.querySelectorAll('[data-diagram="inline"] [data-node]')
    expect(nodes).toHaveLength(3)
    const lit = Array.from(nodes).filter((n) => n.classList.contains('dg-hl')).map((n) => n.getAttribute('data-node'))
    expect(lit).toEqual(['tooldoor', 'household'])
  })

  test('nothing in the SVG can run: scripts and handlers are stripped', () => {
    render(<ToolCard card={DIAGRAM_CARD} />)
    const card = region('Engine components, in layers')
    expect(card.querySelector('script')).toBeNull()
    expect(card.querySelector('[onclick]')).toBeNull()
    expect(cleanSvg('<div>nope</div>')).toBeNull()
  })

  test('no highlight says nothing about one', () => {
    render(<ToolCard card={{ ...DIAGRAM_CARD, highlight: [] }} />)
    const card = region('Engine components, in layers')
    expect(within(card).queryByText(/Highlighted/)).toBeNull()
    expect(card.querySelectorAll('.dg-hl')).toHaveLength(0)
  })

  test('with no modal on the page the thumbnail opens nothing', () => {
    render(<ToolCard card={DIAGRAM_CARD} />)
    expect(screen.getByRole('button', { name: /Open the diagram over the page/ })).toBeDisabled()
  })
})
