import type { ReactNode } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import { ScreenModalProvider } from '@/components/assistant/cards/presenter'
import { statusWords } from '@/components/assistant/chat'
import { orbMode } from '@/components/assistant/orb'
import { VoiceOrb } from '@/components/assistant/voice-orb'

/**
 * The page's one modal for diagrams and the resume, fed by the conversation (demo-modals).
 * Mounted inside `AssistantProvider` by the app shell and the recruiter page.
 */
export function AssistantModals({ children }: { children: ReactNode }) {
  const { state } = useAssistant()
  return (
    <ScreenModalProvider lines={state.lines} footer={<ModalVoiceBar />}>
      {children}
    </ScreenModalProvider>
  )
}

/**
 * The voice controls, inside the modal. The modal makes the page under it inert (the sheet's
 * orb and the dock's Stop included), so a visitor who is talking needs these here: the same
 * calls as the sheet and the dock (`voice-chat.tsx`), never a second implementation. The
 * conversation itself never stops because the modal opened.
 */
export function ModalVoiceBar() {
  const { client, state, name } = useAssistant()
  const live = state.phase === 'connecting' || state.phase === 'live'
  if (!live && state.mic === 'off') return null
  const speaking = state.activity === 'speaking'
  const voiceOn = state.mic !== 'off'
  const words = statusWords(state, name) || (voiceOn ? 'Listening' : `${name} is here`)
  return (
    <div data-modal="voice" className="flex min-h-12 items-center gap-2.5 rounded-full bg-surface-2 py-1 pr-1 pl-1.5">
      <span aria-hidden="true" className="grid size-10 flex-none place-items-center">
        <VoiceOrb mode={orbMode(state)} levels={() => client.levels()} points={260} dot={0.7} className="size-10" />
      </span>
      <p role="status" className="min-w-0 flex-1 truncate text-[0.875rem]">
        {words}
      </p>
      {speaking && (
        <button type="button" onClick={() => client.interrupt()} className="h-10 rounded-full px-3 text-sm">
          Interrupt
        </button>
      )}
      {voiceOn ? (
        <button
          type="button"
          onClick={() => client.stopVoice()}
          aria-label="Stop talking. Typing continues."
          className="h-10 rounded-full bg-primary px-4 text-sm font-medium text-primary-foreground"
        >
          Stop
        </button>
      ) : (
        <button
          type="button"
          onClick={() => void client.startVoice()}
          aria-label={`Talk to ${name}`}
          className="h-10 rounded-full bg-primary px-4 text-sm font-medium text-primary-foreground"
        >
          Talk
        </button>
      )}
    </div>
  )
}
