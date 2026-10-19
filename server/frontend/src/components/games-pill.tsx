import { Link } from '@tanstack/react-router'
import { Gamepad2 } from 'lucide-react'

/**
 * Home's way into Games on the family surface (retro-games ticket 05). The
 * family tab bar stays at its four tabs (spec D1); this is how Mia reaches
 * Tetris and a race from her phone.
 */
export function GamesPill() {
  return (
    <Link
      to="/games"
      className="flex h-14 items-center gap-3 rounded-full bg-surface-3 px-5 text-base text-muted-foreground outline-none focus-visible:outline-2 focus-visible:outline-primary"
    >
      <Gamepad2 className="size-5 shrink-0" aria-hidden="true" />
      Play Tetris
    </Link>
  )
}
