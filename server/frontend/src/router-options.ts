import { RouteErrorScreen } from './components/error-screen'

/**
 * The app router's options, apart from its route tree (`main.tsx`), so a test builds the same
 * router the page runs.
 *
 * A route that throws shows a sentence and a retry, never a blank page or a stack (architecture
 * review 2026-11-10, item 5; `components/error-screen.tsx`).
 */

export const appRouterOptions = {
  defaultErrorComponent: RouteErrorScreen,
}
