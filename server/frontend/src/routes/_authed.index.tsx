import { createFileRoute } from '@tanstack/react-router'

import { BriefingHome } from '@/screens/home-briefing'
import { FamilyHome } from '@/screens/home-family'
import { useSurface } from '@/lib/surface'

export const Route = createFileRoute('/_authed/')({
  component: Home,
})

/**
 * `/`: the family Home below 1024 px (Mia's glance-and-tick day, ticket 10) or
 * the Morning briefing from 1024 px up (recruiter-demo ticket 14, prototype A).
 * Two trees, not one page reflowed (ADR 0053); each lives in `screens/`.
 */
function Home() {
  const surface = useSurface()
  return surface === 'family' ? <FamilyHome /> : <BriefingHome />
}
