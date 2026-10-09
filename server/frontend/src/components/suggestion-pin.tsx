import { Pin } from 'lucide-react'
import { useState } from 'react'

import { usePinSuggestion } from '@/api/mutations'
import { useMe } from '@/api/queries'
import type { Event } from '@/api/types'
import { Button } from '@/components/ui/button'
import { isPinnedBy, pinnedWords, pinsOf } from '@/lib/suggestion'
import { cn } from '@/lib/utils'

/**
 * Suggestion pins (Kevin, 2026-10-09): *"pick whichever she like, and pin it on
 * the household so i can see which ones she wanna go to"*. A pin is one
 * member's "I want to go", seen by the whole household. It is not "Add to my
 * plans", which still turns the suggestion into an event.
 *
 * Two pieces, used by the calendar row, the desk chips and the suggestion
 * sheet: the line that says who wants to go, in words, and the toggle for the
 * signed-in member's own pin.
 */

/** The signed-in member's id, or null while it is not known. */
export function useMyUserId(): string | null {
  const me = useMe()
  return me.data?.signedIn ? me.data.me.user_id : null
}

/** "You and Mia want to go", with a pin beside it. Nothing when nobody has
 * pinned it. The words carry the meaning; the icon is decoration. */
export function SuggestionPinnedLine({
  event,
  compact = false,
  className,
}: {
  event: Pick<Event, 'pinned_by'>
  compact?: boolean
  className?: string
}) {
  const words = pinnedWords(pinsOf(event), useMyUserId())
  if (words === null) return null
  return (
    <span
      className={cn(
        'flex min-w-0 items-center gap-1 font-medium',
        compact ? 'text-[0.6875rem]' : 'text-[0.8125rem]',
        className,
      )}
    >
      <Pin className={cn('shrink-0 fill-current', compact ? 'size-3' : 'size-3.5')} aria-hidden="true" />
      <span className="truncate">{words}</span>
    </span>
  )
}

/** "I want to go" / "Unpin" for the signed-in member's own pin. Not
 * optimistic: the engine's answer is what the row then shows, and a refusal is
 * said in words beside the button. */
export function SuggestionPinToggle({
  event,
  size = 'sm',
  className,
}: {
  event: Pick<Event, 'id' | 'title' | 'pinned_by'>
  size?: 'sm' | 'lg'
  className?: string
}) {
  const myId = useMyUserId()
  const pin = usePinSuggestion()
  const [problem, setProblem] = useState<string | null>(null)
  const mine = isPinnedBy(event, myId)
  const label = mine ? 'Unpin' : 'I want to go'

  async function toggle() {
    setProblem(null)
    try {
      await pin.mutateAsync({ id: event.id, pinned: !mine })
    } catch (error) {
      setProblem(error instanceof Error ? error.message : mine ? 'Nothing was changed.' : 'Nothing was saved.')
    }
  }

  return (
    <span className={cn('flex flex-wrap items-center gap-2', className)}>
      <Button
        type="button"
        variant={mine ? 'secondary' : 'outline'}
        size={size}
        aria-pressed={mine}
        aria-label={`${label}: ${event.title}`}
        disabled={pin.isPending || myId === null}
        onClick={() => void toggle()}
      >
        <Pin className={mine ? 'fill-current' : undefined} aria-hidden="true" />
        {pin.isPending ? (mine ? 'Unpinning' : 'Pinning') : label}
      </Button>
      {problem && (
        <span role="alert" className="text-[0.8125rem] text-destructive">
          {problem}
        </span>
      )}
    </span>
  )
}
