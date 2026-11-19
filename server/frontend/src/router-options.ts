import type { ParsedLocation } from '@tanstack/react-router'

import { RouteErrorScreen } from './components/error-screen'
import { isModalEntry } from './lib/modal-entry'

/**
 * The app router's options, apart from its route tree (`main.tsx`), so a test builds the same
 * router the page runs.
 *
 * A route that throws shows a sentence and a retry, never a blank page or a stack (architecture
 * review 2026-11-10, item 5; `components/error-screen.tsx`).
 *
 * Scroll: a page modal's history entry (`lib/modal-entry.ts`) reaches the router as a navigation
 * each way, and by default the router scrolled the window to the top on both: under an opening
 * diagram the page jumped to the top (the docked orb left, the stage showed through the backdrop),
 * and on close it flashed at the top before the page came back (Kevin, 2026-11-19: "the diagrams
 * are flickering hard"; `server/tests/realrun_try_orb_flow.mjs`). So the router leaves the scroll
 * alone when it arrives at a modal's entry, and restores each entry's own position on the way back
 * (as the browser does by itself without a router). A new page still starts at the top.
 */
export function scrollRestorationFor({ location }: { location: ParsedLocation }): boolean {
  return !isModalEntry(location.state)
}

export const appRouterOptions = {
  defaultErrorComponent: RouteErrorScreen,
  scrollRestoration: scrollRestorationFor,
}
