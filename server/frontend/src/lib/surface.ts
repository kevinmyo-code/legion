import { useSyncExternalStore } from 'react'

/**
 * Which of the two rendered surfaces this window is.
 *
 * `family` below 1024 px (Mia's phone: the glance-and-tick view) and
 * `workbench` from 1024 px up (Kevin's desk: the full-width records view).
 * ADR 0053 / spec D1. The split is by viewport and nothing else: ADR 0045 has
 * no roles to split it on, so Mia on a laptop gets the workbench and Kevin in a
 * narrow window gets the family view, and both are accepted.
 *
 * It is a hook and not a CSS breakpoint on purpose. The two surfaces are
 * different component trees, and a workbench-only affordance is ABSENT from the
 * DOM at family width, never `display:none`: a hidden table is still in the
 * accessibility tree, still reachable by Tab, still downloaded and parsed.
 * Tests assert absence with `queryBy*`, which a CSS-hidden element would fail.
 */
export type Surface = 'family' | 'workbench'

/** The one width the whole split hangs on. Tailwind's `lg` is the same 1024 px. */
export const WORKBENCH_QUERY = '(min-width: 1024px)'

function subscribe(onChange: () => void): () => void {
  if (typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(WORKBENCH_QUERY)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}

function getSnapshot(): Surface {
  // No `matchMedia` (an ancient browser, or a test that never stubbed it) means
  // no evidence of a wide screen, and the narrow surface is the safe default:
  // it shows less, never more.
  if (typeof window.matchMedia !== 'function') return 'family'
  return window.matchMedia(WORKBENCH_QUERY).matches ? 'workbench' : 'family'
}

export function useSurface(): Surface {
  return useSyncExternalStore(subscribe, getSnapshot, () => 'family' as const)
}
