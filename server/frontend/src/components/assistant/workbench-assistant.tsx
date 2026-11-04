import { Sparkles } from 'lucide-react'
import { useEffect, useRef } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import { AssistantChat, statusWords } from '@/components/assistant/chat'
import { VoiceFirstChat } from '@/components/assistant/voice-chat'

/**
 * The workbench's assistant (web-assistant 05, variant C on the desk): an entry
 * in the rail named for the member's companion, opening a panel docked on the
 * right of whatever page is open. The page stays usable beside it; pressing "/"
 * anywhere that is not a text field opens the panel and focuses the message box.
 */

export function AssistantRailEntry() {
  const { state, name, ui } = useAssistant()
  const status = statusWords(state, name)
  const busy = state.phase !== 'idle' && state.phase !== 'ended'
  // The slim rail (recruiter-demo 14): icon over the companion's name, like
  // every other rail entry; a live conversation's status shows as its tooltip
  // and in words under the name while the panel is closed.
  return (
    <button
      type="button"
      aria-expanded={ui.open}
      aria-controls="assistant-dock"
      onClick={() => (ui.open ? ui.setOpen(false) : ui.focusBox())}
      className={`flex min-h-[3.375rem] w-full flex-col items-center justify-center gap-1 rounded-2xl px-0.5 py-1.5 text-center text-[0.71875rem] font-medium transition-colors outline-none focus-visible:outline-2 focus-visible:outline-primary ${
        ui.open ? 'text-foreground' : 'text-muted-foreground hover:bg-surface-3'
      }`}
    >
      <span
        aria-hidden="true"
        className={`grid h-7 w-12 place-items-center rounded-full ${ui.open ? 'bg-primary-container text-primary-container-foreground' : 'bg-surface-3'}`}
      >
        <Sparkles className="size-5" />
      </span>
      <span className="w-full truncate">{name}</span>
      {busy && !ui.open && status && (
        <span className="block w-full truncate text-[0.6875rem] font-normal opacity-80">{status.replace(/…$/, '')}</span>
      )}
    </button>
  )
}

/**
 * `pinned`: the dock is part of the page and always open, with no close (the
 * recruiter page, Kevin 2026-11-03: "keep the right panel window open at all
 * times"). "New conversation" still clears it.
 */
export function AssistantDock({ pinned = false }: { pinned?: boolean } = {}) {
  const { client, name, ui, copy } = useAssistant()
  const inputRef = useRef<HTMLInputElement>(null)

  // "/" opens the panel and takes focus, unless the person is already typing.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return
      const target = event.target as HTMLElement | null
      if (target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) return
      event.preventDefault()
      ui.focusBox()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [ui])

  // Focus the box whenever something asked for it (the rail entry, "/").
  useEffect(() => {
    if ((pinned || ui.open) && ui.focusTick > 0) inputRef.current?.focus()
  }, [pinned, ui.open, ui.focusTick])

  if (!pinned && !ui.open) return null
  return (
    <aside
      id="assistant-dock"
      aria-label={`Conversation with ${name}`}
      className="sticky top-0 flex h-dvh w-[400px] shrink-0 flex-col border-l border-outline-variant bg-popover"
    >
      {pinned && copy?.voiceFirst ? (
        <VoiceFirstChat layout="panel" inputRef={inputRef} />
      ) : (
        <AssistantChat
          layout="panel"
          inputRef={inputRef}
          onClose={
            pinned
              ? undefined
              : () => {
                  client.reset()
                  ui.setOpen(false)
                }
          }
        />
      )}
    </aside>
  )
}
