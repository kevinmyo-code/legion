import {
  Car,
  CalendarDays,
  HeartPulse,
  House,
  ListChecks,
  MapPin,
  NotebookText,
  Settings,
  ShoppingBasket,
  Wallet,
  type LucideIcon,
} from 'lucide-react'

import type { Surface } from '@/lib/surface'

/**
 * Everything LEGION's web client can navigate to, in one table, so the family
 * tab bar and the workbench rail never drift into disagreeing about what exists
 * (the reasoning `docs/adr/0035` applies to voice and hands paths, pointed at
 * two navigation chromes).
 *
 * **`built` is the gate.** An item renders only when its screen exists
 * (web-surface 04: "no item that opens an empty page"). Spec D1 lists four
 * family tabs and nine workbench rails, but today the routes are Home and
 * Lists, so today the tab bar has two tabs and the rail has two items. Each
 * later ticket that builds a screen flips its `built` to true in the same
 * commit as the route, and its tab or rail item appears with it; nothing else
 * needs to change. A test fails if an item is rendered while `built` is false.
 *
 * `to` is a plain string and not a typed route because most of these routes do
 * not exist yet, so the router's generated path type cannot name them. It is
 * safe to hand to `<Link>` exactly because only `built` items are ever rendered.
 */
export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Which surfaces list this item. A workbench-only area is never a family tab. */
  surfaces: readonly Surface[]
  /** True once the screen behind `to` exists. */
  built: boolean
  /** Where it sits on a surface whose order differs from the table's. */
  rank?: Partial<Record<Surface, number>>
  /** A divider is drawn above this item in the rail (Settings, set apart). */
  separated?: boolean
}

const BOTH = ['family', 'workbench'] as const
const DESK = ['workbench'] as const

/** In rail order. The family tab order differs (Lists before Calendar), via `rank`. */
export const NAV: readonly NavItem[] = [
  { to: '/', label: 'Home', icon: House, surfaces: BOTH, built: true },
  {
    to: '/calendar',
    label: 'Calendar',
    icon: CalendarDays,
    surfaces: BOTH,
    built: true,
    rank: { family: 3 },
  },
  { to: '/lists', label: 'Lists', icon: ListChecks, surfaces: BOTH, built: true, rank: { family: 2 } },
  { to: '/money', label: 'Money', icon: Wallet, surfaces: DESK, built: true },
  { to: '/pantry', label: 'Pantry', icon: ShoppingBasket, surfaces: DESK, built: true },
  { to: '/body', label: 'Body', icon: HeartPulse, surfaces: DESK, built: true },
  { to: '/fleet', label: 'Fleet', icon: Car, surfaces: DESK, built: true },
  { to: '/places', label: 'Places', icon: MapPin, surfaces: DESK, built: true },
  { to: '/notes', label: 'Notes', icon: NotebookText, surfaces: DESK, built: true },
  {
    to: '/settings',
    label: 'Settings',
    icon: Settings,
    surfaces: BOTH,
    built: false,
    separated: true,
    rank: { family: 4 },
  },
]

/** What `surface` actually renders: built items it lists, in that surface's order. */
export function visibleNav(nav: readonly NavItem[], surface: Surface): NavItem[] {
  return nav
    .map((item, index) => ({ item, order: item.rank?.[surface] ?? index }))
    .filter(({ item }) => item.built && item.surfaces.includes(surface))
    .sort((a, b) => a.order - b.order)
    .map(({ item }) => item)
}

/** Whether `item` is the current page. `/` matches only itself; the rest match
 * their own sub-routes too (`/settings/devices` keeps Settings lit). */
export function isActive(item: NavItem, pathname: string): boolean {
  if (item.to === '/') return pathname === '/'
  return pathname === item.to || pathname.startsWith(`${item.to}/`)
}
