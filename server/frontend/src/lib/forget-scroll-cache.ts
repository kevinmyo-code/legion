/**
 * Imported FIRST by `main.tsx`, before TanStack Router: its scroll cache is read from
 * sessionStorage when the router's module loads and written back on page hide. The app never
 * restores a scroll position (`router-options.ts`), so what a past page load left there is only
 * stale offsets and a list that grows by an entry per navigation: it is dropped before the router
 * can read it (review 2026-11-19, round 2).
 */
export const TANSTACK_SCROLL_CACHE = 'tsr-scroll-restoration-v1_3'

try {
  window.sessionStorage.removeItem(TANSTACK_SCROLL_CACHE)
} catch {
  // No sessionStorage here (a locked-down browser): nothing was kept either.
}
