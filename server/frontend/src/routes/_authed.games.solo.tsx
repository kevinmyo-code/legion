import { createFileRoute } from '@tanstack/react-router'

import { GamesSoloScreen } from '@/screens/games-solo'

/** `/games/solo`: Tetris with no clock and no engine. */
export const Route = createFileRoute('/_authed/games/solo')({
  component: GamesSoloScreen,
})
