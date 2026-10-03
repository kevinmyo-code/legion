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
  className,
}: {
  visibility: Visibility
  onTint?: boolean
  className?: string
}) {
  if (visibility === 'private') {
    return (
      <span
        className={cn(
          'inline-flex h-6 shrink-0 items-center gap-1 px-1 text-[0.8125rem] whitespace-nowrap text-muted-foreground',
          className,
        )}
      >
        <Lock className="size-3.5" aria-hidden="true" />
        {PRIVATE_WORDS}
      </span>
    )
  }
  return (
    <span
      className={cn(
        'inline-flex h-6 shrink-0 items-center gap-1 rounded-full bg-shared pr-2.5 pl-2 text-[0.8125rem] font-medium whitespace-nowrap text-shared-foreground',
        onTint && 'bg-[color-mix(in_oklab,var(--shared-accent)_22%,var(--shared))]',
        className,
      )}
    >
      <Users className="size-3.5" aria-hidden="true" />
      {SHARED_WORDS}
    </span>
  )
}
