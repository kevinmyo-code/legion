import { useEffect, useRef, useState, type FormEvent } from 'react'

import type { DemoContent } from '@/api/demo'
import { UNNAMED, useAssistant } from '@/assistant/assistant-context'
import { CascadeClient, type SuggestChip } from '@/assistant/cascade-client'
import type { Line } from '@/assistant/live-client'
import { orbMode } from '@/components/assistant/orb'
import { VoiceOrb } from '@/components/assistant/voice-orb'

/**
 * The demo's voice-first stage (Kevin, 2026-11-17: "the voice the center of attention"). A long
 * spoken opening and a page to read before talking lose people, so: one large orb and one line;
 * the tap that opens the audio is the tap that enters the visit, and the opening is about five
 * seconds. Under the orb, for people who
 * cannot talk: the engine's suggestion chips (answered aloud once the greeting, recording notice
 * included, has played to its end; typed before that), the one-minute tour, captions always on, and a
 * box to type (answered aloud too, and an open conversation carries on after it). The full recording notice sits by the mic. A live strip shows what the engine is
 * doing (tool, how long, the turn's estimated cost). The ask (resume, contact) stays on screen.
 * Mobile first: one column at 360 px.
 */

export const STAGE_LINE = 'Tap and ask me about Kevin'
export const RECORDING_NOTICE = 'Conversations here may be recorded to improve the assistant.'
/** After the follow-up window lapsed quietly under an open card (item 4). */
export const ASK_MORE = 'Tap to ask more'

function lastOf(lines: readonly Line[], kind: Line['kind']): Line | undefined {
  for (let i = lines.length - 1; i >= 0; i--) if (lines[i].kind === kind) return lines[i]
  return undefined
}

function money(usd: number | null): string {
  if (usd === null) return ''
  return usd < 0.01 ? `${(usd * 100).toFixed(2)}¢` : `$${usd.toFixed(3)}`
}

// A sliver of orb is not a reachable orb: it counts as on screen only when half of it shows.
const ORB_SEEN: IntersectionObserverInit = { threshold: 0.5 }

export function VoiceStage({ content, error }: { content?: DemoContent | null; error?: string | null }) {
  const { client, state, name } = useAssistant()
  const [draft, setDraft] = useState('')
  const [marked, setMarked] = useState(false)
  // Whether the orb is on screen (blind review, 2026-11-18): once the stage scrolls away on a
  // phone, a small fixed control is the way to interrupt him while he thinks or speaks.
  const orbRef = useRef<HTMLButtonElement>(null)
  const [orbSeen, setOrbSeen] = useState(true)
  useEffect(() => {
    const el = orbRef.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    // The LAST entry is the orb's current state: a fast scroll can deliver [in, out] in one batch.
    const observer = new IntersectionObserver((entries) => {
      const latest = entries[entries.length - 1]
      if (latest) setOrbSeen(latest.isIntersecting)
    }, ORB_SEEN)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])
  const who = name === UNNAMED ? 'the assistant' : name
  const cascade = client instanceof CascadeClient ? client : null
  const mode = orbMode(state)
  const speaking = state.activity === 'speaking'
  // What the orb's tap interrupts; the off-screen control shows for exactly these states.
  const interruptible = speaking || state.activity === 'thinking'
  const voiceOn = state.mic === 'on'

  const freshIfEnded = () => {
    if (state.phase === 'ended') client.reset()
  }
  const onOrb = () => {
    freshIfEnded()
    if (interruptible) client.interrupt()
    else if (voiceOn) client.stopVoice()
    else void client.startVoice()
  }
  const tapChip = (chip: SuggestChip) => {
    freshIfEnded()
    cascade?.tapChip(chip)
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    freshIfEnded()
    if (client.send(draft)) setDraft('')
  }

  const said = lastOf(state.lines, 'ai')
  const heard = lastOf(state.lines, 'me')
  const sys = lastOf(state.lines, 'sys')
  const status = speaking
    ? `${who} is speaking. Tap to interrupt.`
    : state.activity === 'thinking'
      ? `${who} is thinking…`
      : voiceOn
        ? 'Listening. Tap to stop.'
        : cascade?.lapsedQuietly
          ? ASK_MORE
          : STAGE_LINE

  // Its own class name: it once borrowed `rc-stage`, the phone section's sticky class, and stayed
  // pinned with no background over everything scrolling under it (live demo, 2026-11-18).
  return (
    <section aria-label="Talk to the demo assistant" className="rc-voice-stage flex flex-col items-center gap-3 px-4 pt-6 pb-4 text-center">
      <button
        ref={orbRef}
        type="button"
        onClick={onOrb}
        aria-label={speaking ? `Interrupt ${who}` : voiceOn ? 'Stop listening' : `Talk to ${who}`}
        data-stage="orb"
        data-mode={mode}
        className="aspect-square w-[min(64vw,15rem)] rounded-full outline-none focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-primary"
      >
        <VoiceOrb mode={mode} levels={() => client.levels()} className="block size-full" />
      </button>
      {interruptible && !orbSeen && (
        <button
          type="button"
          onClick={() => {
            client.interrupt()
            // The control unmounts with the reply; keep the keyboard on the orb, not the page body.
            orbRef.current?.focus({ preventScroll: true })
          }}
          aria-label={`Interrupt ${who}`}
          data-stage="stop"
          className="fixed right-4 bottom-[calc(1rem+env(safe-area-inset-bottom))] z-50 min-h-11 rounded-full bg-primary px-4 text-sm font-medium text-primary-foreground shadow-lg"
        >
          Interrupt
        </button>
      )}
      <p role="status" data-stage="line" className="rc-h3 min-h-[1.5em] text-lg font-semibold">
        {status}
      </p>
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      {state.notice && <p role="alert">{state.notice.text}</p>}
      <div data-stage="captions" aria-live="polite" className="w-full max-w-[34rem] text-left text-[0.95rem]">
        {heard && heard.kind === 'me' && <p className="text-muted-foreground">You: {heard.text}</p>}
        {said && said.kind === 'ai' && (
          <p>
            {who === 'the assistant' ? 'Assistant' : who}: {said.text}
          </p>
        )}
        {sys && sys.kind === 'sys' && <p className="text-muted-foreground italic">{sys.text}</p>}
      </div>
      {cascade && (cascade.strip.tools.length > 0 || cascade.strip.costUsd !== null) && (
        <p data-stage="strip" className="font-mono text-xs text-muted-foreground">
          {cascade.strip.tools
            .map((t) => `${t.tool}${t.ms !== null ? ` ${t.ms} ms` : ''}`)
            .join(' · ')}
          {cascade.strip.costUsd !== null && ` · this turn ~${money(cascade.strip.costUsd)} (estimate)`}
          {' · '}
          <button
            type="button"
            data-stage="robotic"
            className="underline"
            disabled={marked}
            onClick={() => setMarked(cascade.markRobotic())}
          >
            {marked ? 'Marked, thanks' : 'Sounded robotic'}
          </button>
        </p>
      )}
      {cascade && (
        <button type="button" className="rc-ask-q" data-stage="tour" onClick={() => cascade.startTour(0)}>
          Take the one-minute tour
        </button>
      )}
      {/* The engine's chips, checked for this visit (`demo/suggestions.py`): one row that scrolls
          sideways, so a phone's page never grows wider than the screen. */}
      {cascade && cascade.chips.length > 0 && (
        <div
          data-stage="chips"
          role="group"
          aria-label="Suggested questions"
          className="flex w-full max-w-[34rem] flex-nowrap gap-2 overflow-x-auto overscroll-x-contain pb-1"
        >
          {cascade.chips.map((chip) => (
            <button
              key={chip.token}
              type="button"
              className="rc-ask-q flex-none whitespace-nowrap"
              title={chip.question}
              onClick={() => tapChip(chip)}
            >
              {chip.label}
            </button>
          ))}
        </div>
      )}
      <form onSubmit={submit} data-stage="form" className="flex w-full max-w-[34rem] gap-2">
        <input
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder={`Or type to ${who}`}
          aria-label={`Type to ${who}`}
          className="min-h-11 flex-1 rounded-md border border-input bg-background px-3"
        />
        <button type="submit" className="rc-ask-button min-h-11 px-4" disabled={!draft.trim()}>
          Send
        </button>
      </form>
      <p data-stage="notice" className="max-w-[34rem] text-xs text-muted-foreground">
        {RECORDING_NOTICE}
      </p>
      {content && <AskCard content={content} />}
    </section>
  )
}

/** The ask that stays on screen: the resume and the ways to reach Kevin, as the content defines. */
function AskCard({ content }: { content: DemoContent }) {
  const links = content.links
  return (
    <aside data-stage="ask" className="mt-2 w-full max-w-[34rem] border-t pt-3 text-left">
      <p className="font-semibold">Want to talk to Kevin?</p>
      <p className="mt-1 flex flex-wrap gap-x-4 gap-y-2">
        <a href="/api/demo/asset/resume" className="inline-flex min-h-11 items-center underline">
          His resume (PDF)
        </a>
        {links.email && (
          <a href={`mailto:${links.email}`} className="inline-flex min-h-11 items-center underline">
            Email
          </a>
        )}
        {links.linkedin && (
          <a href={links.linkedin} className="inline-flex min-h-11 items-center underline" rel="noreferrer" target="_blank">
            LinkedIn
          </a>
        )}
        {links.github && (
          <a href={links.github} className="inline-flex min-h-11 items-center underline" rel="noreferrer" target="_blank">
            GitHub
          </a>
        )}
      </p>
    </aside>
  )
}
