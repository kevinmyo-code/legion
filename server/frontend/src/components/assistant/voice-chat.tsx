import { ArrowUp, ChevronDown, CloudOff, MicOff, Minus, RefreshCw, TriangleAlert, X } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent, type RefObject } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { Bubble, recordingNotice, SessionLine, statusWords } from './chat'
import { orbMode } from './orb'
import { VoiceOrb } from './voice-orb'

/**
 * The conversation with voice as the star (Kevin, 2026-11-04: "the voice chat
 * should be the star... a ball avatar that animates as its thinking or idle...
 * an optional chat pop up below it"). The recruiter page's dock, opted into
 * with `ChatCopy.voiceFirst`; the member chat (`AssistantChat`) is untouched.
 *
 * The orb IS the talk button: tap to talk, tap again to stop, tap while the
 * companion speaks to interrupt. It calls `client.startVoice()` straight from
 * the tap, which is the only moment an iPhone opens a microphone. Voice never
 * starts on its own: that needs the tap, and each voice conversation reserves
 * demo budget.
 *
 * Typing is folded under the orb, never removed (ADR 0035: voice is the way that
 * FAILS). It opens by itself when voice fails (a blocked mic, Google refusing the
 * visitor's location) and on "/", and a failure notice sits above the orb, never
 * inside the folded part, so it is seen either way.
 *
 * The same panel is the phone's sheet on that page (Kevin, 2026-11-04: "can we
 * do the mobile view as well?"), with the sheet's Minimise and Close.
 *
 * The same rules as `AssistantChat`: no tool name or JSON on screen, failures
 * are sentences, the state is in words beside the orb (`statusWords`), and the
 * recording notice is always visible.
 */
export function VoiceFirstChat({
  layout,
  inputRef,
  onMinimise,
  onClose,
}: {
  layout: 'sheet' | 'panel'
  inputRef: RefObject<HTMLInputElement | null>
  /** Sheet only: hide the sheet, keep the conversation going. */
  onMinimise?: () => void
  /** Sheet only: close and clear the conversation. */
  onClose?: () => void
}) {
  const { client, state, name, copy, ui } = useAssistant()
  const intro = copy?.intro ?? 'Tap the circle to talk, or type below.'
  const suggestions = copy?.suggestions ?? []
  const subtitle = copy?.subtitle ?? 'Household assistant'
  const [chatOpen, setChatOpen] = useState(false)
  const [draft, setDraft] = useState('')
  const scroller = useRef<HTMLDivElement>(null)
  const chatId = useId()

  const ended = state.phase === 'ended'
  const voiceOn = state.mic !== 'off'
  const speaking = state.activity === 'speaking'
  const mode = orbMode(state)

  // Voice failed: typing is the way left, so show it. "/" asks for the box too.
  useEffect(() => {
    if (state.notice) setChatOpen(true)
  }, [state.notice])
  useEffect(() => {
    if (ui.focusTick > 0) setChatOpen(true)
  }, [ui.focusTick])
  useEffect(() => {
    if (chatOpen) inputRef.current?.focus()
  }, [chatOpen, ui.focusTick, inputRef])
  useEffect(() => {
    const el = scroller.current
    if (el) el.scrollTop = el.scrollHeight
  }, [state.lines, state.activity, chatOpen])

  const onOrb = () => {
    if (ended) return
    if (speaking) client.interrupt()
    else if (voiceOn) client.stopVoice()
    else void client.startVoice()
  }
  const orbLabel = ended
    ? `${name}: this conversation ended`
    : speaking
      ? `Interrupt ${name}`
      : voiceOn
        ? 'Stop talking. Typing continues.'
        : `Talk to ${name}`
  const words = ended ? (state.ended ?? 'This conversation ended.') : statusWords(state, name) || 'Tap to talk'
  const live = [...state.lines].reverse().find((line) => (line.kind === 'me' || line.kind === 'ai') && line.open)
  const caption = live && (live.kind === 'me' || live.kind === 'ai') ? live.text : ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (client.send(draft)) setDraft('')
  }
  const reset = () => {
    client.reset()
    setDraft('')
  }

  return (
    <div data-chat="root" data-voice-first="" className="flex h-full min-h-0 flex-col">
      <header data-chat="header" className="flex flex-none items-center gap-3 px-4 pt-2 pb-2">
        <div className="min-w-0 flex-1">
          <p data-chat="name" className="truncate leading-tight font-semibold">
            {name}
          </p>
          <p data-chat="subtitle" className="text-[0.8125rem] text-muted-foreground">
            {subtitle}
          </p>
        </div>
        {onMinimise && (
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label="Minimise. The conversation keeps going."
            onClick={onMinimise}
          >
            <Minus className="size-5" />
          </Button>
        )}
        {onClose && (
          <Button type="button" variant="ghost" size="icon-sm" aria-label="Close. This clears the conversation." onClick={onClose}>
            <X className="size-5" />
          </Button>
        )}
      </header>

      {state.notice && (
        <div role="alert" data-chat="alert"
          className="mx-3 mb-2 flex flex-none gap-2 rounded-2xl bg-destructive-container p-3 text-sm text-destructive-container-foreground">
          {state.notice.kind === 'engine-unreachable' ? (
            <CloudOff className="mt-0.5 size-4 flex-none" aria-hidden="true" />
          ) : state.notice.kind.startsWith('mic') ? (
            <MicOff className="mt-0.5 size-4 flex-none" aria-hidden="true" />
          ) : (
            <TriangleAlert className="mt-0.5 size-4 flex-none" aria-hidden="true" />
          )}
          <p>{state.notice.text}</p>
        </div>
      )}

      <section
        aria-label="Voice"
        data-chat="stage"
        className={cn(
          'flex min-h-0 flex-col items-center justify-center px-4 text-center',
          chatOpen ? 'flex-none py-2' : 'flex-1',
        )}
      >
        <button
          type="button"
          onClick={onOrb}
          disabled={ended}
          aria-label={orbLabel}
          data-chat="orb"
          data-mode={mode}
          className={cn(
            'aspect-square rounded-full outline-none transition-[width] duration-300 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-primary disabled:cursor-default',
            chatOpen
              ? layout === 'sheet'
                ? 'w-[min(112px,30%)]'
                : 'w-[min(180px,50%)]'
              : 'w-[min(300px,80%)]',
          )}
        >
          <VoiceOrb mode={mode} levels={() => client.levels()} className="block size-full" />
        </button>
        <p role="status" data-chat="status" className="mt-2 min-h-[1.5em] font-semibold">
          {words}
        </p>
        {/* With the chat open the transcript says it; the caption would say it twice. */}
        {!chatOpen && (
          <p data-chat="caption" className="line-clamp-2 min-h-[3em] max-w-[30ch] text-sm text-muted-foreground italic">
            {caption}
          </p>
        )}
        <div data-chat="actions" className="flex min-h-11 items-center gap-4">
          {speaking && (
            <button type="button" data-chat="action" onClick={() => client.interrupt()}>
              Interrupt
            </button>
          )}
          {voiceOn && (
            <button type="button" data-chat="action" onClick={() => client.stopVoice()}>
              Stop
            </button>
          )}
          {ended && (
            <Button type="button" size="sm" onClick={reset}>
              <RefreshCw />
              New conversation
            </Button>
          )}
        </div>
      </section>

      <div data-chat="drawer" className={cn('flex min-h-0 flex-col', chatOpen ? 'flex-1' : 'flex-none')}>
        <button
          type="button"
          aria-expanded={chatOpen}
          aria-controls={chatId}
          onClick={() => setChatOpen((open) => !open)}
          data-chat="toggle"
          className="flex min-h-11 flex-none items-center justify-between px-4 text-left text-sm"
        >
          {chatOpen ? 'Hide the chat' : 'Type instead'}
          <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform', chatOpen && 'rotate-180')} />
        </button>
        {chatOpen && (
          <div id={chatId} className="flex min-h-0 flex-1 flex-col">
            <div
              ref={scroller}
              role="log"
              aria-label={`Conversation with ${name}`}
              aria-live="polite"
              data-chat="log"
              className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto px-4 pb-3"
            >
              {state.lines.length === 0 ? (
                <div data-chat="intro" className="pb-1">
                  <p className="text-sm text-muted-foreground">{intro}</p>
                  <div data-chat="suggestions" className="mt-3 flex flex-wrap gap-2">
                    {suggestions.map((text) => (
                      <button
                        key={text}
                        type="button"
                        onClick={() => {
                          client.send(text)
                          inputRef.current?.focus()
                        }}
                        disabled={ended}
                        data-chat="suggestion"
                        className="min-h-11 rounded-full border border-outline px-4 text-left text-sm hover:bg-surface-2"
                      >
                        {text}
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <>
                  <SessionLine layout={layout} closable={onClose !== undefined} />
                  {state.lines.map((line) => (
                    <Bubble key={line.id} line={line} name={name} />
                  ))}
                </>
              )}
            </div>
            <form onSubmit={submit} data-chat="form" className="flex flex-none items-center gap-2 px-3 pt-1 pb-2">
              <label
                data-chat="input"
                className="flex h-12 min-w-0 flex-1 items-center rounded-full bg-surface-3 pr-1 pl-4 focus-within:ring-2 focus-within:ring-primary"
              >
                <span className="sr-only">Message {name}</span>
                <input
                  ref={inputRef}
                  value={draft}
                  onChange={(event) => setDraft(event.target.value)}
                  placeholder={ended ? 'This conversation ended' : `Message ${name}`}
                  disabled={ended}
                  enterKeyHint="send"
                  autoComplete="off"
                  className="h-full min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed"
                />
                {draft.trim() && (
                  <Button type="submit" size="icon-sm" aria-label="Send" className="size-10">
                    <ArrowUp className="size-5" />
                  </Button>
                )}
              </label>
            </form>
            {state.lines.length > 0 && !ended && (
              <div data-chat="drawer-foot" className="flex flex-none px-4 pb-1">
                <button type="button" data-chat="action" onClick={reset} className="text-sm">
                  New conversation
                </button>
              </div>
            )}
          </div>
        )}
      </div>
      <p data-chat="notice" className="flex-none px-4 pb-2 text-center text-[0.75rem] text-muted-foreground">
        {recordingNotice(name)}
      </p>
    </div>
  )
}
