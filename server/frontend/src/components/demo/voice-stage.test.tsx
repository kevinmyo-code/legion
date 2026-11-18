import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, test } from 'vitest'

import { AssistantProvider } from '@/assistant/assistant-context'
import type { CascadeDeps, TurnStart } from '@/assistant/cascade-client'
import { pcm16ToBase64 } from '@/assistant/pcm'
import type { SseEvent } from '@/assistant/sse'
import { AssistantModals } from '@/components/assistant/assistant-modals'
import { DIAGRAM_CARD } from '@/test/cards-seed'
import { FakeAudio, settle } from '@/test/fake-live'

import { QUESTION_CHIPS, VoiceStage } from './voice-stage'

/**
 * The browser run (2026-11-17): an answer that opened a diagram covered the orb, so a tap to stop
 * Alfred never landed. The dialog carries its own control for that, present whenever he is
 * thinking or speaking; the orb is back as soon as the dialog closes (backdrop, Esc, X).
 */

afterEach(() => {
  window.history.replaceState({}, '')
})

function stage(withAudio: boolean) {
  const audio = new FakeAudio()
  const cancels: string[] = []
  async function* answer(): AsyncGenerator<SseEvent> {
    yield { type: 'turn', data: { conversation_id: 'c-1', turn: 0 } }
    yield { type: 'tool', data: { name: 'show_diagram', ok: true, text: 'Shown.', card: DIAGRAM_CARD } }
    yield { type: 'sentence', data: { text: 'Here is how it fits together.' } }
    if (withAudio) yield { type: 'audio', data: { pcm: pcm16ToBase64(new Int16Array(4800)), rate: 24000 } }
    await new Promise(() => {})
  }
  const deps: CascadeDeps = {
    startTurn: async (): Promise<TurnStart> => ({ ok: true, events: answer() }),
    cancelTurn: (id) => cancels.push(id),
    audio: () => audio,
    now: () => Date.now(),
    utcOffsetMinutes: () => 0,
  }
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AssistantProvider voiceFirst={{ prepare: async () => true }} cascadeDeps={deps}>
        <AssistantModals>
          <VoiceStage />
        </AssistantModals>
      </AssistantProvider>
    </QueryClientProvider>,
  )
  return { audio, cancels }
}

describe('the voice stage with a card open over it', () => {
  test.each([
    ['thinking', false],
    ['speaking', true],
  ])('while %s, the dialog has a control that stops him in one tap and cancels the turn', async (_when, withAudio) => {
    const { audio, cancels } = stage(withAudio)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: QUESTION_CHIPS[1] }))
      await settle()
    })
    const dialog = await screen.findByRole('dialog')
    const flushes = audio.flushes
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Interrupt' }))
      await settle()
    })
    expect(cancels).toHaveLength(1)
    expect(audio.flushes).toBe(flushes + 1)
    expect(within(dialog).queryByRole('button', { name: 'Interrupt' })).not.toBeInTheDocument()
  })
})
