import { Sparkles } from 'lucide-react'
import { useEffect, useRef } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import { AssistantChat, statusWords } from '@/components/assistant/chat'

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
  return (
    <button
      type="button"
      aria-expanded={ui.open}
      aria-controls="assistant-dock"
      onClick={() => (ui.open ? ui.setOpen(false) : ui.focusBox())}
      className={`flex h-12 w-full items-center gap-3 rounded-full px-4 text-left text-[0.9375rem] font-medium transition-colors ${
        ui.open ? 'bg-primary-container text-primary-container-foreground' : 'bg-surface-3 hover:bg-surface-2'
      }`}
    >
      <Sparkles className="size-5 flex-none" aria-hidden="true" />
      <span className="min-w-0 flex-1">
        <span className="block truncate">{name}</span>
        {busy && !ui.open && status && (
          <span className="block truncate text-[0.75rem] font-normal opacity-80">{status.replace(/…$/, '')}</span>
        )}
      </span>
    </button>
  )
}

export function AssistantDock() {
  const { client, name, ui } = useAssistant()
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
    if (ui.open && ui.focusTick > 0) inputRef.current?.focus()
  }, [ui.open, ui.focusTick])

  if (!ui.open) return null
  return (
    <aside
      id="assistant-dock"
      aria-label={`Conversation with ${name}`}
      className="sticky top-0 flex h-dvh w-[400px] shrink-0 flex-col border-l border-outline-variant bg-popover"
    >
      <AssistantChat
        layout="panel"
        inputRef={inputRef}
        onClose={() => {
          client.reset()
          ui.setOpen(false)
        }}
      />
    </aside>
  )
}
