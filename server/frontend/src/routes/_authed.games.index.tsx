import { createFileRoute } from '@tanstack/react-router'

import { GamesScreen } from '@/screens/games'

/** `/games`: Tetris solo, and races with the household (retro-games ticket 05).
 * Both surfaces: it is where Mia's races start. */
export const Route = createFileRoute('/_authed/games/')({
  component: GamesScreen,
})
