/**
 * The sentences on the family Home, in one place (spec D6, D14).
 *
 * Every section has three different sentences for three different situations
 * that look alike on a screen and mean opposite things (CLAUDE.md section 1):
 *
 * - **empty**: the read worked and there is nothing;
 * - **unreachable**: the read failed and there is nothing cached, so what the
 *   section would have said is NOT known (never rendered as empty, and for the
 *   spend card never as $0);
 * - **stale**: a refresh failed but older data is on screen, said beside that
 *   data with how old it is.
 *
 * They live together so a test can assert that no two are the same, and so
 * nobody "tidies" one section's wording into another's.
 */

export type HomeSection = 'on-today' | 'overdue' | 'to-do' | 'pinned' | 'spend'

interface Sentences {
  empty: string
  unreachable: string
  /** `when` is a relative time such as "3 minutes ago". */
  stale: (when: string) => string
}

export const HOME_COPY: Record<HomeSection, Sentences> = {
  'on-today': {
    empty: 'Nothing on the calendar today.',
    unreachable: 'Could not reach the engine, so what is on today is not known.',
    stale: (when) =>
      `Could not refresh. What is on today was last read ${when}.`,
  },
  overdue: {
    empty: 'Nothing has slipped past its date.',
    unreachable: 'Could not reach the engine, so whether anything is overdue is not known.',
    stale: (when) =>
      `Could not refresh. This list of overdue things was last read ${when}.`,
  },
  'to-do': {
    empty: 'Nothing to do today.',
    unreachable: 'Could not reach the engine, so what is due today is not known.',
    stale: (when) =>
      `Could not refresh. What is due today was last read ${when}, so it may be out of date.`,
  },
  pinned: {
    empty: 'No lists yet. Start one on Lists.',
    unreachable: 'Could not reach the engine, so your lists are not shown.',
    stale: (when) =>
      `Could not refresh. These lists were last read ${when} and may have changed.`,
  },
  spend: {
    empty: 'No card activity has reached the engine this month yet.',
    unreachable:
      'Could not reach the engine, so what was spent this month is not known. This is not zero.',
    stale: (when) =>
      `Could not refresh. These figures were last read ${when} and may have changed since.`,
  },
}

/** Pinned lists, when nothing is pinned and no list has anything left on it. */
export const NOTHING_TO_SHOW_PINNED =
  'Nothing pinned, and no list has anything left on it. Pin a list from Lists to keep it here.'

/** Shown on a pinned list card with nothing open. */
export const LIST_ALL_TICKED = 'Everything on this list is ticked.'
export const LIST_NO_ITEMS = 'Nothing on this list yet.'

/** "Good morning" and friends, from the viewer's own clock. */
export function greeting(now: Date = new Date()): string {
  const hour = now.getHours()
  if (hour < 5) return 'Hello'
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}
