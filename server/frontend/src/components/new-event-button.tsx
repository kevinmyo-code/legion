import { Plus } from 'lucide-react'

import { useEventSheet } from '@/components/event-sheet-context'
import { Button } from '@/components/ui/button'
import { useSurface } from '@/lib/surface'

/**
 * The "+" that opens the event sheet on today. A 56 px floating button above
 * the tab bar at family width (spec D6: the thumb's reach), and a labelled
 * "New event" button at workbench width, where there is room to say what it
 * does. Renders nothing outside the signed-in shell, where there is no sheet to
 * open.
 */
export function NewEventButton() {
  const sheet = useEventSheet()
  const surface = useSurface()
  if (sheet === null) return null

  if (surface === 'workbench') {
    return (
      <Button size="lg" onClick={() => sheet.open({ kind: 'create' })}>
        <Plus />
        New event
      </Button>
    )
  }
  return (
    <button
      type="button"
      aria-label="New event"
      onClick={() => sheet.open({ kind: 'create' })}
      className="fixed right-4 bottom-[calc(6.5rem+env(safe-area-inset-bottom))] z-30 grid size-14 place-items-center rounded-2xl bg-primary text-primary-foreground shadow-lg outline-none active:brightness-95 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
    >
      <Plus className="size-6" aria-hidden="true" />
    </button>
  )
}
