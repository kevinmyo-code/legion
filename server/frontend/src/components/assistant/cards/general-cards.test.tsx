import { render, screen, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'

import rawFixtures from '../../../../../../shared/cards/fixtures.json'

import { ToolCard } from './card'
import { runs } from './trend'
import {
  asCard,
  asOfWords,
  levelWords,
  meterWords,
  readingsHeadline,
  readingWords,
  seriesWords,
  splitWords,
  STATUS_WORDS,
  statusHeadline,
  trendHeadline,
  type Headline,
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
  words: {
    values?: string[]
    statuses?: string[]
    series?: string[]
    asOfStartsWith?: string
    more?: string
    headline?: Headline | null
    split?: string[]
    meters?: string[]
    levels?: string[]
    guide?: string
  }
}

/** The fixture's headline, with `attention` left to the card (the fixtures pin words only). */
const words = (h: Headline | null) => (h ? { label: h.label, value: h.value } : null)

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

  test('each card says the fixture headline, and the words beside its pictures', () => {
    const readings = asCard(of('readings')[0].card) as ReadingsCard
    expect(words(readingsHeadline(readings))).toEqual(of('readings')[0].words.headline)
    expect(readings.items.flatMap((i) => splitWords(i) ?? [])).toEqual(of('readings')[0].words.split)
    const status = of('status_list')[0]
    const list = asCard(status.card) as StatusListCard
    expect(words(statusHeadline(list))).toEqual({ label: undefined, ...status.words.headline })
    expect(list.items.flatMap((i) => (i.meter ? meterWords(i.meter) : []))).toEqual(status.words.meters)
    expect(list.items.flatMap((i) => (i.level ? levelWords(i.level) : []))).toEqual(status.words.levels)
    for (const f of of('trend')) {
      const card = asCard(f.card) as TrendCard
      expect(words(trendHeadline(card))).toEqual(f.words.headline)
      expect(card.guide?.label).toEqual(f.words.guide)
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

  test('the pictures: a headline, a meter and its mark, a hatched split, status marks and a level', () => {
    const readings = asCard(of('readings')[0].card) as ReadingsCard
    render(<ToolCard card={readings} />)
    const region = screen.getByRole('region', { name: 'Laptop' })
    expect(region.querySelector('[data-card="headline"]')).toHaveTextContent('62%')
    expect(region.querySelectorAll('[data-card="meter"]')).toHaveLength(1)
    expect(within(region).getByText('Alert at 95%')).toBeInTheDocument()
    expect(region.querySelector('[data-card="split"] pattern')).not.toBeNull()
    expect(within(region).getByText('96 °C (needs attention)')).toBeInTheDocument()
    const list = asCard(of('status_list')[0].card) as StatusListCard
    render(<ToolCard card={list} />)
    const services = screen.getByRole('region', { name: 'Services' })
    expect(services.querySelector('[data-card="headline"]')).toHaveTextContent('4 need attention')
    expect(services.querySelectorAll('[data-card="mark"]')).toHaveLength(7)
    expect(within(services).getByRole('img', { name: 'Level 2 of 4' })).toBeInTheDocument()
    expect(within(services).getByText('$520.00 of $500.00')).toBeInTheDocument()
  })

  test('sleep draws a column a logged night, a missed night an empty slot, and its target', () => {
    const card = asCard(of('trend')[2].card) as TrendCard
    render(<ToolCard card={card} />)
    const region = screen.getByRole('region', { name: 'Sleep' })
    expect(region.querySelectorAll('[data-card="column"]')).toHaveLength(4)
    expect(region.querySelector('[data-card="guide"]')).not.toBeNull()
    expect(region.querySelector('[data-card="low"]')).toHaveTextContent('0 h')
    expect(within(region).getByText('Target 8 h')).toBeInTheDocument()
  })

  test('a long silence breaks a line into runs; a steady series is one run', () => {
    expect(runs(['2026-11-01', '2026-11-02', '2026-11-03'])).toEqual([[0, 1, 2]])
    expect(runs(['2026-11-01', '2026-11-02', '2026-11-03', '2026-11-20', '2026-11-21'])).toEqual([[0, 1, 2], [3, 4]])
    expect(runs([])).toEqual([])
  })

  test('a picture field that does not fit draws nothing', () => {
    const reading = { label: 'a', value: 1, unit: '' }
    const card = (item: object, extra: object = {}) => ({ card: 'readings', title: 'x', items: [item], more: 0, ...extra })
    expect(asCard(card({ ...reading, scale: { min: 5, max: 5 } }))).toBeUndefined()
    expect(asCard(card({ ...reading, mark: { value: 1, label: 'x' } }))).toBeUndefined()
    expect(asCard(card({ ...reading, split: { unit: 'g', parts: [{ label: 'P', value: 1 }] } }))).toBeUndefined()
    expect(asCard(card(reading, { lead: 1 }))).toBeUndefined()
    const row = (extra: object) => ({ card: 'status_list', title: 'x', items: [{ name: 'a', status: 'ok', ...extra }], more: 0 })
    expect(asCard(row({ meter: { value: 1, max: 0, unit: '' } }))).toBeUndefined()
    expect(asCard(row({ level: { value: 5, of: 4 } }))).toBeUndefined()
    const trend = { card: 'trend', title: 'x', unit: 'h', series: [{ name: 'a', points: [] }] }
    expect(asCard({ ...trend, style: 'pie' })).toBeUndefined()
    expect(asCard({ ...trend, style: 'bars', series: [...trend.series, ...trend.series] })).toBeUndefined()
    expect(asCard({ ...trend, guide: { value: '8', label: 'x' } })).toBeUndefined()
    expect(asCard(card(reading))).toBeDefined()
  })

  test('a shape that does not fit draws nothing', () => {
    expect(asCard({ card: 'readings', title: 'x', items: [{ label: 'a', value: 1.5, unit: 'usd' }], more: 0 })).toBeUndefined()
    expect(asCard({ card: 'status_list', title: 'x', items: [{ name: 'a', status: 'green' }], more: 0 })).toBeUndefined()
    expect(asCard({ card: 'trend', title: 'x', unit: 'lb', series: [] })).toBeUndefined()
    expect(asCard({ card: 'trend', title: 'x', unit: 'lb', series: [{ name: 'a', points: [{ at: 'Nov 4', value: 1 }] }] })).toBeUndefined()
  })
})
