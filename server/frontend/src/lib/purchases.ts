import type { Purchase } from '@/api/types'
import { dateForEpochDay, epochDay } from '@/lib/day'

/**
 * The bought log's small rules, in one file: how a price is typed and shown, how
 * a day is worded, what each source is called, and which entry a list item's
 * "last bought" line comes from. Pure, so each is testable without a screen.
 *
 * Everything user-facing here follows ADR 0049's wording rule: a figure that was
 * typed by hand says so, and an absence is "no record", never "never bought".
 */

/** Words beside every price (purchase-log map: price is what someone typed). */
export const ENTERED_BY_HAND = 'entered by hand'

/** The same floor the engine's matcher starts from: case, trim, whitespace. */
export function normItem(text: string): string {
  return text.toLowerCase().trim().replace(/\s+/g, ' ')
}

/** 899 -> "$8.99". Cents are integers all the way down; this is display only. */
export function formatCents(cents: number): string {
  const sign = cents < 0 ? '-' : ''
  const abs = Math.abs(cents)
  return `${sign}$${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`
}

export type PriceParse = { ok: true; cents: number | null } | { ok: false }

/** What a person typed in the price field. Empty is fine (no price); "8.99",
 * "8", "$8.99" and "8.9" are; anything else is refused, never guessed at. */
export function parsePriceCents(raw: string): PriceParse {
  const text = raw.trim()
  if (text === '') return { ok: true, cents: null }
  const match = /^\$?(\d{1,7})(?:\.(\d{1,2}))?$/.exec(text)
  if (!match) return { ok: false }
  const dollars = Number(match[1])
  const fraction = match[2] === undefined ? 0 : Number(match[2].padEnd(2, '0'))
  return { ok: true, cents: dollars * 100 + fraction }
}

/** The text a price field shows for an entry's stored cents. */
export function priceFieldText(cents: number | null | undefined): string {
  if (cents === null || cents === undefined) return ''
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`
}

/** `YYYY-MM-DD` for a local epoch day, for a date input's value. */
export function isoForEpochDay(day: number): string {
  const d = dateForEpochDay(day)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const date = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${date}`
}

/** The epoch day a date input's `YYYY-MM-DD` names, or null if it is not a date. */
export function epochDayForIso(iso: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!match) return null
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return Number.isNaN(date.getTime()) ? null : epochDay(date)
}

/** "Sep 20", with the year only when it is not this year. Built from the day's
 * own components, never through a UTC conversion. */
export function dayLabel(day: number, today: number): string {
  const date = dateForEpochDay(day)
  const sameYear = date.getFullYear() === dateForEpochDay(today).getFullYear()
  return date.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    ...(sameYear ? {} : { year: 'numeric' }),
  })
}

/** "today" for today, else `dayLabel`. */
export function dayLabelOrToday(day: number, today: number): string {
  return day === today ? 'today' : dayLabel(day, today)
}

/** Who, in words. Null is NOT RECORDED, which is said, never guessed. */
export function whoWords(entry: Pick<Purchase, 'logged_by'>): string {
  return entry.logged_by ?? 'not recorded'
}

export type SourceKey = 'GROCERIES_TICK' | 'MANUAL' | 'GROCERIES_BACKFILL'

/** The chip each source wears (ticket 05's wording). */
export function sourceLabel(source: string): string {
  if (source === 'GROCERIES_TICK') return 'Groceries tick'
  if (source === 'GROCERIES_BACKFILL') return 'Old Groceries tick'
  return 'Logged by hand'
}

/** The sentence the search answers with: the exact entry, its date and who. */
export function answerSentence(entry: Purchase, today: number): string {
  const when = dayLabel(entry.bought_on, today)
  return entry.logged_by
    ? `${entry.item}, bought ${when} by ${entry.logged_by}`
    : `${entry.item}, bought ${when} (who: not recorded)`
}

/** "last bought Sep 20 · Mia" for a Groceries line. */
export function lastBoughtLine(entry: Purchase, today: number): string {
  return `last bought ${dayLabelOrToday(entry.bought_on, today)} · ${whoWords(entry)}`
}

/** Newest live entry whose text IS `item` (case and spacing aside): the entry a
 * Groceries line's label names. Entries arrive newest first, but this does not
 * rely on it. Null when there is no such entry: a line with no record says
 * nothing, never "never bought". */
export function lastExactFor(item: string, entries: readonly Purchase[]): Purchase | null {
  const wanted = normItem(item)
  let best: Purchase | null = null
  for (const entry of entries) {
    if (entry.deleted_at !== null || normItem(entry.item) !== wanted) continue
    if (best === null || entry.bought_on > best.bought_on) best = entry
  }
  return best
}

/** The `system_key` of the household's built-in Groceries list. */
export const SYSTEM_KEY_GROCERIES = 'groceries'

/** Whether a checklist is the household's built-in Groceries list: the engine's
 * own rule is its `system_key`, never its name (2026-10-05). A list a person
 * happened to name Groceries is just a list, and its ticks log nothing. */
export function isGroceriesList(list: { system_key?: string | null }): boolean {
  return list.system_key === SYSTEM_KEY_GROCERIES
}

/** Whether a checklist is built in, and so cannot be deleted, renamed,
 * archived or made private (the engine refuses all four). */
export function isBuiltInList(list: { system_key?: string | null }): boolean {
  return list.system_key != null
}

/** Whether a signed-in member may edit or delete the entry: their own, or one
 * nobody is recorded as having logged. */
export function mayChange(entry: Pick<Purchase, 'logged_by_me' | 'logged_by'>): boolean {
  return entry.logged_by_me || entry.logged_by === null
}
