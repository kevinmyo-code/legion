import { useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useEffect, useRef, useState, type ReactNode } from 'react'

import {
  inviteNoticeKey,
  matchKey,
  postProgress,
  useMatch,
  useTransition,
  type Match,
  type MatchRead,
} from '@/api/games'
import { OkSentence } from '@/components/settings/settings-page'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorSentence, Panel } from '@/components/workbench/page'
import { Numbers, Sidebar, usePlayfield, type Stats } from '@/games/tetris/playfield'

/**
 * `/games/match/<id>`: one Tetris race, from invite to result (ticket 05). The
 * invite notification opens this page.
 *
 * The engine is the clock. Every read carries `server_now`, so the countdown and
 * the three minutes run on the engine's time corrected for this device's, and two
 * phones whose clocks disagree still start together.
 *
 * Every state says what it is in words: waiting, declined, cancelled, expired,
 * gone, could not reach the engine, a score that was not sent. None is drawn as
 * another (CLAUDE.md section 1).
 */
export const MATCH_COPY = {
  unreachable: "Can't reach the engine, so this race can't be read right now. Nothing was changed.",
  gone: 'This race is gone. A race is kept for ten minutes after it closes, and this one closed earlier, or never existed.',
  resumed:
    'You already played part of this race on a device, and a race cannot be restarted. Your score stands as the engine holds it.',
  toppedOut: 'Your board filled, so your game is over. Your score stands.',
  timeUp: 'Time is up.',
  waitingForThem: (name: string) => `Waiting for ${name} to finish, or for the clock.`,
  waitingForResult: 'Waiting for the result.',
} as const

/** The engine's now, as this device best knows it, re-read four times a second. */
function useEngineNow(offsetMs: number): number {
  const [now, setNow] = useState(() => Date.now() + offsetMs)
  useEffect(() => {
    setNow(Date.now() + offsetMs)
    const timer = window.setInterval(() => setNow(Date.now() + offsetMs), 250)
    return () => window.clearInterval(timer)
  }, [offsetMs])
  return now
}

function clock(ms: number): string {
  const seconds = Math.max(0, Math.ceil(ms / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <h1 className="text-[1.5rem] font-medium">Tetris race</h1>
        <Button asChild variant="ghost">
          <Link to="/games">Games</Link>
        </Button>
      </div>
      {children}
    </div>
  )
}

export function GamesMatchScreen({ id }: { id: string }) {
  const read = useMatch(id)

  if (read.isPending) {
    return (
      <Frame>
        <Skeleton className="h-40 rounded-sheet" />
      </Frame>
    )
  }
  if (read.data === null) {
    return (
      <Frame>
        <Panel title="Race">
          <p className="text-[0.9375rem]">{MATCH_COPY.gone}</p>
        </Panel>
      </Frame>
    )
  }
  if (read.data === undefined) {
    return (
      <Frame>
        <ErrorSentence>{MATCH_COPY.unreachable}</ErrorSentence>
      </Frame>
    )
  }
  return (
    <Frame>
      {read.isError && <ErrorSentence role="status">{MATCH_COPY.unreachable}</ErrorSentence>}
      <MatchBody id={id} read={read.data} />
    </Frame>
  )
}

function MatchBody({ id, read }: { id: string; read: MatchRead }) {
  const { match } = read
  if (match.status === 'invited') return <InviteView id={id} match={match} offsetMs={read.offsetMs} />
  if (match.status === 'playing') return <RaceView key={match.id} id={id} read={read} />
  return <Closed match={match} />
}

function InviteView({ id, match, offsetMs }: { id: string; match: Match; offsetMs: number }) {
  const queryClient = useQueryClient()
  const notice = queryClient.getQueryData<string>(inviteNoticeKey(id))
  const accept = useTransition(id, 'accept')
  const decline = useTransition(id, 'decline')
  const cancel = useTransition(id, 'cancel')
  const now = useEngineNow(offsetMs)
  const left = Date.parse(match.expires_at) - now
  const busy = accept.isPending || decline.isPending || cancel.isPending
  const failed = accept.error ?? decline.error ?? cancel.error

  return (
    <Panel title={match.role === 'inviter' ? `Inviting ${match.opponent.name}` : 'An invite'}>
      <div className="flex flex-col gap-4">
        <p className="text-[1rem]">{match.detail}</p>
        {notice && match.role === 'inviter' && <p className="text-[0.875rem] text-muted-foreground">{notice}</p>}
        <p className="text-[0.875rem] text-muted-foreground" aria-live="polite">
          The invite is open for {clock(left)} more.
        </p>
        {match.role === 'invitee' ? (
          <div className="flex gap-2">
            <Button disabled={busy} onClick={() => accept.mutate()}>
              Accept
            </Button>
            <Button variant="outline" disabled={busy} onClick={() => decline.mutate()}>
              Decline
            </Button>
          </div>
        ) : (
          <div>
            <Button variant="outline" disabled={busy} onClick={() => cancel.mutate()}>
              Cancel invite
            </Button>
          </div>
        )}
        {failed && <ErrorSentence>{failed.message}</ErrorSentence>}
      </div>
    </Panel>
  )
}

function Closed({ match }: { match: Match }) {
  const ended = match.status === 'ended'
  return (
    <Panel title={ended ? 'Result' : 'No race'}>
      <div className="flex flex-col gap-4">
        <p className="text-[1.25rem] font-medium" role="status">
          {match.detail}
        </p>
        {ended && (
          <div className="grid grid-cols-2 gap-3">
            <PlayerCard label="You" player={match.you} />
            <PlayerCard label={match.opponent.name} player={match.opponent} />
          </div>
        )}
        <div>
          <Button asChild variant="secondary">
            <Link to="/games">Back to Games</Link>
          </Button>
        </div>
      </div>
    </Panel>
  )
}

function PlayerCard({ label, player }: { label: string; player: Match['you'] }) {
  return (
    <div className="rounded-control bg-surface-2 px-4 py-3">
      <p className="text-[0.8125rem] text-muted-foreground">{label}</p>
      <p className="text-[1.25rem] font-medium tabular-nums">{player.score.toLocaleString()}</p>
      <p className="text-[0.8125rem] text-muted-foreground">
        {player.lines} lines, level {player.level}
        {player.topped_out ? ', board filled' : ''}
      </p>
    </div>
  )
}

function RaceView({ id, read }: { id: string; read: MatchRead }) {
  const { match, offsetMs } = read
  const queryClient = useQueryClient()
  const now = useEngineNow(offsetMs)
  const start = Date.parse(match.start_at ?? match.created_at)
  const ends = Date.parse(match.ends_at ?? match.expires_at)
  // A page opened after this player already posted (a reload, a second device)
  // cannot replay the race: a fresh board would post a lower score over the real one.
  const [resumed] = useState(() => match.you.updated_at !== null)
  const [toppedOut, setToppedOut] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)
  const finishedSent = useRef(false)

  const started = now >= start
  const timeUp = now >= ends
  const running = started && !timeUp && !toppedOut && !resumed
  const field = usePlayfield(match.seed, running, () => setToppedOut(true))
  const myGameOver = toppedOut || timeUp

  // Score out, both scores in, once a second while this player is racing; and one
  // last post, marked finished, the moment their game ends.
  const send = useRef<(finished: boolean) => Promise<void>>(async () => {})
  send.current = async (finished: boolean) => {
    const stats: Stats = field.statsRef.current
    try {
      const fresh = await postProgress(id, {
        score: stats.score,
        lines: stats.lines,
        level: stats.level,
        topped_out: stats.over,
        finished,
      })
      setSendError(null)
      queryClient.setQueryData(matchKey(id), fresh)
    } catch (error) {
      setSendError(error instanceof Error ? error.message : 'Your score was not sent.')
      if (finished) finishedSent.current = false
    }
  }

  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => void send.current(false), 1000)
    return () => window.clearInterval(timer)
  }, [running])

  useEffect(() => {
    if (resumed || !started || !myGameOver || finishedSent.current || match.you.finished) return
    finishedSent.current = true
    void send.current(true)
  }, [resumed, started, myGameOver, match.you.finished])

  if (!started) {
    return (
      <Panel title={`Against ${match.opponent.name}`}>
        <p className="text-center text-[3rem] font-medium tabular-nums" aria-live="polite">
          {Math.max(1, Math.ceil((start - now) / 1000))}
        </p>
        <p className="text-center text-[0.9375rem] text-muted-foreground">
          Starting. Same pieces for both of you, three minutes.
        </p>
      </Panel>
    )
  }

  const status = resumed
    ? MATCH_COPY.resumed
    : toppedOut
      ? MATCH_COPY.toppedOut
      : timeUp
        ? MATCH_COPY.timeUp
        : null

  return (
    <>
      <div className="flex items-center justify-between gap-3 rounded-control bg-surface-2 px-4 py-2">
        <span className="text-[0.9375rem]">
          {match.opponent.name}:{' '}
          <span className="font-medium tabular-nums">{match.opponent.score.toLocaleString()}</span>
          {match.opponent.topped_out ? ' (board filled)' : match.opponent.finished ? ' (finished)' : ''}
        </span>
        <span className="font-medium tabular-nums" aria-label="Time left">
          {clock(ends - now)}
        </span>
      </div>
      {resumed && (
        <Numbers stats={{ ...field.stats, ...match.you, hold: null, holdUsed: false, next: [], over: true }} />
      )}
      {status && (
        <p role="status" className="rounded-control bg-surface-2 px-4 py-3 text-[0.9375rem]">
          {status} {timeUp ? MATCH_COPY.waitingForResult : MATCH_COPY.waitingForThem(match.opponent.name)}
        </p>
      )}
      {sendError && <ErrorSentence>{sendError}</ErrorSentence>}
      {!resumed && (
        <div className="flex items-start gap-3">
          {field.board}
          <div className="flex min-w-0 flex-1 flex-col gap-3">
            <Numbers stats={field.stats} stacked />
            <Sidebar stats={field.stats} theme={field.theme} />
            {field.pauseButton}
            {field.holdButton}
          </div>
        </div>
      )}
      {!resumed && (
        <div className="flex items-center justify-between gap-2">
          {field.themePicker}
          {field.padToggle}
        </div>
      )}
      {!resumed && field.controls}
      {match.you.finished && !sendError && !resumed && <OkSentence>Your final score was sent.</OkSentence>}
    </>
  )
}
