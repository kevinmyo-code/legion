import type { Event } from '@/api/types'

/**
 * A suggestion is something the engine found that the household MIGHT do (a
 * weekend event in town). It is never the household's plan: no reminder, never
 * done, and it must not count toward "you have N things", the horizon strip,
 * due or overdue rows, or any busy/free reading. Only the calendar views show
 * one, visibly apart and with the word "Suggestion".
 *
 * Every reader that treats events as plans filters through `plansOnly`; the
 * calendar views are the only places that do not.
 */
export const SUGGESTION_WORD = 'Suggestion'

export function isSuggestion(event: Pick<Event, 'kind'>): boolean {
  return event.kind === 'suggestion'
}

/** The events that are the household's plans: everything except suggestions. */
export function plansOnly<T extends Pick<Event, 'kind'>>(events: readonly T[]): T[] {
  return events.filter((event) => !isSuggestion(event))
}

/** The first http(s) link in a suggestion's notes (the engine puts the source
 * URL there), or null. */
export function firstLink(notes: string | null | undefined): string | null {
  const match = /https?:\/\/[^\s<>"')]+/i.exec(notes ?? '')
  return match ? match[0].replace(/[.,;]+$/, '') : null
}

export interface SuggestionMeta {
  venue: string | null
  city: string | null
  address: string | null
  /** Only ever an http(s) URL; anything else reads as null. */
  url: string | null
  price: string | null
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null
}

/** `structured_meta` on a suggestion, read defensively: it arrives as `unknown`
 * from an engine fed by the web, so only non-empty strings are kept and the URL
 * must be http(s). Returns null when the event carries no usable meta at all. */
export function suggestionMeta(event: Pick<Event, 'structured_meta'>): SuggestionMeta | null {
  const raw = event.structured_meta
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return null
  const bag = raw as Record<string, unknown>
  const url = text(bag.url)
  const meta: SuggestionMeta = {
    venue: text(bag.venue),
    city: text(bag.city),
    address: text(bag.address),
    url: url !== null && /^https?:\/\//i.test(url) ? url : null,
    price: text(bag.price),
  }
  return Object.values(meta).every((v) => v === null) ? null : meta
}

/** One member who pinned a suggestion ("I want to go"), as the engine sends it. */
export interface Pin {
  user_id: string
  display_name: string
}

/** Who pinned it, oldest first; `[]` when nobody has or the row predates pins. */
export function pinsOf(event: Pick<Event, 'pinned_by'>): readonly Pin[] {
  return Array.isArray(event.pinned_by) ? event.pinned_by : []
}

export function isPinnedBy(event: Pick<Event, 'pinned_by'>, userId: string | null | undefined): boolean {
  return userId != null && pinsOf(event).some((pin) => pin.user_id === userId)
}

/**
 * Who wants to go, in words (Kevin, 2026-10-09). "You" first, then everyone
 * else in the order they pinned: "You want to go", "Mia wants to go", "You and
 * Mia want to go", "You, Mia and Sam want to go". Null when nobody has pinned
 * it. The Android row says exactly the same (`EventSuggestions.pinnedWords`).
 */
export function pinnedWords(pins: readonly Pin[], myUserId: string | null | undefined): string | null {
  const mine = myUserId != null && pins.some((pin) => pin.user_id === myUserId)
  const others = pins.filter((pin) => pin.user_id !== myUserId).map((pin) => pin.display_name)
  const names = mine ? ['You', ...others] : others
  if (names.length === 0) return null
  if (names.length === 1) return names[0] === 'You' ? 'You want to go' : `${names[0]} wants to go`
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]} want to go`
}

/** The "Pinned only" filter: every plan stays; a suggestion stays only when
 * someone in the household has pinned it. */
export function pinnedSuggestionsOnly<T extends Pick<Event, 'kind' | 'pinned_by'>>(events: readonly T[]): T[] {
  return events.filter((event) => !isSuggestion(event) || pinsOf(event).length > 0)
}
