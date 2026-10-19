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
import { encodeBoard } from '@/games/tetris/board-wire'
import { OpponentBoard } from '@/games/tetris/opponent-board'
import { Numbers, RACE_NO_PAUSE, Sidebar, usePlayfield } from '@/games/tetris/playfield'

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
  /** Mid-race, only after REACH_GRACE_MS with no answer: the game keeps going. */
  raceUnreachable: "Can't reach the engine, your score is kept and will send when it's back.",
  waitingForBoard: (name: string) => `Waiting for ${name}`,
  gone: 'This race is gone. A race is kept for ten minutes after it closes, and this one closed earlier, or never existed.',
  resumed:
    'You already played part of this race on a device, and a race cannot be restarted. Your score stands as the engine holds it.',
  toppedOut: 'Your board filled, so your game is over. Your score stands.',
  timeUp: 'Time is up.',
  waitingForThem: (name: string) => `Waiting for ${name} to finish, or for the clock.`,
  waitingForResult: 'Waiting for the result.',
} as const

/**
 * How long without one good answer from the engine before the screen says so.
 * A race polls every second and a single slow or failed poll is normal (a cold
 * start, a phone between networks), so nothing is said for one; five seconds of
 * nothing is an outage worth one calm line (Kevin, 2026-10-19).
 */
export const REACH_GRACE_MS = 5000
/** The opponent's board dims after this long with no new post from them. */
export const BOARD_STALE_MS = 3000

/** True once `lastOkAt` (this device's clock) is REACH_GRACE_MS old; re-checked twice a second. */
function useQuiet(lastOkAt: number): boolean {
  const [quiet, setQuiet] = useState(false)
  useEffect(() => {
    const check = () => setQuiet(lastOkAt > 0 && Date.now() - lastOkAt >= REACH_GRACE_MS)
    check()
    const timer = window.setInterval(check, 500)
    return () => window.clearInterval(timer)
  }, [lastOkAt])
  return quiet
}

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
  // Every good GET, and every accepted progress post (it writes the same cache
  // entry), moves this on. One failed or slow poll says nothing.
  const quiet = useQuiet(read.dataUpdatedAt)

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
      {quiet && read.data.match.status !== 'playing' && (
        <ErrorSentence role="status">{MATCH_COPY.unreachable}</ErrorSentence>
      )}
      <MatchBody id={id} read={read.data} quiet={quiet} />
    </Frame>
  )
}

function MatchBody({ id, read, quiet }: { id: string; read: MatchRead; quiet: boolean }) {
  const { match } = read
  if (match.status === 'invited') return <InviteView id={id} match={match} offsetMs={read.offsetMs} />
  if (match.status === 'playing') return <RaceView key={match.id} id={id} read={read} quiet={quiet} />
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

function RaceView({ id, read, quiet }: { id: string; read: MatchRead; quiet: boolean }) {
  const { match, offsetMs } = read
  const queryClient = useQueryClient()
  const now = useEngineNow(offsetMs)
  const start = Date.parse(match.start_at ?? match.created_at)
  const ends = Date.parse(match.ends_at ?? match.expires_at)
  // A page opened after this player already posted (a reload, a second device)
  // cannot replay the race: a fresh board would post a lower score over the real one.
  const [resumed] = useState(() => match.you.updated_at !== null)
  const [toppedOut, setToppedOut] = useState(false)

  const started = now >= start
  const timeUp = now >= ends
  const running = started && !timeUp && !toppedOut && !resumed
  const field = usePlayfield(match.seed, running, () => setToppedOut(true), { besideOpponent: true })
  const myGameOver = toppedOut || timeUp
  const overRef = useRef(myGameOver)
  useEffect(() => {
    overRef.current = myGameOver
  })

  // Score and board out, both players in, once a second from the start until the
  // engine holds my final score. A post that fails is not said: the next one
  // carries the same numbers, so nothing is lost, and only a sustained silence
  // (`quiet`) shows a line. Finished rides on every post once my game is over, so
  // a final post that failed is simply sent again.
  const send = useRef<() => Promise<void>>(async () => {})
  useEffect(() => {
    send.current = async () => {
      const stats = field.statsRef.current
      const game = field.getGame()
      try {
        const fresh = await postProgress(id, {
          score: stats.score,
          lines: stats.lines,
          level: stats.level,
          topped_out: stats.over,
          finished: overRef.current,
          ...(game ? { board: encodeBoard(game) } : {}),
        })
        queryClient.setQueryData(matchKey(id), fresh)
      } catch {
        // Said by `quiet` if it keeps happening; one miss is retried in a second.
      }
    }
  })

  const sending = started && !resumed && !match.you.finished
  useEffect(() => {
    if (!sending) return
    const timer = window.setInterval(() => void send.current(), 1000)
    return () => window.clearInterval(timer)
  }, [sending])

  // The moment my game ends, say so now rather than on the next tick.
  useEffect(() => {
    if (sending && myGameOver) void send.current()
  }, [sending, myGameOver])

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

  const them = match.opponent
  const theirsDone = them.topped_out || them.finished
  // Engine time on both sides: their last post against the engine's now.
  const lastHeard = Date.parse(them.updated_at ?? match.start_at ?? match.created_at)
  const stale = !theirsDone && now - lastHeard > BOARD_STALE_MS
  const theirState = them.topped_out
    ? 'Board filled'
    : them.finished
      ? 'Finished'
      : stale
        ? MATCH_COPY.waitingForBoard(them.name)
        : null

  return (
    <>
      <div className="flex items-center justify-between gap-3 rounded-control bg-surface-2 px-4 py-2">
        <span className="text-[0.9375rem]">
          You: <span className="font-medium tabular-nums">{field.stats.score.toLocaleString()}</span>
          <span className="text-[0.8125rem] text-muted-foreground">
            {' '}
            · {field.stats.lines} lines, level {field.stats.level}
          </span>
        </span>
        <span className="font-medium tabular-nums" aria-label="Time left">
          {clock(ends - now)}
        </span>
      </div>
      {quiet && (
        <p role="status" className="rounded-control bg-surface-2 px-4 py-2 text-[0.875rem]">
          {MATCH_COPY.raceUnreachable}
        </p>
      )}
      {resumed && (
        <Numbers stats={{ ...field.stats, ...match.you, hold: null, holdUsed: false, next: [], over: true }} />
      )}
      {status && (
        <p role="status" className="rounded-control bg-surface-2 px-4 py-3 text-[0.9375rem]">
          {status} {timeUp ? MATCH_COPY.waitingForResult : MATCH_COPY.waitingForThem(match.opponent.name)}
        </p>
      )}
      {!resumed && (
        <div className="flex items-start gap-3">
          {field.board}
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <section aria-label={`${them.name}, live`} className="flex flex-col gap-1">
              <p className="flex items-baseline justify-between gap-2 text-[0.8125rem]">
                <span className="truncate text-muted-foreground">{them.name}</span>
                <span className="font-medium tabular-nums">{them.score.toLocaleString()}</span>
              </p>
              <OpponentBoard
                name={them.name}
                board={them.board}
                theme={field.theme}
                stale={stale}
                className="max-w-[min(100%,calc((100dvh-28rem)/2.1*0.4))]"
              />
              {theirState && <p className="text-[0.75rem] text-muted-foreground">{theirState}</p>}
            </section>
            <Sidebar stats={field.stats} theme={field.theme} compact />
            <p className="text-[0.75rem] text-muted-foreground">{RACE_NO_PAUSE}</p>
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
      {match.you.finished && !resumed && <OkSentence>Your final score was sent.</OkSentence>}
    </>
  )
}
