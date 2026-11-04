import { Mic, Sparkles, Volume2 } from 'lucide-react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { useEffect, useRef, useState } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import { AssistantChat, statusWords } from '@/components/assistant/chat'
import { orbMode } from '@/components/assistant/orb'
import { VoiceFirstChat } from '@/components/assistant/voice-chat'
import { VoiceOrb } from '@/components/assistant/voice-orb'

/**
 * The family view's assistant (web-assistant 05, variant C): a floating orb on
 * every screen, above the tab bar, that opens the chat as a full-height sheet
 * with the keyboard up. **It opens to typing**, not voice (Kevin's one change to
 * the prototype); the mic inside the sheet is the tap an iPhone needs before a
 * microphone may open.
 *
 * Minimising keeps the conversation: the sheet closes, the client lives on in
 * `AssistantProvider`, and the orb wears a status in words ("Dorothy is
 * listening. Tap to see.") so a conversation in progress is never invisible.
 * Closing with the X is the one thing that clears it.
 *
 * `launcher="bar"` (the recruiter page, which has no tab bar): in place of the
 * orb, one full-width "Ask" bar pinned to the bottom of the page. It is a
 * button, not a text field, so the sheet's message box stays the one place to
 * type (Kevin, 2026-11-03).
 */
export function FamilyAssistant({ launcher = 'orb' }: { launcher?: 'orb' | 'bar' } = {}) {
  const { client, state, name, ui, copy } = useAssistant()
  // The recruiter page's sheet is voice first, like its desk dock (Kevin, 2026-11-04).
  const voiceFirst = launcher === 'bar' && copy?.voiceFirst === true
  const inputRef = useRef<HTMLInputElement>(null)
  const frame = useVisualViewport(ui.open)

  const status = statusWords(state, name)
  const tag =
    state.phase === 'ended'
      ? 'Conversation ended. Tap to see.'
      : status && (state.mic !== 'off' || state.phase !== 'idle')
        ? `${status.replace(/…$/, '')}. Tap to see.`
        : null
  const live = state.phase !== 'idle' && state.phase !== 'ended'
  const Icon = state.activity === 'speaking' ? Volume2 : state.mic !== 'off' ? Mic : Sparkles

  return (
    <DialogPrimitive.Root open={ui.open} onOpenChange={ui.setOpen}>
      {!ui.open && launcher === 'bar' && (
        <div className="sticky bottom-0 z-30 bg-background/95 px-4 pt-2 pb-[calc(0.75rem+env(safe-area-inset-bottom))] backdrop-blur">
          <button
            type="button"
            onClick={() => ui.setOpen(true)}
            aria-label={live ? `Open the conversation with ${name}` : voiceFirst ? `Talk to ${name}` : `Ask ${name}`}
            data-chat="askbar"
            className="mx-auto flex min-h-12 w-full max-w-[880px] items-center gap-2.5 rounded-full bg-card py-1.5 pr-1.5 pl-2 text-left shadow-md outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
          >
            {voiceFirst ? (
              // The same orb as the sheet, small and live: the conversation's state at a glance.
              <span aria-hidden="true" data-chat="bar-orb" className="grid size-10 shrink-0 place-items-center">
                <VoiceOrb mode={orbMode(state)} levels={() => client.levels()} points={260} dot={0.7} className="size-10" />
              </span>
            ) : (
              <span
                aria-hidden="true"
                className="grid size-10 shrink-0 place-items-center rounded-full bg-primary-container text-primary-container-foreground"
              >
                <Icon className="size-[18px]" />
              </span>
            )}
            <span className="min-w-0 flex-1 truncate text-[0.9375rem] text-muted-foreground">
              {tag ?? (voiceFirst ? `Talk to ${name}, or type` : `Ask ${name}…`)}
            </span>
            <span aria-hidden="true" className="grid h-10 place-items-center rounded-full bg-primary px-5 text-sm font-medium text-primary-foreground">
              {voiceFirst ? 'Talk' : 'Ask'}
            </span>
          </button>
        </div>
      )}
      {!ui.open && launcher === 'orb' && (
        <>
          <button
            type="button"
            onClick={() => ui.setOpen(true)}
            aria-label={live ? `Open the conversation with ${name}` : `Ask ${name}`}
            className="fixed right-4 bottom-[calc(6.25rem+env(safe-area-inset-bottom))] z-30 grid size-14 place-items-center rounded-full bg-primary text-primary-foreground shadow-lg outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
          >
            {live && state.activity !== 'idle' && (
              <span
                aria-hidden="true"
                className="absolute -inset-1.5 rounded-full border-[3px] border-primary/50 motion-safe:animate-ping"
              />
            )}
            <Icon className="size-6" aria-hidden="true" />
          </button>
          {tag && (
            <p
              role="status"
              className="fixed right-[5.25rem] bottom-[calc(6.5rem+env(safe-area-inset-bottom))] z-30 max-w-[14rem] rounded-2xl bg-foreground px-3 py-2 text-[0.84375rem] text-background"
            >
              {tag}
            </p>
          )}
        </>
      )}

      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-scrim" />
        <DialogPrimitive.Content
          aria-describedby={undefined}
          onOpenAutoFocus={(event) => {
            // Typing is the default: the keyboard comes up with the sheet. The
            // sheet is opened by a tap, which is the gesture iOS needs to show it.
            // The voice-first sheet opens to the orb, not the keyboard: the chat is folded.
            event.preventDefault()
            if (!voiceFirst) inputRef.current?.focus()
          }}
          style={frame}
          // The sheet is portalled out of the page, so the recruiter page's look
          // (`.recruiter`, the only page with the bar) is put back on it here.
          className={`fixed inset-x-0 top-0 z-50 flex flex-col bg-background pt-[env(safe-area-inset-top)] outline-none${
            launcher === 'bar' ? ' recruiter' : ''
          }`}
        >
          <DialogPrimitive.Title className="sr-only">Conversation with {name}</DialogPrimitive.Title>
          {voiceFirst ? (
            <VoiceFirstChat
              layout="sheet"
              inputRef={inputRef}
              onMinimise={() => ui.setOpen(false)}
              onClose={() => {
                client.reset()
                ui.setOpen(false)
              }}
            />
          ) : (
            <AssistantChat
              layout="sheet"
              inputRef={inputRef}
              onMinimise={() => ui.setOpen(false)}
              onClose={() => {
                client.reset()
                ui.setOpen(false)
              }}
            />
          )}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

/**
 * The sheet's box, tracking the VISIBLE viewport. On an iPhone the on-screen
 * keyboard shrinks the visual viewport but not the layout one, so a bottom-pinned
 * message box would sit behind the keyboard; sizing the sheet to `visualViewport`
 * keeps it above. Browsers without it get the full-height default. Owed on a
 * real iPhone (jsdom has no keyboard).
 */
function useVisualViewport(active: boolean): { height: string; top: string } {
  const [box, setBox] = useState({ height: '100dvh', top: '0px' })
  useEffect(() => {
    const viewport = window.visualViewport
    if (!active || !viewport) return
    const measure = () => setBox({ height: `${viewport.height}px`, top: `${viewport.offsetTop}px` })
    measure()
    viewport.addEventListener('resize', measure)
    viewport.addEventListener('scroll', measure)
    return () => {
      viewport.removeEventListener('resize', measure)
      viewport.removeEventListener('scroll', measure)
    }
  }, [active])
  return box
}
