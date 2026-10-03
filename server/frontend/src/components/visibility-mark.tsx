import { Lock, Users } from 'lucide-react'

import { PRIVATE_WORDS, SHARED_WORDS, type Visibility } from '@/lib/visibility'
import { cn } from '@/lib/utils'

/**
 * Who sees a row, said in words.
 *
 * Shared is the pink container with a two-person glyph and the word "Shared";
 * private is a lock and the words "Only you". **The word is always on the
 * screen**, never a colour or a glyph alone (ADR 0053: colour carries no
 * meaning alone), so a greyscale screenshot or a colour-blind reader loses
 * nothing. The glyph is decoration and is hidden from a screen reader, which
 * reads the word.
 *
 * `onTint` is for a mark sitting on a row that is itself pink: the chip goes a
 * step stronger so it does not vanish into its own row (the prototype's
 * `color-mix` of the accent into the container).
 */
export function VisibilityMark({
  visibility,
  onTint = false,
  compact = false,
  className,
}: {
  visibility: Visibility
  onTint?: boolean
  /** Smaller type for a block on the week grid or a chip in a month cell. The
   * word is still there: compact trims the size, never the sentence. */
  compact?: boolean
  className?: string
}) {
  if (visibility === 'private') {
    return (
      <span
        className={cn(
          'inline-flex shrink-0 items-center gap-1 px-1 whitespace-nowrap text-muted-foreground',
          compact ? 'h-5 text-[0.6875rem]' : 'h-6 text-[0.8125rem]',
          className,
        )}
      >
        <Lock className={compact ? 'size-3' : 'size-3.5'} aria-hidden="true" />
        {PRIVATE_WORDS}
      </span>
    )
  }
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-full bg-shared font-medium whitespace-nowrap text-shared-foreground',
        compact ? 'h-5 pr-2 pl-1.5 text-[0.6875rem]' : 'h-6 pr-2.5 pl-2 text-[0.8125rem]',
        onTint && 'bg-[color-mix(in_oklab,var(--shared-accent)_22%,var(--shared))]',
        className,
      )}
    >
      <Users className={compact ? 'size-3' : 'size-3.5'} aria-hidden="true" />
      {SHARED_WORDS}
    </span>
  )
}
