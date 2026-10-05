import { describe, expect, test } from 'vitest'

import { makePurchase } from '@/test/engine'

import {
  answerSentence,
  epochDayForIso,
  formatCents,
  isBuiltInList,
  isGroceriesList,
  isoForEpochDay,
  lastBoughtLine,
  lastExactFor,
  mayChange,
  normItem,
  parsePriceCents,
  priceFieldText,
} from './purchases'

describe('price', () => {
  test('is parsed to whole cents, never a float', () => {
    expect(parsePriceCents('8.99')).toEqual({ ok: true, cents: 899 })
    expect(parsePriceCents('8')).toEqual({ ok: true, cents: 800 })
    expect(parsePriceCents('$8.9')).toEqual({ ok: true, cents: 890 })
    expect(parsePriceCents('0.07')).toEqual({ ok: true, cents: 7 })
    expect(parsePriceCents('19.99')).toEqual({ ok: true, cents: 1999 })
  })

  test('empty is no price; anything else that is not money is refused', () => {
    expect(parsePriceCents('  ')).toEqual({ ok: true, cents: null })
    for (const bad of ['abc', '8.999', '-3', '8,99', '1e3', '8.']) {
      expect(parsePriceCents(bad)).toEqual({ ok: false })
    }
  })

  test('is shown with two decimals', () => {
    expect(formatCents(899)).toBe('$8.99')
    expect(formatCents(5)).toBe('$0.05')
    expect(priceFieldText(1499)).toBe('14.99')
    expect(priceFieldText(null)).toBe('')
  })
})

describe('days', () => {
  test('round-trip between a date input and a local epoch day', () => {
    const day = epochDayForIso('2026-09-20')!
    expect(isoForEpochDay(day)).toBe('2026-09-20')
    expect(epochDayForIso('not a date')).toBeNull()
  })
})

describe('the wording', () => {
  const today = epochDayForIso('2026-10-04')!
  const shampoo = makePurchase({ item: 'Shampoo', bought_on: epochDayForIso('2026-09-20')!, logged_by: 'Mia' })

  test('the answer names the exact entry, its date and who', () => {
    expect(answerSentence(shampoo, today)).toMatch(/^Shampoo, bought Sep 20 by Mia$/)
  })

  test('a backfilled entry says who is not recorded, never a guess', () => {
    const old = makePurchase({ item: 'Dish soap', bought_on: epochDayForIso('2026-08-02')!, logged_by: null })
    expect(answerSentence(old, today)).toBe('Dish soap, bought Aug 2 (who: not recorded)')
    expect(lastBoughtLine(old, today)).toBe('last bought Aug 2 · not recorded')
  })

  test('a Groceries label says today for today', () => {
    expect(lastBoughtLine(makePurchase({ item: 'Eggs', bought_on: today, logged_by: 'Kevin' }), today)).toBe(
      'last bought today · Kevin',
    )
  })
})

describe('last bought for a list line', () => {
  const day = (iso: string) => epochDayForIso(iso)!
  const entries = [
    makePurchase({ item: 'Shampoo', bought_on: day('2026-09-20') }),
    makePurchase({ item: ' shampoo ', bought_on: day('2026-08-01') }),
    makePurchase({ item: 'Head & Shoulders shampoo', bought_on: day('2026-09-28') }),
    makePurchase({ item: 'Shampoo', bought_on: day('2026-10-01'), deleted_at: '2026-10-02T00:00:00Z' }),
  ]

  test('is the newest live entry whose text IS the item, not a looser match', () => {
    expect(lastExactFor('SHAMPOO', entries)?.bought_on).toBe(day('2026-09-20'))
  })

  test('no entry is null, so the line says nothing', () => {
    expect(lastExactFor('Eggs', entries)).toBeNull()
  })

  test('normalises case and spacing only', () => {
    expect(normItem('  Oat   MILK ')).toBe('oat milk')
  })
})

test('which list is Groceries: the built-in one, by system key and never by name', () => {
  expect(isGroceriesList({ system_key: 'groceries' })).toBe(true)
  expect(isGroceriesList({ system_key: null })).toBe(false)
  expect(isGroceriesList({})).toBe(false)
})

test('a built-in list is any list carrying a system key', () => {
  expect(isBuiltInList({ system_key: 'groceries' })).toBe(true)
  expect(isBuiltInList({ system_key: null })).toBe(false)
})

test('who may change an entry: their own, or one nobody logged', () => {
  expect(mayChange({ logged_by_me: true, logged_by: 'Mia' })).toBe(true)
  expect(mayChange({ logged_by_me: false, logged_by: 'Kevin' })).toBe(false)
  expect(mayChange({ logged_by_me: false, logged_by: null })).toBe(true)
})
