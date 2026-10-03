import { useCallback } from 'react'

/**
 * A `refetchInterval` that only ticks while the page is on screen.
 *
 * The web keeps refreshing while it is open (spec D13, user story 14: "I want to
 * see when Kevin has ticked or added something, within about half a minute and
 * without refreshing") but a tab nobody is looking at has no business asking the
 * engine every 30 seconds, and a phone in a pocket has even less. Returning
 * `false` stops the timer; TanStack re-reads this function on every tick, so the
 * moment the page is visible again the interval is live again.
 *
 * Becoming visible also refetches immediately (`refetchOnWindowFocus: 'always'`
 * on the query, which TanStack drives from `visibilitychange`), so a person
 * returning to the tab never waits out the rest of an interval to see current
 * data. This helper is only the "while visible" half.
 *
 * Shared so the later ledger queries (5 minutes, spec D13: the source changes
 * every 6 hours) use the same gate instead of growing their own.
 */
export function useVisibleInterval(ms: number): () => number | false {
  return useCallback(() => (document.visibilityState === 'visible' ? ms : false), [ms])
}

/** How often `useChanges` asks again while visible. User story 14's "about half
 * a minute" is this number. */
export const CHANGES_POLL_MS = 30_000
