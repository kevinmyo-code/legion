import type { LocatorJson } from '@/api/books'

/**
 * Applying a position that was recorded on another device (ticket 07, "Locator
 * compatibility"). An exact locator is applied only when the thing it names exists
 * in the book open here. Otherwise the 0-1 `totalProgression` is applied and the
 * page says "near", never claiming the exact spot.
 */

/** "your phone" for the phone's device names ("A25"), "the web" for another browser. */
export function whereWord(device: string | undefined): string {
  return device === 'web' ? 'the web' : 'your phone'
}

export const SENTENCES = {
  exact: (device?: string) => `Picked up where you left off on ${whereWord(device)}.`,
  near: (device?: string) => `Opened near where you were on ${whereWord(device)}.`,
  nearPaged: (device?: string) =>
    `Opened near where you were on ${whereWord(device)}. This file has a different number of pages here.`,
} as const

export type Resolution =
  | { kind: 'exact'; locator: LocatorJson }
  | { kind: 'near'; totalProgression: number }

function clamp01(n: number): number {
  return Math.min(1, Math.max(0, n))
}

/** `OEBPS/ch1.xhtml#a` and `/OEBPS/ch1.xhtml` both name the resource `OEBPS/ch1.xhtml`. */
export function normalizeHref(href: string): string {
  return href.split('#')[0].replace(/^\/+/, '')
}

/** A Readium Locator's resource href, or undefined when the JSON is not one. */
export function locatorHref(locator: LocatorJson | null): string | undefined {
  return locator && typeof locator.href === 'string' ? normalizeHref(locator.href) : undefined
}

/** `{ page }` (0-based) for a PDF or comic, or undefined when the JSON is not one. */
export function locatorPage(locator: LocatorJson | null): number | undefined {
  return locator && typeof locator.page === 'number' && Number.isInteger(locator.page) ? locator.page : undefined
}

export function resolveEpub(
  remote: { locator: LocatorJson | null; total_progression: number },
  readingOrderHrefs: readonly string[],
): Resolution {
  const href = locatorHref(remote.locator)
  const known = new Set(readingOrderHrefs.map(normalizeHref))
  if (remote.locator && href && known.has(href)) return { kind: 'exact', locator: remote.locator }
  return { kind: 'near', totalProgression: clamp01(remote.total_progression) }
}

export function resolvePaged(
  remote: { locator: LocatorJson | null; total_progression: number },
  pageCount: number,
): { kind: 'exact'; page: number } | { kind: 'near'; page: number } {
  const page = locatorPage(remote.locator)
  if (page !== undefined && page >= 0 && page < pageCount) return { kind: 'exact', page }
  return { kind: 'near', page: Math.round(clamp01(remote.total_progression) * Math.max(0, pageCount - 1)) }
}

/** The sentence for how a remote position was applied. */
export function appliedSentence(kind: 'exact' | 'near', paged = false, device?: string): string {
  if (kind === 'exact') return SENTENCES.exact(device)
  return paged ? SENTENCES.nearPaged(device) : SENTENCES.near(device)
}
