export type Visibility = 'shared' | 'private'

/**
 * Whether a row is shared with the household or private to the signed-in
 * member (ADR 0052).
 *
 * The engine only ever serves a member the rows they may see, so `private` here
 * always means "private to YOU": another member's private row never arrives
 * (it is a 404, and only a redacted tombstone in the changes feed). A row from
 * an engine that predates the field carries no `visibility` and is shared,
 * which is what every row was before the field existed.
 */
export function visibilityOf(row: { visibility?: Visibility | null }): Visibility {
  return row.visibility === 'private' ? 'private' : 'shared'
}

/** What a private row says about itself, in words, wherever it renders. */
export const PRIVATE_WORDS = 'Only you'
/** What a shared row says about itself, in words, wherever it renders. */
export const SHARED_WORDS = 'Shared'
