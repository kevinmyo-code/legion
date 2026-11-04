import { render, screen, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'

import { CAR_CARD, DAY_CARD, LIST_CARD, SPEND_CARD, STEPS_CARD } from '@/test/cards-seed'

import { ToolCard } from './card'
import { asCard, centsWords, clockWords, dayWords } from './types'

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
      '3:30 pm to 4:15 pmDentist',
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

describe('the card shape', () => {
  test('asCard keeps the five kinds and draws nothing for anything else', () => {
    for (const card of [SPEND_CARD, DAY_CARD, CAR_CARD, STEPS_CARD, LIST_CARD]) expect(asCard(card)).toBe(card)
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
