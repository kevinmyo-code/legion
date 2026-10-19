import { createFileRoute } from '@tanstack/react-router'

import { GamesMatchScreen } from '@/screens/games-match'

/** `/games/match/<id>`: one race, from invite to result. The `game_invite`
 * notification opens this page (`server/games/matches.py:match_url`). */
export const Route = createFileRoute('/_authed/games/match/$id')({
  component: Page,
})

function Page() {
  const { id } = Route.useParams()
  return <GamesMatchScreen id={id} />
}
