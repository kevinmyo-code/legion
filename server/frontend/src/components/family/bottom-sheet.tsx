import { Dialog as DialogPrimitive } from 'radix-ui'
import type { ReactNode } from 'react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogDescription, DialogOverlay, DialogPortal, DialogTitle } from '@/components/ui/dialog'

/**
 * A read-only bottom sheet for the family surface: 28 px top corners, a grab
 * handle, a title, the content, and one "Close" button that stays in thumb
 * reach. The event sheet is the editable cousin of this and keeps its own
 * markup because it owns a form and a sticky footer; this is for a page that
 * only needs to show more (the spend categories), so it is a component rather
 * than a third copy of the same classes.
 *
 * Radix gives it the focus trap, Escape, and the overlay tap that closes it.
 */
export function BottomSheet({
  open,
  onOpenChange,
  title,
  description,
  children,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  /** Read by a screen reader only; the visible sentences live in `children`. */
  description: string
  children: ReactNode
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogPortal>
        <DialogOverlay />
        <DialogPrimitive.Content
          data-slot="bottom-sheet"
          className="fixed inset-x-0 bottom-0 z-50 mx-auto flex max-h-[88dvh] max-w-xl flex-col overflow-y-auto rounded-t-sheet bg-popover px-5 pt-3 pb-[calc(1.25rem+env(safe-area-inset-bottom))] text-sm text-popover-foreground shadow-lg outline-none duration-200 data-open:animate-in data-open:slide-in-from-bottom data-closed:animate-out data-closed:slide-out-to-bottom"
        >
          <div aria-hidden="true" className="mx-auto mb-3 h-1 w-9 shrink-0 rounded-full bg-outline-variant" />
          <DialogTitle className="mb-1 text-[1.375rem] leading-tight font-medium">{title}</DialogTitle>
          <DialogDescription className="sr-only">{description}</DialogDescription>
          {children}
          <div className="mt-5 flex justify-end">
            <Button variant="secondary" size="lg" onClick={() => onOpenChange(false)}>
              Close
            </Button>
          </div>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  )
}
