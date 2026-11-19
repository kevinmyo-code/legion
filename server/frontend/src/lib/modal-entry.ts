/**
 * The history entry a page modal pushes (`components/assistant/cards/presenter.tsx`), so the phone's
 * back gesture closes the modal instead of leaving the page.
 *
 * It goes through `history.pushState`, which TanStack Router's history wraps, with the next
 * `__TSR_index` so the router reads the back as a BACK. The router therefore sees a navigation each
 * way, and its scroll handling must leave the page alone both ways (`router-options.ts`): the way
 * in is told by the entry itself (`isModalEntry`), the way out by `closedModalEntry`, set here when
 * the modal's entry is popped and taken once by the router.
 */

export const MODAL_ENTRY = 'legionModal'

type HistoryState = Record<string, unknown> & { __TSR_index?: number }

let closing = false

export function pushModalEntry() {
  const state = (window.history.state ?? {}) as HistoryState
  const key = Math.random().toString(36).slice(2, 10)
  const index = typeof state.__TSR_index === 'number' ? state.__TSR_index + 1 : 1
  window.history.pushState({ ...state, __TSR_index: index, key, __TSR_key: key, [MODAL_ENTRY]: true }, '')
}

/** The modal closed by its X, Esc or backdrop: go back over its entry. */
export function popModalEntry() {
  closing = true
  window.history.back()
}

/** The back gesture popped the modal's entry (its `popstate`). */
export function notePoppedModalEntry() {
  closing = true
}

/** Whether the navigation being finished is the way out of a modal; true once per close. */
export function closedModalEntry(): boolean {
  const was = closing
  closing = false
  return was
}

export function isModalEntry(state: unknown): boolean {
  return typeof state === 'object' && state !== null && (state as Record<string, unknown>)[MODAL_ENTRY] === true
}
