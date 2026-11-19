import type { ParsedLocation } from '@tanstack/react-router'

import { RouteErrorScreen } from './components/error-screen'
import { closedModalEntry, isModalEntry } from './lib/modal-entry'

/**
 * The app router's options, apart from its route tree (`main.tsx`), so a test builds the same
 * router the page runs.
 *
 * A route that throws shows a sentence and a retry, never a blank page or a stack (architecture
 * review 2026-11-10, item 5; `components/error-screen.tsx`).
 *
 * Scroll: a page modal's history entry (`lib/modal-entry.ts`) reaches the router as a navigation
 * each way, and the router scrolled the window to the top on both: under an opening diagram the
 * page jumped to the top, and on close it flashed at the top before the page came back (Kevin,
 * 2026-11-19: "the diagrams are flickering hard"; `server/tests/realrun_try_orb_flow.mjs`). So the
 * router does nothing to any scroll on the way into a modal's entry or back out of one.
 *
 * Everything else scrolls exactly as before (review 2026-11-19, F2): every navigation, Back and
 * Forward included, starts at the top and no box inside a page is ever put back. TanStack's only
 * hook for skipping a navigation's scroll is this function, and giving one switches its restoring
 * on; `scrollRestorationKey` hands it a key no location ever has twice, so it never finds anything
 * to restore and falls through to its plain scroll to the top.
 */
export function scrollRestorationFor({ location }: { location: ParsedLocation }): boolean {
  // Read on every navigation, so the way-out mark can never outlive the one it was set for.
  const leavingModal = closedModalEntry()
  return !leavingModal && !isModalEntry(location.state)
}

let keys = 0
export function scrollRestorationKey(): string {
  keys += 1
  return `legion-no-restore-${keys}`
}

export const appRouterOptions = {
  defaultErrorComponent: RouteErrorScreen,
  scrollRestoration: scrollRestorationFor,
  getScrollRestorationKey: scrollRestorationKey,
}
