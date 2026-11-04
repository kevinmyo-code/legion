import {
  ArrowUp,
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

import { UNNAMED, useAssistant } from '@/assistant/assistant-context'
import type { AssistantState, Line } from '@/assistant/live-client'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { ToolCard } from './cards/card'
import type { Card } from './cards/types'

/**
 * The conversation itself, shared by the family sheet and the workbench's docked
 * panel (web-assistant 05, Kevin's variant C): a transcript, a message box that
 * is the default, and a mic that switches the same conversation to live voice.
 *
 * Rules this screen keeps, each of them a CLAUDE.md or ticket ruling:
 *  - The companion's name is the engine's, never typed into this file.
 *  - No tool name, argument, result or JSON is ever on screen (Kevin,
 *    2026-11-03: "i can see tool calls and code"). The engine's tool text is
 *    written for the model, often a JSON dump, so it stays in the client and the
 *    engine's conversation record. While a tool runs the status says
 *    "<name> is checking…"; a success shows nothing (the reply says it); a
 *    failure is one neutral, centred sentence: `TOOL_FAILED`.
 *  - A tool's card (`cards/`) is the one thing of a tool's on screen: the
 *    engine built it from the tool's rows for the screen, never from model
 *    text, and it carries no tool name or argument.
 *  - Failures, an ended conversation, a blocked microphone are sentences, not a
 *    silent dead button.
 *  - What closing does is on screen: it clears the conversation from the screen.
 *  - "May be recorded to improve the assistant" sits beside the mic, for a
 *    member and a recruiter alike (Kevin, 2026-11-03): the engine keeps a record
 *    of each conversation's words and tool calls, never the audio.
 *
 * `data-chat="..."` marks the parts a page may restyle (the recruiter page's
 * transcript look, `.recruiter [data-chat]` in `index.css`). They are stable
 * hooks: rename one and that page's styles silently stop applying. The speaker
 * label on each turn is `hidden` here and shown only by such a page, so the
 * member chat looks exactly as it did.
 */

/** The recording notice. The companion's name is the engine's; the wording says "improve", never "train". */
export function recordingNotice(name: string): string {
  // Before the engine has named the companion there is no name to say.
  if (name === UNNAMED) return 'Conversations with the assistant may be recorded to improve it.'
  return `Conversations with ${name} may be recorded to improve the assistant.`
}

/**
 * The household's first questions. The one set: the briefing's ask bar that
 * carried its own is gone (Kevin, 2026-11-03: one place to type).
 */
const SUGGESTIONS = [
  "What's on today?",
  'What did we spend on groceries?',
  'When is the next car service due?',
  "What's on the Groceries list?",
]

export function AssistantChat({
  layout,
  inputRef,
  onMinimise,
  onClose,
  subtitle: subtitleProp,
  intro: introProp,
  suggestions: suggestionsProp,
}: {
  layout: 'sheet' | 'panel'
  inputRef: RefObject<HTMLInputElement | null>
  /** Sheet only: hide the sheet, keep the conversation going. */
  onMinimise?: () => void
  /** Close and clear the conversation. Absent where the chat is part of the page and cannot close. */
  onClose?: () => void
  /** The line under the companion's name. */
  subtitle?: string
  /** What the empty conversation says it can be asked about. */
  intro?: string
  /** The tappable first questions. */
  suggestions?: readonly string[]
}) {
  const { client, state, name, copy } = useAssistant()
  // A prop wins, then the page's copy (`AssistantProvider copy`), then the household's.
  const subtitle = subtitleProp ?? copy?.subtitle ?? 'Household assistant'
  const intro =
    introProp ?? copy?.intro ?? 'About the calendar, the lists, or what was bought. Type, or tap the mic to talk.'
  const suggestions = suggestionsProp ?? copy?.suggestions ?? SUGGESTIONS
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
    <div data-chat="root" className="flex h-full min-h-0 flex-col">
      <header data-chat="header" className="flex flex-none items-center gap-3 px-4 pt-2 pb-2">
        <span
          aria-hidden="true"
          data-chat="avatar"
          className="grid size-9 flex-none place-items-center rounded-full bg-primary-container font-semibold text-primary-container-foreground"
        >
          {name.slice(0, 1).toUpperCase()}
        </span>
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
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label="Close. This clears the conversation."
            onClick={onClose}
          >
            <X className="size-5" />
          </Button>
        )}
      </header>

      {state.lines.length > 0 && (
        <div className="flex flex-none px-4 pb-1 pl-16">
          <Button type="button" variant="secondary" size="sm" onClick={reset}>
            <RefreshCw />
            New conversation
          </Button>
        </div>
      )}

      {state.notice && (
        <div
          role="alert"
          data-chat="alert"
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
        data-chat="log"
        className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto px-4 pb-3"
      >
        {state.lines.length === 0 ? (
          <div data-chat="intro" className="pt-4 pb-1">
            <h2 className="text-[1.375rem]">Ask {name}</h2>
            <p className="mt-1 text-sm text-muted-foreground">{intro}</p>
            <SessionLine layout={layout} closable={onClose !== undefined} />
            <div data-chat="suggestions" className="mt-3 flex flex-wrap gap-2">
              {suggestions.map((text) => (
                <button
                  key={text}
                  type="button"
                  onClick={() => ask(text)}
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
        {!ended && state.activity === 'thinking' && state.mic === 'off' && (
          <p role="status" className="px-1 py-1 text-sm text-muted-foreground">
            {state.phase === 'connecting' ? `Connecting to ${name}…` : `${name} is checking…`}
          </p>
        )}
        {ended && state.ended && (
          <div role="status" className="flex flex-col items-center gap-2 py-2 text-center text-sm">
            <p className="font-medium">{state.ended}</p>
            <p className="text-muted-foreground">What was said stays on this screen until you close this.</p>
            <Button type="button" size="sm" onClick={reset}>
              <RefreshCw />
              New conversation
            </Button>
          </div>
        )}
      </div>

      {state.mic !== 'off' && <VoiceStage state={state} name={name} />}

      <form onSubmit={submit} data-chat="form" className="flex flex-none items-center gap-2 px-3 pt-1 pb-3">
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
        {state.mic === 'off' ? (
          <Button
            type="button"
            size="icon-lg"
            data-chat="mic"
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
            data-chat="mic"
            aria-label="Stop talking. Typing continues."
            onClick={() => client.stopVoice()}
          >
            <Square className="size-4" />
          </Button>
        )}
      </form>
      <p data-chat="notice" className="flex-none px-4 pb-2 text-center text-[0.75rem] text-muted-foreground">
        {recordingNotice(name)}
      </p>
    </div>
  )
}

export function SessionLine({ layout, closable }: { layout: 'sheet' | 'panel'; closable: boolean }) {
  return (
    <p className="flex items-center justify-center gap-1.5 py-1 text-center text-[0.8125rem] text-muted-foreground">
      <Lock className="size-3.5 flex-none" aria-hidden="true" />
      {!closable
        ? 'Leaving this page clears it from the screen.'
        : layout === 'sheet'
          ? 'Closing this, or a screen lock, ends it and clears it from the screen.'
          : 'Closing this clears it from the screen.'}
    </p>
  )
}

/** What a failed tool call says on screen: no tool name, no engine words. */
export const TOOL_FAILED = "Couldn't check that."

export function Bubble({ line, name }: { line: Line; name: string }) {
  if (line.kind === 'tool') {
    // `line.text` is the engine's text for the model (names, args, JSON). Never rendered.
    // The card is the engine's own drawing of the tool's rows, so it is shown where the tool ran.
    if (line.card) return <ToolCard card={line.card} />
    if (line.ok) return null
    return (
      <p role="status" className="flex items-center gap-1.5 self-center px-2 text-center text-[0.8125rem] text-muted-foreground">
        <TriangleAlert className="size-3.5 flex-none" aria-hidden="true" />
        {TOOL_FAILED}
      </p>
    )
  }
  if (line.kind === 'sys') {
    return <p className="self-center px-2 text-center text-[0.8125rem] text-muted-foreground">{line.text}</p>
  }
  const mine = line.kind === 'me'
  return (
    <div
      data-chat={mine ? 'bubble-me' : 'bubble-ai'}
      className={cn(
        'max-w-[84%] rounded-[1.25rem] px-3.5 py-2.5 text-[0.9375rem]',
        mine
          ? 'self-end rounded-br-md bg-primary text-primary-foreground'
          : 'self-start rounded-bl-md bg-surface-2 text-foreground',
      )}
    >
      <span data-chat="speaker" className="hidden">
        {mine ? 'You' : name}
      </span>
      {line.text}
      {line.kind === 'ai' && line.cut && <span className="text-muted-foreground italic"> (cut off)</span>}
      {(mine || line.via === 'spoken') && (
        <span data-chat="via" className="mt-1 flex items-center gap-1 text-[0.71875rem] opacity-85">
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

/**
 * The newest card of the current exchange, for a stage that shows one at a time:
 * the latest tool line's card, unless the person has spoken or typed since (a new
 * question clears the stage until its own answer brings a card, if any).
 */
export function latestCard(lines: readonly Line[]): Card | undefined {
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i]
    if (line.kind === 'tool' && line.card) return line.card
    if (line.kind === 'me') return undefined
  }
  return undefined
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
