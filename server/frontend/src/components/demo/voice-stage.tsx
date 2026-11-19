import { useEffect, useLayoutEffect, useRef, useState, type FormEvent } from 'react'

import type { DemoContent } from '@/api/demo'
import { UNNAMED, useAssistant } from '@/assistant/assistant-context'
import { CascadeClient, type SuggestChip } from '@/assistant/cascade-client'
import type { Line } from '@/assistant/live-client'
import { orbMode } from '@/components/assistant/orb'
import { VoiceOrb } from '@/components/assistant/voice-orb'
import { ChipRow } from '@/components/demo/chip-row'

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
// The dock's height before it has been measured (a 48 px orb and its padding).
const DOCK_ROOM_PX = 72

export function VoiceStage({ content, error }: { content?: DemoContent | null; error?: string | null }) {
  const { client, state, name } = useAssistant()
  const [draft, setDraft] = useState('')
  const [marked, setMarked] = useState(false)
  // Whether the orb is on screen. Once the stage scrolls away, the orb docks at the bottom of the
  // page with the status line (Kevin, 2026-11-18: "make the orb follow along at the bottom of the
  // page as a footer"); the dock's tap is the orb's tap, in every state.
  const orbRef = useRef<HTMLButtonElement>(null)
  const dockOrbRef = useRef<HTMLButtonElement>(null)
  const dockRef = useRef<HTMLDivElement>(null)
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
  // While docked, the page gets the dock's height as room at its foot, so nothing ends under it.
  const docked = !orbSeen
  useEffect(() => {
    if (!docked) return
    const el = dockRef.current
    const room = () => {
      document.body.style.paddingBottom = `${el?.offsetHeight || DOCK_ROOM_PX}px`
    }
    room()
    window.addEventListener('resize', room)
    return () => {
      window.removeEventListener('resize', room)
      document.body.style.paddingBottom = ''
    }
  }, [docked])
  const who = name === UNNAMED ? 'the assistant' : name
  const cascade = client instanceof CascadeClient ? client : null
  const mode = orbMode(state)
  const speaking = state.activity === 'speaking'
  // What the orb's tap interrupts.
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

  const orbLabel = speaking ? `Interrupt ${who}` : voiceOn ? 'Stop listening' : `Talk to ${who}`

  // The captions box: newest words at its foot once they no longer fit (`.rc-captions`), measured
  // again whenever the box or its words change size (a resize, a rotation, a late font), so the
  // newest words are never the ones cut off. The whole answer is one tap away ("Read it all").
  const captionsRef = useRef<HTMLDivElement>(null)
  const captionWordsRef = useRef<HTMLDivElement>(null)
  const [captionsOverflow, setCaptionsOverflow] = useState(false)
  const [captionsOpen, setCaptionsOpen] = useState(false)
  const captionText = `${heard?.id}:${said?.id}:${said?.kind === 'ai' ? said.text.length : 0}:${sys?.id}`
  const answerId = said?.id
  useEffect(() => {
    // A new answer starts with the box closed again.
    setCaptionsOpen(false)
  }, [answerId])
  useLayoutEffect(() => {
    const box = captionsRef.current
    const words = captionWordsRef.current
    if (!box || !words) return
    const measure = () => setCaptionsOverflow(words.offsetHeight > box.clientHeight + 1)
    measure()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    observer?.observe(box)
    observer?.observe(words)
    void document.fonts?.ready.then(measure)
    return () => observer?.disconnect()
  }, [captionText])

  // Its own class name: it once borrowed `rc-stage`, the phone section's sticky class, and stayed
  // pinned with no background over everything scrolling under it (live demo, 2026-11-18).
  return (
    <section aria-label="Talk to the demo assistant" className="rc-voice-stage flex flex-col items-center gap-3 px-4 pt-6 pb-4 text-center">
      <button
        ref={orbRef}
        type="button"
        onClick={onOrb}
        aria-label={orbLabel}
        data-stage="orb"
        data-mode={mode}
        className="aspect-square w-[min(64vw,15rem)] rounded-full outline-none focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-primary"
      >
        <VoiceOrb mode={mode} levels={() => client.levels()} className="block size-full" />
      </button>
      {/* The chips sit right under the orb (Kevin, 2026-11-19: "make them float at the bottom of the
          orb"); the row's height is kept while there are none, so chips arriving never push the page. */}
      <div data-stage="chips-room" className="flex min-h-12 w-full max-w-[34rem] justify-center">
        {cascade && cascade.chips.length > 0 && <ChipRow chips={cascade.chips} onTap={tapChip} />}
      </div>
      {docked && (
        // Its own class and an opaque background (the stage once borrowed a sticky class and
        // stayed pinned, see-through, over the page); the safe area keeps it off a phone's bar.
        <div
          ref={dockRef}
          data-stage="dock"
          className="rc-voice-dock fixed inset-x-0 bottom-0 z-50 flex items-center gap-3 border-t bg-background px-4 pt-2 pb-[calc(0.5rem+env(safe-area-inset-bottom))] shadow-lg"
        >
          <button
            ref={dockOrbRef}
            type="button"
            onClick={() => {
              onOrb()
              // The keyboard stays on the orb the visitor can see.
              dockOrbRef.current?.focus({ preventScroll: true })
            }}
            aria-label={orbLabel}
            data-stage="dock-orb"
            data-mode={mode}
            className="size-12 flex-none rounded-full outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
          >
            <VoiceOrb mode={mode} levels={() => client.levels()} points={260} dot={0.7} className="block size-full" />
          </button>
          <p className="min-w-0 flex-1 truncate text-left text-sm font-medium">{status}</p>
        </div>
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
      {/* Live captions in a box of fixed height: growing and shrinking with every sentence they
          moved the whole page under the stage (the browser run, 2026-11-19: layout shift 0.48
          over one tour at 390 px). Words that fit sit at its top; a longer answer keeps its newest
          words at its foot and its start fades out at the top, as live captions do; "Read it all"
          opens the whole answer. Nothing in it scrolls: a scroll box here took the visitor's swipes
          and wheel turns from the page. */}
      <div
        data-stage="captions"
        data-overflow={captionsOverflow && !captionsOpen}
        data-open={captionsOpen}
        ref={captionsRef}
        className="rc-captions relative flex w-full max-w-[34rem] flex-col overflow-hidden text-left text-[0.95rem]"
      >
        {/* The live region is the words alone: "Read it all" is not announced with every caption. */}
        <div ref={captionWordsRef} aria-live="polite" tabIndex={-1} data-stage="captions-words" className="outline-none">
          {heard && heard.kind === 'me' && <p className="text-muted-foreground">You: {heard.text}</p>}
          {said && said.kind === 'ai' && (
            <p>
              {who === 'the assistant' ? 'Assistant' : who}: {said.text}
            </p>
          )}
          {sys && sys.kind === 'sys' && <p className="text-muted-foreground italic">{sys.text}</p>}
        </div>
        {/* For someone who cannot hear: the start of a long answer is one tap away. It sits over the
            faded start, so it moves nothing; the box grows only when the visitor taps it. */}
        {captionsOverflow && !captionsOpen && (
          <button
            type="button"
            data-stage="captions-open"
            className="rc-captions-open"
            onClick={() => {
              setCaptionsOpen(true)
              // The control goes once the box opens: the keyboard moves to the words it opened.
              captionWordsRef.current?.focus({ preventScroll: true })
            }}
          >
            Read it all
          </button>
        )}
      </div>
      {/* Kevin's tester visit only: the engine sends a turn's cost to no one else. */}
      {cascade && cascade.stripShown && (cascade.strip.tools.length > 0 || cascade.strip.costUsd !== null) && (
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
