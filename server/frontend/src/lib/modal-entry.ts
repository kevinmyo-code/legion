/**
 * The history entry a page modal pushes (`components/assistant/cards/presenter.tsx`), so the phone's
 * back gesture closes the modal instead of leaving the page.
 *
 * It goes through `history.pushState`, which TanStack Router's history wraps, with the next
 * `__TSR_index` so the router reads the back as a BACK. The router therefore sees a navigation each
 * way, and its scroll handling must leave the page where it is (`router-options.ts`).
 */

export const MODAL_ENTRY = 'legionModal'

type HistoryState = Record<string, unknown> & { __TSR_index?: number }

export function pushModalEntry() {
  const state = (window.history.state ?? {}) as HistoryState
  const key = Math.random().toString(36).slice(2, 10)
  const index = typeof state.__TSR_index === 'number' ? state.__TSR_index + 1 : 1
  window.history.pushState({ ...state, __TSR_index: index, key, __TSR_key: key, [MODAL_ENTRY]: true }, '')
}

export function isModalEntry(state: unknown): boolean {
  return typeof state === 'object' && state !== null && (state as Record<string, unknown>)[MODAL_ENTRY] === true
}
