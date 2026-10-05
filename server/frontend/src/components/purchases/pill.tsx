import { Link } from '@tanstack/react-router'
import { Search } from 'lucide-react'

/**
 * Home's way into the bought log (ticket 05, variant C): a search pill that is a
 * link, because "when did we last buy shampoo?" is the question and the screen it
 * opens is a search box already focused on nothing but that.
 */
export function BoughtPill() {
  return (
    <Link
      to="/bought"
      className="flex h-14 items-center gap-3 rounded-full bg-surface-3 px-5 text-base text-muted-foreground outline-none focus-visible:outline-2 focus-visible:outline-primary"
    >
      <Search className="size-5 shrink-0" aria-hidden="true" />
      When did we last buy...?
    </Link>
  )
}
