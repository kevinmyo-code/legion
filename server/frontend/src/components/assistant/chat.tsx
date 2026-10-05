import {
  ArrowUp,
  Check,
  CloudOff,
  Lock,
  Mic,
  MicOff,
  Minus,
  RefreshCw,
  Sparkles,
  Square,
  TriangleAlert,
  Volume2,
  X,
} from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent, type RefObject } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import type { AssistantState, Line } from '@/assistant/live-client'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * The conversation itself, shared by the family sheet and the workbench's docked
 * panel (web-assistant 05, Kevin's variant C): a transcript, a message box that
 * is the default, and a mic that switches the same conversation to live voice.
 *
 * Rules this screen keeps, each of them a CLAUDE.md or ticket ruling:
 *  - The companion's name is the engine's, never typed into this file.
 *  - A tool line says `Done:` only after the engine said `success: true`; every
 *    other result says `Did not run:` and the engine's words. Colour is never
 *    the only signal: both carry a word and an icon.
 *  - Failures, an ended conversation, a blocked microphone are sentences, not a
 *    silent dead button.
 *  - "Not saved" is on screen, because it is true and because closing clears it.
 */

const SUGGESTIONS = ["What's on today?", 'When did we last buy shampoo?', "What's on the Groceries list?"]

export function AssistantChat({
  layout,
  inputRef,
  onMinimise,
  onClose,
}: {
  layout: 'sheet' | 'panel'
  inputRef: RefObject<HTMLInputElement | null>
  /** Sheet only: hide the sheet, keep the conversation going. */
  onMinimise?: () => void
  /** Close and clear the conversation. */
  onClose: () => void
}) {
  const { client, state, name } = useAssistant()
  const [draft, setDraft] = useState('')
  const scroller = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = scroller.current
    if (el) el.scrollTop = el.scrollHeight
  }, [state.lines, state.activity, state.ended])

  const ended = state.phase === 'ended'
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (client.send(draft)) setDraft('')
  }
  const ask = (text: string) => {
    client.send(text)
    inputRef.current?.focus()
  }
  const reset = () => {
    client.reset()
    setDraft('')
    inputRef.current?.focus()
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex flex-none items-center gap-3 px-4 pt-2 pb-2">
        <span
          aria-hidden="true"
          className="grid size-9 flex-none place-items-center rounded-full bg-primary-container font-semibold text-primary-container-foreground"
        >
          {name.slice(0, 1).toUpperCase()}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate leading-tight font-semibold">{name}</p>
          <p className="text-[0.8125rem] text-muted-foreground">Household assistant</p>
        </div>
        {state.lines.length > 0 && (
          <Button type="button" variant="secondary" size="sm" onClick={reset}>
            <RefreshCw />
            New conversation
          </Button>
        )}
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
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Close. This clears the conversation."
          onClick={onClose}
        >
          <X className="size-5" />
        </Button>
      </header>

      {state.notice && (
        <div
          role="alert"
          className="mx-3 mb-2 flex flex-none gap-2 rounded-2xl bg-destructive-container p-3 text-sm text-destructive-container-foreground"
        >
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

      <div
        ref={scroller}
        role="log"
        aria-label={`Conversation with ${name}`}
        aria-live="polite"
        className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto px-4 pb-3"
      >
        {state.lines.length === 0 ? (
          <div className="pt-4 pb-1">
            <h2 className="text-[1.375rem]">Ask {name}</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              About the calendar, the lists, or what was bought. Type, or tap the mic to talk.
            </p>
            <SessionLine layout={layout} />
            <div className="mt-3 flex flex-wrap gap-2">
              {SUGGESTIONS.map((text) => (
                <button
                  key={text}
                  type="button"
                  onClick={() => ask(text)}
                  disabled={ended}
                  className="min-h-11 rounded-full border border-outline px-4 text-left text-sm hover:bg-surface-2"
                >
                  {text}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <>
            <SessionLine layout={layout} />
            {state.lines.map((line) => (
              <Bubble key={line.id} line={line} />
            ))}
          </>
        )}
        {!ended && state.activity === 'thinking' && state.mic === 'off' && (
          <p role="status" className="px-1 py-1 text-sm text-muted-foreground">
            {state.phase === 'connecting' ? `Connecting to ${name}…` : `${name} is checking…`}
          </p>
        )}
        {ended && state.ended && (
          <div role="status" className="flex flex-col items-center gap-2 py-2 text-center text-sm">
            <p className="font-medium">{state.ended}</p>
            <p className="text-muted-foreground">What was said stays here until you close this. It is not saved.</p>
            <Button type="button" size="sm" onClick={reset}>
              <RefreshCw />
              New conversation
            </Button>
          </div>
        )}
      </div>

      {state.mic !== 'off' && <VoiceStage state={state} name={name} />}

      <form onSubmit={submit} className="flex flex-none items-center gap-2 px-3 pt-1 pb-3">
        <label className="flex h-12 min-w-0 flex-1 items-center rounded-full bg-surface-3 pr-1 pl-4 focus-within:ring-2 focus-within:ring-primary">
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
        {state.mic === 'off' ? (
          <Button
            type="button"
            size="icon-lg"
            aria-label={`Talk to ${name}`}
            disabled={ended}
            onClick={() => void client.startVoice()}
          >
            <Mic className="size-5" />
          </Button>
        ) : (
          <Button
            type="button"
            size="icon-lg"
            variant="secondary"
            aria-label="Stop talking. Typing continues."
            onClick={() => client.stopVoice()}
          >
            <Square className="size-4" />
          </Button>
        )}
      </form>
    </div>
  )
}

function SessionLine({ layout }: { layout: 'sheet' | 'panel' }) {
  return (
    <p className="flex items-center justify-center gap-1.5 py-1 text-center text-[0.8125rem] text-muted-foreground">
      <Lock className="size-3.5 flex-none" aria-hidden="true" />
      {layout === 'sheet'
        ? 'Not saved. Closing this, or a screen lock, ends and clears it.'
        : 'Not saved. Closing this clears it.'}
    </p>
  )
}

function Bubble({ line }: { line: Line }) {
  if (line.kind === 'tool') {
    return (
      <div
        role="status"
        className={cn(
          'flex max-w-[92%] items-start gap-2 self-start rounded-2xl px-3 py-2 text-[0.84375rem]',
          line.ok
            ? 'bg-done-container text-foreground'
            : 'bg-destructive-container text-destructive-container-foreground',
        )}
      >
        {line.ok ? (
          <Check className="mt-0.5 size-4 flex-none" aria-hidden="true" />
        ) : (
          <TriangleAlert className="mt-0.5 size-4 flex-none" aria-hidden="true" />
        )}
        <span>{line.text}</span>
      </div>
    )
  }
  if (line.kind === 'sys') {
    return <p className="self-center px-2 text-center text-[0.8125rem] text-muted-foreground">{line.text}</p>
  }
  const mine = line.kind === 'me'
  return (
    <div
      className={cn(
        'max-w-[84%] rounded-[1.25rem] px-3.5 py-2.5 text-[0.9375rem]',
        mine
          ? 'self-end rounded-br-md bg-primary text-primary-foreground'
          : 'self-start rounded-bl-md bg-surface-2 text-foreground',
      )}
    >
      {line.text}
      {line.kind === 'ai' && line.cut && <span className="text-muted-foreground italic"> (cut off)</span>}
      {(mine || line.via === 'spoken') && (
        <span className="mt-1 flex items-center gap-1 text-[0.71875rem] opacity-85">
          {mine ? (
            line.via === 'spoken' ? (
              <>
                <Mic className="size-3" aria-hidden="true" />
                said
              </>
            ) : (
              'typed'
            )
          ) : (
            <>
              <Volume2 className="size-3" aria-hidden="true" />
              spoken
            </>
          )}
        </span>
      )}
    </div>
  )
}

/** What voice is doing right now, in words (the orb alone would be a colour). */
export function statusWords(state: AssistantState, name: string): string {
  if (state.mic === 'starting') return 'Opening the microphone…'
  if (state.phase === 'connecting') return `Connecting to ${name}…`
  switch (state.activity) {
    case 'speaking':
      return `${name} is speaking`
    case 'thinking':
      return `${name} is checking…`
    case 'listening':
      return 'Listening'
    default:
      return state.mic === 'on' ? 'Listening' : ''
  }
}

function VoiceStage({ state, name }: { state: AssistantState; name: string }) {
  const { client } = useAssistant()
  const words = statusWords(state, name)
  const caption = [...state.lines].reverse().find((line) => (line.kind === 'me' || line.kind === 'ai') && line.open)
  const pulsing = state.activity === 'listening' || state.activity === 'speaking'
  return (
    <section
      aria-label="Voice conversation"
      className="mx-3 mb-2 flex flex-none items-center gap-3 rounded-3xl bg-surface-1 p-3"
    >
      <span
        aria-hidden="true"
        className={cn(
          'relative grid size-14 flex-none place-items-center rounded-full bg-primary text-primary-foreground',
          pulsing && 'motion-safe:animate-pulse',
        )}
      >
        {state.activity === 'speaking' ? (
          <Volume2 className="size-6" />
        ) : state.activity === 'thinking' ? (
          <Sparkles className="size-6" />
        ) : (
          <Mic className="size-6" />
        )}
      </span>
      <div className="min-w-0 flex-1">
        <p role="status" className="font-semibold">
          {words}
        </p>
        {caption && <p className="line-clamp-2 text-sm text-muted-foreground">{caption.kind !== 'sys' && caption.kind !== 'tool' ? caption.text : ''}</p>}
      </div>
      {state.activity === 'speaking' && (
        <Button type="button" variant="secondary" size="sm" onClick={() => client.interrupt()}>
          Interrupt
        </Button>
      )}
    </section>
  )
}
