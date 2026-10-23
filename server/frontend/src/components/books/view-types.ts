import type { LocatorJson } from '@/api/books'
import type { ReaderSettings } from '@/lib/books/comfort'
import type { Place } from '@/lib/books/sync'

/** Where a view opens. `exact` also carries the fraction, which is what is used if the locator names something this copy does not have; `near` carries only that fraction. */
export type StartPoint =
  | { kind: 'none' }
  | { kind: 'exact'; locator: LocatorJson; totalProgression: number }
  | { kind: 'near'; totalProgression: number }

/** What a view tells the reader shell. */
export interface ViewEvents {
  /** The opening jump has settled and the view is ready to be turned. */
  onReady: (info: { appliedExact: boolean; pageCount?: number }) => void
  /** The position changed. Reported also for the opening jump; the shell ignores it until armed. */
  onPlace: (place: Place) => void
  /** The person pressed a key, tapped a zone or clicked a button that turns the page. */
  onUserTurn: () => void
  /** A tap in the middle third. */
  onMiddleTap: () => void
  onError: (message: string) => void
}

/** What the shell can ask of a view. */
export interface ViewControl {
  next(): void
  prev(): void
  /** Jump to a locator or table-of-contents href (EPUB) or `{ page }` (paged). */
  goTo(locator: LocatorJson): void
  /** The place right now, for a bookmark. */
  current(): Place | undefined
  pageCount?: number
}

export interface ViewProps extends ViewEvents {
  start: StartPoint
  settings: ReaderSettings
  controlRef: { current: ViewControl | null }
}

/** Left and right thirds turn pages; the middle third toggles the bars, as on the phone. */
export function tapZone(x: number, width: number): 'prev' | 'next' | 'middle' {
  if (width <= 0) return 'middle'
  const f = x / width
  if (f < 1 / 3) return 'prev'
  if (f > 2 / 3) return 'next'
  return 'middle'
}
