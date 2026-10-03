import { useMemo, useSyncExternalStore } from 'react'

import type { Checklist, ChecklistItem, ChecklistTick } from '@/api/types'
import { tickState } from '@/lib/checklist'

/**
 * Which lists sit on this device's Home (spec D6, ticket 10).
 *
 * **Per device, in `localStorage`, on purpose.** A pin is a convenience of the
 * person holding this phone: Mia pinning Groceries must never change what
 * Kevin's Home shows, and the engine has no column for it (ADR 0045: no roles,
 * and nothing finer than the household is stored). So the key is the only
 * state, and it lives here.
 *
 * **Storage is untrustworthy and every access says so.** Safari in a private
 * window, a full quota and a cleared profile all throw, and a stored value can
 * be anything. Every read and write is in a `try`, and a value that cannot be
 * read or is not a list of ids means **no pins**, never an error on Home. A
 * write that did not land says so (`'unsaved'`) rather than looking pinned.
 */

export const PINS_KEY = 'legion.pins.v1'

/** Home shows at most three pinned lists, so pinning a fourth is refused with a
 * sentence instead of being stored and then never shown. */
export const MAX_PINS = 3

/** Lists Home falls back to when nothing is pinned. */
export const FALLBACK_LISTS = 2

/** The stored string, or `null` for "nothing there or could not read it". */
function readRaw(): string | null {
  try {
    return window.localStorage.getItem(PINS_KEY)
  } catch {
    return null
  }
}

/** Ids in the stored order. Anything that is not a JSON array of strings is no pins. */
export function parsePins(raw: string | null): string[] {
  if (raw === null) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    const ids = parsed.filter((entry): entry is string => typeof entry === 'string' && entry !== '')
    return [...new Set(ids)]
  } catch {
    return []
  }
}

export function readPins(): string[] {
  return parsePins(readRaw())
}

const listeners = new Set<() => void>()

function subscribe(onChange: () => void): () => void {
  listeners.add(onChange)
  // Another tab of the same app on this device pinned something.
  const onStorage = (event: StorageEvent) => {
    if (event.key === PINS_KEY || event.key === null) onChange()
  }
  window.addEventListener('storage', onStorage)
  return () => {
    listeners.delete(onChange)
    window.removeEventListener('storage', onStorage)
  }
}

export type PinResult = 'pinned' | 'unpinned' | 'full' | 'unsaved'

/** Pin or unpin one list. `unsaved` means the device would not keep it and
 * nothing changed; `full` means three are already pinned. */
export function setPinned(id: string, pinned: boolean): PinResult {
  const current = readPins()
  const has = current.includes(id)
  if (pinned && !has && current.length >= MAX_PINS) return 'full'
  const next = pinned ? (has ? current : [...current, id]) : current.filter((entry) => entry !== id)
  try {
    window.localStorage.setItem(PINS_KEY, JSON.stringify(next))
  } catch {
    return 'unsaved'
  }
  for (const listener of listeners) listener()
  return pinned ? 'pinned' : 'unpinned'
}

/** The pinned ids, live: another component or tab changing them re-renders this. */
export function usePins(): string[] {
  const raw = useSyncExternalStore(subscribe, readRaw, () => null)
  return useMemo(() => parsePins(raw), [raw])
}

// ---- Which lists Home shows ------------------------------------------------

function isLive<T extends { deleted_at: string | null }>(row: T): boolean {
  return row.deleted_at === null
}

/** The items of `checklist` still to do, in list order. A scheduled list's
 * ticks are per day (`tickState`), so "to do" is today's. */
export function openItems(
  checklist: Checklist,
  items: readonly ChecklistItem[],
  ticks: readonly ChecklistTick[],
  today: number,
): ChecklistItem[] {
  return items
    .filter((item) => item.checklist === checklist.id && isLive(item))
    .filter((item) => !tickState(checklist, item, [...ticks], today).ticked)
    .sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0))
}

/** When a list last changed: the later of the list's own stamp and any of its
 * items'. A list whose last item was added this morning is recent even though
 * the list row itself is a month old. Ticking is not counted: a tick is use,
 * not a change to what is on the list. */
export function lastChanged(checklist: Checklist, items: readonly ChecklistItem[]): number {
  let latest = Date.parse(checklist.updated_at) || 0
  for (const item of items) {
    if (item.checklist !== checklist.id) continue
    const stamp = Date.parse(item.updated_at) || 0
    if (stamp > latest) latest = stamp
  }
  return latest
}

export interface HomeLists {
  lists: Checklist[]
  /** True when these are the person's own pins; false for the fallback. */
  pinned: boolean
}

/**
 * The lists Home shows.
 *
 * The pins that still name a live list, in pinned order, first three. With
 * none of those (nothing pinned, or every pin names a list that is gone), the
 * fallback: unscheduled lists that still have something open, most recently
 * changed first, at most two. A scheduled list is already on Home as the rows
 * of "To do today", and repeating it here would show it twice.
 */
export function homeLists(
  checklists: readonly Checklist[],
  items: readonly ChecklistItem[],
  ticks: readonly ChecklistTick[],
  pins: readonly string[],
  today: number,
): HomeLists {
  const live = checklists.filter(isLive).filter((list) => !list.archived)
  const pinned = pins
    .map((id) => live.find((list) => list.id === id))
    .filter((list): list is Checklist => list !== undefined)
    .slice(0, MAX_PINS)
  if (pinned.length > 0) return { lists: pinned, pinned: true }

  const fallback = live
    .filter((list) => list.schedule_kind == null)
    .filter((list) => openItems(list, items, ticks, today).length > 0)
    .sort((a, b) => lastChanged(b, items) - lastChanged(a, items))
    .slice(0, FALLBACK_LISTS)
  return { lists: fallback, pinned: false }
}
