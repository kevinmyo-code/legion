import { render, screen, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'

import rawFixtures from '../../../../../../shared/cards/fixtures.json'

import { ToolCard } from './card'
import {
  asCard,
  asOfWords,
  readingWords,
  seriesWords,
  STATUS_WORDS,
  type ReadingsCard,
  type StatusListCard,
  type TrendCard,
} from './types'

/**
 * The general kinds against `shared/cards/fixtures.json`, the file the engine's and the phone's
 * tests read too: each parses, and each says exactly the fixture's words.
 */
interface Fixture {
  card: Record<string, unknown>
  words: { values?: string[]; statuses?: string[]; series?: string[]; asOfStartsWith?: string; more?: string }
}

const fixtures = (rawFixtures as unknown as { cards: Fixture[] }).cards
const of = (kind: string) => fixtures.filter((f) => f.card.card === kind)

describe('the shared fixtures', () => {
  test('every one is a card this screen draws', () => {
    for (const f of fixtures) expect(asCard(f.card)).toBe(f.card)
  })

  test('readings say every value in the fixture words, unread as "not read"', () => {
    for (const f of of('readings')) {
      const card = asCard(f.card) as ReadingsCard
      expect(card.items.map(readingWords)).toEqual(f.words.values)
      expect(asOfWords(card.at)?.startsWith(f.words.asOfStartsWith!)).toBe(true)
      render(<ToolCard card={card} />)
      const region = screen.getByRole('region', { name: card.title })
      const values = Array.from(region.querySelectorAll('[data-card="value"]'))
      values.forEach((dd, i) => expect(dd.textContent?.startsWith(f.words.values![i])).toBe(true))
      expect(values).toHaveLength(f.words.values!.length)
      expect(within(region).getByText(f.words.more!)).toBeInTheDocument()
    }
  })

  test('status rows say the status in words', () => {
    for (const f of of('status_list')) {
      const card = asCard(f.card) as StatusListCard
      expect(card.items.map((i) => STATUS_WORDS[i.status])).toEqual(f.words.statuses)
      render(<ToolCard card={card} />)
      const rows = within(screen.getByRole('region', { name: card.title })).getAllByRole('listitem')
      rows.forEach((row, i) => expect(row).toHaveTextContent(f.words.statuses![i]))
    }
  })

  test('a trend says each series latest, low and high in words', () => {
    for (const f of of('trend')) {
      const card = asCard(f.card) as TrendCard
      expect(card.series.map((s) => seriesWords(s, card.unit))).toEqual(f.words.series)
    }
  })
})

describe('the general kinds on screen', () => {
  test('a trend draws its line as decoration and its words for everyone', () => {
    const card = asCard(of('trend')[0].card) as TrendCard
    render(<ToolCard card={card} />)
    const region = screen.getByRole('region', { name: 'Weight' })
    const chart = region.querySelector('[data-card="chart"]')
    expect(chart?.getAttribute('aria-hidden')).toBe('true')
    expect(chart?.querySelectorAll('polyline')).toHaveLength(2)
    expect(chart?.querySelectorAll('polyline')[1].getAttribute('stroke-dasharray')).toBe('5 4')
    expect(within(region).getAllByRole('listitem')).toHaveLength(2)
  })

  test('an empty trend draws no picture and says so', () => {
    const card = asCard(of('trend')[1].card) as TrendCard
    render(<ToolCard card={card} />)
    const region = screen.getByRole('region', { name: 'Spending' })
    expect(region.querySelector('[data-card="chart"]')).toBeNull()
    expect(within(region).getByText('Groceries: nothing recorded in this window.')).toBeInTheDocument()
  })

  test('the as-of time is said with its clock when the card carries one', () => {
    expect(asOfWords('2026-11-15T14:20')).toBe('As of Sun Nov 15, 2026, 2:20 pm')
    expect(asOfWords('2026-11-15')).toBe('As of Sun Nov 15, 2026')
    expect(asOfWords(undefined)).toBeNull()
  })

  test('a shape that does not fit draws nothing', () => {
    expect(asCard({ card: 'readings', title: 'x', items: [{ label: 'a', value: 1.5, unit: 'usd' }], more: 0 })).toBeUndefined()
    expect(asCard({ card: 'status_list', title: 'x', items: [{ name: 'a', status: 'green' }], more: 0 })).toBeUndefined()
    expect(asCard({ card: 'trend', title: 'x', unit: 'lb', series: [] })).toBeUndefined()
    expect(asCard({ card: 'trend', title: 'x', unit: 'lb', series: [{ name: 'a', points: [{ at: 'Nov 4', value: 1 }] }] })).toBeUndefined()
  })
})
