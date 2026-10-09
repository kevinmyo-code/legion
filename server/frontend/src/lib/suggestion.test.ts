import { describe, expect, test } from 'vitest'

import type { Event } from '@/api/types'
import { epochDay, todayEpochDay } from '@/lib/day'
import { buildHorizon, buildMonth, overdueTasks } from '@/lib/horizon'
import {
  firstLink,
  isPinnedBy,
  isSuggestion,
  pinnedSuggestionsOnly,
  pinnedWords,
  plansOnly,
  suggestionMeta,
} from '@/lib/suggestion'
import { eventsOnDay } from '@/lib/today'
import { makeEvent, todayAt } from '@/test/engine'

// A suggestion is never the household's plan: none of the readers that treat
// events as plans may count or list one.

function suggestion(offset = 0): Event {
  return makeEvent({ title: 'Riverfest', kind: 'suggestion', starts_at: todayAt(11, 0, offset), notes: 'https://example.test/e $10' })
}
function plan(offset = 0): Event {
  return makeEvent({ title: 'Dentist', kind: 'event', starts_at: todayAt(15, 0, offset) })
}

describe('plansOnly', () => {
  test('drops suggestions and keeps events and tasks', () => {
    const task = makeEvent({ title: 'Essay', kind: 'task', starts_at: todayAt(23, 59) })
    expect(plansOnly([suggestion(), plan(), task]).map((e) => e.title)).toEqual(['Dentist', 'Essay'])
    expect(isSuggestion(suggestion())).toBe(true)
    expect(isSuggestion(plan())).toBe(false)
  })

  test('firstLink finds the source URL without trailing punctuation', () => {
    expect(firstLink('Free. https://example.test/a/b, 8pm')).toBe('https://example.test/a/b')
    expect(firstLink('no link')).toBeNull()
    expect(firstLink(null)).toBeNull()
  })
})

describe('readers that mean "plans"', () => {
  const today = todayEpochDay()

  test('eventsOnDay (Today) leaves a suggestion out', () => {
    expect(eventsOnDay(today, [suggestion(), plan()]).map((e) => e.title)).toEqual(['Dentist'])
    expect(eventsOnDay(today, [suggestion()])).toEqual([])
  })

  test('the horizon strip counts no suggestion as an event or a task', () => {
    const cells = buildHorizon(today, [suggestion(), suggestion(1)])
    expect(cells.every((cell) => cell.events === 0 && cell.tasks === 0)).toBe(true)
    expect(cells[0]).not.toHaveProperty('suggestions')
  })

  test('the month grid counts a suggestion apart from events', () => {
    const cells = buildMonth(new Date(), [suggestion(), plan()])
    const cell = cells.find((c) => c.day === today)!
    expect(cell.events).toBe(1)
    expect(cell.suggestions).toBe(1)
  })

  test('overdue never lists one', () => {
    const past = makeEvent({ title: 'Old thing', kind: 'suggestion', starts_at: todayAt(11, 0, -3) })
    expect(overdueTasks(epochDay(new Date()), [past])).toEqual([])
  })
})

describe('suggestionMeta', () => {
  test('keeps only strings and http(s) urls', () => {
    const meta = suggestionMeta({
      structured_meta: { city: 'Austin', venue: 42, address: null, url: 'javascript:alert(1)', price: '$10' },
    })
    expect(meta).toEqual({ city: 'Austin', venue: null, address: null, url: null, price: '$10' })
    expect(suggestionMeta({ structured_meta: { url: 'https://a.test/x' } })?.url).toBe('https://a.test/x')
  })
  test('absent or junk meta reads as null', () => {
    expect(suggestionMeta({ structured_meta: null })).toBeNull()
    expect(suggestionMeta({ structured_meta: 'x' })).toBeNull()
    expect(suggestionMeta({ structured_meta: [1] })).toBeNull()
    expect(suggestionMeta({ structured_meta: { city: 3 } })).toBeNull()
  })
})

describe('pinnedWords', () => {
  const ME = 'me-id'
  const mia = { user_id: 'mia-id', display_name: 'Mia' }
  const sam = { user_id: 'sam-id', display_name: 'Sam' }
  const me = { user_id: ME, display_name: 'Kevin' }

  test.each([
    ['nobody', [], null],
    ['only me', [me], 'You want to go'],
    ['only Mia', [mia], 'Mia wants to go'],
    ['me and Mia, Mia first', [mia, me], 'You and Mia want to go'],
    ['me and two others', [sam, me, mia], 'You, Sam and Mia want to go'],
    ['two others', [mia, sam], 'Mia and Sam want to go'],
  ])('%s', (_name, pins, words) => {
    expect(pinnedWords(pins, ME)).toBe(words)
  })

  test('with no signed-in id, everyone is named', () => {
    expect(pinnedWords([me, mia], null)).toBe('Kevin and Mia want to go')
  })
})

describe('pinned helpers', () => {
  test('isPinnedBy reads pinned_by and treats a missing list as nobody', () => {
    const pinned = makeEvent({ title: 'Jazz', kind: 'suggestion', pinned_by: [{ user_id: 'a', display_name: 'A' }] })
    expect(isPinnedBy(pinned, 'a')).toBe(true)
    expect(isPinnedBy(pinned, 'b')).toBe(false)
    expect(isPinnedBy(pinned, null)).toBe(false)
    const legacy = { ...pinned, pinned_by: undefined } as unknown as Event
    expect(isPinnedBy(legacy, 'a')).toBe(false)
  })

  test('Pinned only keeps every plan and only the pinned suggestions', () => {
    const pinned = makeEvent({ title: 'Jazz', kind: 'suggestion', pinned_by: [{ user_id: 'a', display_name: 'A' }] })
    const unpinned = suggestion()
    const dentist = plan()
    expect(pinnedSuggestionsOnly([pinned, unpinned, dentist]).map((e) => e.title)).toEqual(['Jazz', 'Dentist'])
  })
})
