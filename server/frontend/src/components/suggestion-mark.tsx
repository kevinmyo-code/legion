import { Sparkles } from 'lucide-react'

import { SUGGESTION_WORD } from '@/lib/suggestion'
import { cn } from '@/lib/utils'

/**
 * How a suggestion looks on a calendar: the indigo container (`primary-container`,
 * which no event or task row uses) with a dashed edge, so it reads as "offered,
 * not planned" even in greyscale. The WORD is always beside it (ADR 0053: colour
 * carries no meaning alone). Both themes define the token.
 */
export const SUGGESTION_TONE =
  'border border-dashed border-primary/60 bg-primary-container text-primary-container-foreground'

export function SuggestionMark({ compact = false, className }: { compact?: boolean; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 px-1 font-medium whitespace-nowrap',
        compact ? 'h-5 text-[0.6875rem]' : 'h-6 text-[0.8125rem]',
        className,
      )}
    >
      <Sparkles className={compact ? 'size-3' : 'size-3.5'} aria-hidden="true" />
      {SUGGESTION_WORD}
    </span>
  )
}
