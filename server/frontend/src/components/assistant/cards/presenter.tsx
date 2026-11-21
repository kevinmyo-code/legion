import { X } from 'lucide-react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'

import type { Line } from '@/assistant/live-client'
import { notePoppedModalEntry, popModalEntry, pushModalEntry } from '@/lib/modal-entry'

import { DiagramBody } from './diagram'
import { PartBody } from './part3d'
import { PresenterContext } from './presenter-context'
import { DownloadPdf, ResumeBody } from './resume'
import { ToolCard } from './card'
import { isModalCard, type Card, type ModalCard } from './types'

/**
 * The cards that open over the whole page: a diagram, the resume and a Jeep part in 3D (demo-modals, Kevin
 * 2026-11-05: "the diagrams should kinda like pop up over the whole page as a modal", "asking
 * for the resume should pop up a modal thats scrollable").
 *
 * One modal for the page, held here rather than in the card, because a card comes and goes:
 * showing a diagram again (a new view, new highlights) replaces the earlier card's line
 * (`LiveClient.replacedDiagram`), and a modal owned by that card would close with it. Here the
 * newest such card opens the modal by itself, and while it is open a newer one replaces what it
 * shows in place. The chat's thumbnail calls `show` to open it again.
 *
 * Closes on the X, Esc, a tap on the backdrop, and the phone's back gesture: opening pushes one
 * history entry (`lib/modal-entry.ts`), and the back pops it. Closing any other way goes back over
 * that entry, so it never piles up.
 *
 * The conversation keeps running underneath: nothing here touches the client. A modal dialog
 * makes the rest of the page inert, so the controls that stop or interrupt voice would be dead
 * under it; `footer` puts them inside the modal (`ModalVoiceBar`).
 */

export function ScreenModalProvider({
  lines,
  footer,
  onOpenChange,
  children,
}: {
  /** The conversation's lines: the newest diagram or resume card opens the modal. */
  lines?: readonly Line[]
  /** What the modal carries at its foot (the voice controls). */
  footer?: ReactNode
  /** Told when the modal opens and closes (the cascade's quiet window lapse under a card). */
  onOpenChange?: (open: boolean) => void
  children: ReactNode
}) {
  const [shown, setShown] = useState<Card | null>(null)
  // Cards already in the conversation when this mounted do not pop up; newer ones do.
  const seen = useRef(lastCardLineId(lines))
  useEffect(() => {
    if (!lines) return
    let newest: { id: number; card: Card } | null = null
    for (const line of lines) {
      if (line.kind === 'tool' && line.id > seen.current && line.card) newest = { id: line.id, card: line.card }
    }
    if (!newest) return
    seen.current = newest.id
    const card = newest.card
    // A diagram or the resume opens the modal. Any other card replaces what an OPEN modal shows:
    // the conversation has moved on to it, and the modal covers the page it would be drawn on
    // (the tour's live beat said "That card is a live lookup" over the last diagram, 2026-11-19).
    // With the modal closed, such a card stays where the conversation draws it.
    setShown((open) => (isModalCard(card) || open !== null ? card : open))
  }, [lines])

  const open = shown !== null
  // Idempotent on the other side, so a new callback each render is harmless.
  useEffect(() => {
    onOpenChange?.(open)
  }, [open, onOpenChange])
  const pushed = useRef(false)
  useEffect(() => {
    if (!open) return
    // Deferred a tick: React's StrictMode runs this effect, its cleanup, and it again; pushing
    // at once would have the first cleanup's back() land after the second push and close it.
    const timer = setTimeout(() => {
      pushModalEntry()
      pushed.current = true
    }, 0)
    const onPop = () => {
      if (pushed.current) notePoppedModalEntry()
      pushed.current = false
      setShown(null)
    }
    window.addEventListener('popstate', onPop)
    return () => {
      clearTimeout(timer)
      window.removeEventListener('popstate', onPop)
      if (pushed.current) {
        pushed.current = false
        popModalEntry()
      }
    }
  }, [open])

  const show = useCallback((card: ModalCard) => setShown(card), [])
  const value = useMemo(() => ({ show }), [show])
  return (
    <PresenterContext.Provider value={value}>
      {children}
      <ScreenModal card={shown} footer={footer} onClose={() => setShown(null)} />
    </PresenterContext.Provider>
  )
}

function lastCardLineId(lines?: readonly Line[]): number {
  let last = -1
  for (const line of lines ?? []) if (line.kind === 'tool' && line.card) last = line.id
  return last
}

function ScreenModal({ card, footer, onClose }: { card: Card | null; footer?: ReactNode; onClose: () => void }) {
  return (
    <DialogPrimitive.Root open={card !== null} onOpenChange={(next) => !next && onClose()}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay data-modal="backdrop" className="fixed inset-0 z-[60] bg-black/60" />
        <DialogPrimitive.Content
          data-modal={card?.card ?? 'closed'}
          aria-describedby={card?.card === 'diagram' || card?.card === 'part3d' ? 'screen-modal-caption' : undefined}
          // The phone's keyboard must not rise, and the X must not grab the eye: focus the box.
          onOpenAutoFocus={(event) => {
            event.preventDefault()
            ;(event.currentTarget as HTMLElement | null)?.focus()
          }}
          tabIndex={-1}
          className="fixed inset-x-1.5 top-[calc(0.375rem+env(safe-area-inset-top))] bottom-[calc(0.375rem+env(safe-area-inset-bottom))] z-[60] flex flex-col gap-2 rounded-2xl bg-background p-3 text-foreground shadow-xl outline-none sm:inset-x-6 sm:top-6 sm:bottom-6 sm:p-4"
        >
          {card && (
            <>
              <header className="flex flex-none items-start gap-2">
                <div className="min-w-0 flex-1">
                  {card.scope && <p className="text-[0.75rem] tracking-wide text-muted-foreground uppercase">{card.scope}</p>}
                  <DialogPrimitive.Title className="text-base leading-tight font-semibold sm:text-lg">{card.title}</DialogPrimitive.Title>
                  {(card.card === 'diagram' || card.card === 'part3d') && (
                    <DialogPrimitive.Description id="screen-modal-caption" className="mt-0.5 line-clamp-3 text-[0.8125rem] text-muted-foreground sm:line-clamp-none">
                      {card.card === 'diagram' ? card.caption : card.where}
                    </DialogPrimitive.Description>
                  )}
                </div>
                {card.card === 'resume' && <DownloadPdf href={card.pdf} className="flex-none" />}
                <DialogPrimitive.Close
                  aria-label="Close"
                  className="grid size-11 flex-none place-items-center rounded-full border border-outline-variant outline-none focus-visible:outline-2 focus-visible:outline-primary"
                >
                  <X className="size-5" aria-hidden="true" />
                </DialogPrimitive.Close>
              </header>
              {card.card === 'diagram' ? (
                <DiagramBody card={card} />
              ) : card.card === 'resume' ? (
                <ResumeBody card={card} />
              ) : card.card === 'part3d' ? (
                <PartBody card={card} />
              ) : (
                // Any other card, drawn as the conversation draws it, while the modal is open.
                <div data-modal="card" className="min-h-0 flex-1 overflow-auto">
                  <ToolCard card={card} className="max-w-none" />
                </div>
              )}
              {footer && <div className="flex-none">{footer}</div>}
            </>
          )}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
