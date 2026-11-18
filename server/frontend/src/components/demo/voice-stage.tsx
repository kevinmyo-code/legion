import { useState, type FormEvent } from 'react'

import type { DemoContent } from '@/api/demo'
import { UNNAMED, useAssistant } from '@/assistant/assistant-context'
import { CascadeClient } from '@/assistant/cascade-client'
import type { Line } from '@/assistant/live-client'
import { orbMode } from '@/components/assistant/orb'
import { VoiceOrb } from '@/components/assistant/voice-orb'

/**
 * The demo's voice-first stage (Kevin, 2026-11-17: "the voice the center of attention"). A long
 * spoken opening and a page to read before talking lose people, so: one large orb and one line;
 * the tap that opens the audio is the tap that enters the visit, and the opening is about five
 * seconds. Under the orb, for people who
 * cannot talk: tappable questions answered aloud, the one-minute tour, captions always on, and a
 * box to type (answered aloud too, and an open conversation carries on after it). The full recording notice sits by the mic. A live strip shows what the engine is
 * doing (tool, how long, the turn's estimated cost). The ask (resume, contact) stays on screen.
 * Mobile first: one column at 360 px.
 */

export const STAGE_LINE = 'Tap and ask me about Kevin'
export const RECORDING_NOTICE = 'Conversations here may be recorded to improve the assistant.'
export const QUESTION_CHIPS = [
  'Tell me about Kevin.',
  'How is LEGION built?',
  'What did this household spend last month?',
] as const

function lastOf(lines: readonly Line[], kind: Line['kind']): Line | undefined {
  for (let i = lines.length - 1; i >= 0; i--) if (lines[i].kind === kind) return lines[i]
  return undefined
}

function money(usd: number | null): string {
  if (usd === null) return ''
  return usd < 0.01 ? `${(usd * 100).toFixed(2)}¢` : `$${usd.toFixed(3)}`
}

export function VoiceStage({ content, error }: { content?: DemoContent | null; error?: string | null }) {
  const { client, state, name } = useAssistant()
  const [draft, setDraft] = useState('')
  const [marked, setMarked] = useState(false)
  const who = name === UNNAMED ? 'the assistant' : name
  const cascade = client instanceof CascadeClient ? client : null
  const mode = orbMode(state)
  const speaking = state.activity === 'speaking'
  const voiceOn = state.mic === 'on'

  const freshIfEnded = () => {
    if (state.phase === 'ended') client.reset()
  }
  const onOrb = () => {
    freshIfEnded()
    if (speaking || state.activity === 'thinking') client.interrupt()
    else if (voiceOn) client.stopVoice()
    else void client.startVoice()
  }
  const ask = (question: string) => {
    freshIfEnded()
    if (cascade) cascade.ask(question)
    else client.send(question)
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
        : STAGE_LINE

  // Its own class name: it once borrowed `rc-stage`, the phone section's sticky class, and stayed
  // pinned with no background over everything scrolling under it (live demo, 2026-11-18).
  return (
    <section aria-label="Talk to the demo assistant" className="rc-voice-stage flex flex-col items-center gap-3 px-4 pt-6 pb-4 text-center">
      <button
        type="button"
        onClick={onOrb}
        aria-label={speaking ? `Interrupt ${who}` : voiceOn ? 'Stop listening' : `Talk to ${who}`}
        data-stage="orb"
        data-mode={mode}
        className="aspect-square w-[min(64vw,15rem)] rounded-full outline-none focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-primary"
      >
        <VoiceOrb mode={mode} levels={() => client.levels()} className="block size-full" />
      </button>
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
      <div data-stage="chips" className="flex max-w-[34rem] flex-wrap justify-center gap-2">
        {cascade && (
          <button type="button" className="rc-ask-q" data-stage="tour" onClick={() => cascade.startTour(0)}>
            Take the one-minute tour
          </button>
        )}
        {QUESTION_CHIPS.map((question) => (
          <button key={question} type="button" className="rc-ask-q" onClick={() => ask(question)}>
            {question}
          </button>
        ))}
      </div>
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
