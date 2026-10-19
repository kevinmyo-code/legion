import { Link, useNavigate } from '@tanstack/react-router'
import { Gamepad2 } from 'lucide-react'

import { CANT_READ_OPPONENTS, useInvite, useOpponents, usePendingInvites } from '@/api/games'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorSentence, PageHeader, Panel } from '@/components/workbench/page'

/**
 * `/games` (retro-games ticket 05, ADR 0060): Tetris alone, or a three-minute
 * score race with someone in the household. Both surfaces: Mia's phone is the
 * family surface, and this is where her races start.
 *
 * Invites addressed to you are listed first, because one open for two minutes
 * is the thing on this page that cannot wait.
 */
export const GAMES_COPY = {
  subtitle: 'Tetris, alone or as a three-minute race with someone in the household.',
  soloBlurb: 'No clock. Play until the board fills.',
  raceBlurb:
    'Both of you get the same pieces in the same order. Three minutes from the start; the higher score wins.',
  nobody: 'Nobody else is in this household yet, so there is no one to race.',
  invited: 'Invited you to a race',
} as const

export function GamesScreen() {
  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-4 pb-16">
      <PageHeader title="Games" subtitle={GAMES_COPY.subtitle} />
      <PendingPanel />
      <Panel title="Tetris" description={GAMES_COPY.soloBlurb}>
        <Button asChild>
          <Link to="/games/solo">
            <Gamepad2 aria-hidden="true" />
            Play Tetris
          </Link>
        </Button>
      </Panel>
      <RacePanel />
    </div>
  )
}

function PendingPanel() {
  const pending = usePendingInvites()
  const invites = pending.data ?? []
  if (invites.length === 0) return null
  return (
    <Panel title="Invites for you">
      <ul className="flex flex-col gap-2">
        {invites.map((match) => (
          <li key={match.id}>
            <Link
              to="/games/match/$id"
              params={{ id: match.id }}
              className="flex items-center justify-between gap-3 rounded-control bg-primary-container px-4 py-3 text-primary-container-foreground"
            >
              <span>
                {match.opponent.name}: {GAMES_COPY.invited.toLowerCase()}
              </span>
              <span className="text-[0.8125rem]">Open</span>
            </Link>
          </li>
        ))}
      </ul>
    </Panel>
  )
}

function RacePanel() {
  const opponents = useOpponents()
  const invite = useInvite()
  const navigate = useNavigate()

  return (
    <Panel title="Race someone" description={GAMES_COPY.raceBlurb}>
      {opponents.isPending && <Skeleton className="h-12 rounded-control" />}
      {opponents.isError && <ErrorSentence>{CANT_READ_OPPONENTS}</ErrorSentence>}
      {opponents.data && opponents.data.length === 0 && (
        <p className="text-[0.9375rem] text-muted-foreground">{GAMES_COPY.nobody}</p>
      )}
      {opponents.data && opponents.data.length > 0 && (
        <ul className="flex flex-col gap-2">
          {opponents.data.map((opponent) => (
            <li key={opponent.user_id}>
              <Button
                variant="outline"
                className="w-full justify-start"
                disabled={invite.isPending}
                onClick={() =>
                  invite.mutate(opponent.user_id, {
                    onSuccess: (made) =>
                      void navigate({ to: '/games/match/$id', params: { id: made.match.id } }),
                  })
                }
              >
                Play {opponent.name}
              </Button>
            </li>
          ))}
        </ul>
      )}
      {invite.error && (
        <div className="mt-3">
          <ErrorSentence>{invite.error.message}</ErrorSentence>
        </div>
      )}
    </Panel>
  )
}
