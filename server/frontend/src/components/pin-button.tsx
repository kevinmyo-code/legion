import { Pin } from 'lucide-react'
import { useState } from 'react'

import type { Checklist } from '@/api/types'
import { Button } from '@/components/ui/button'
import { MAX_PINS, setPinned, usePins } from '@/lib/pins'

/**
 * The pin button in a list's header, on both surfaces (spec D6, ticket 10).
 *
 * Pinning is a convenience of THIS device (`lib/pins.ts`), so the button says so
 * where it matters: it never claims to have pinned anything that did not stick.
 * Pinning a fourth list is refused in words, because Home shows three; a device
 * that will not keep the pin says nothing changed.
 *
 * State is told by the word ("Pinned" or "Pin to Home") and `aria-pressed`, never
 * by the glyph or the tint alone (ADR 0053).
 */
export function PinButton({ checklist }: { checklist: Checklist }) {
  const pins = usePins()
  const pinned = pins.includes(checklist.id)
  const [note, setNote] = useState<string | null>(null)

  const toggle = () => {
    const result = setPinned(checklist.id, !pinned)
    if (result === 'full') {
      setNote(`Home shows ${MAX_PINS} pinned lists. Unpin one first.`)
    } else if (result === 'unsaved') {
      setNote('This device would not keep the pin, so nothing changed.')
    } else {
      setNote(null)
    }
  }

  return (
    <div className="flex flex-col items-start gap-1">
      <Button
        type="button"
        size="sm"
        variant={pinned ? 'secondary' : 'outline'}
        aria-pressed={pinned}
        aria-label={`Pin "${checklist.name}" to Home`}
        onClick={toggle}
      >
        <Pin className={pinned ? 'fill-current' : undefined} aria-hidden="true" />
        {pinned ? 'Pinned' : 'Pin to Home'}
      </Button>
      {note && (
        <p role="status" className="max-w-64 text-[0.8125rem] text-muted-foreground">
          {note}
        </p>
      )}
    </div>
  )
}
