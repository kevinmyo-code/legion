import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useRouterState } from '@tanstack/react-router'
import { CalendarDays, ListChecks, LogOut } from 'lucide-react'
import type { ReactNode } from 'react'

import { api } from '@/api/client'
import { useHousehold } from '@/api/queries'
import { ThemeToggle } from '@/components/theme-toggle'
import { Button } from '@/components/ui/button'

/**
 * ADR 0040's two viewports of one client, per ticket 05's own table: a left
 * rail on a wide screen, a bottom bar on a narrow one. Both read from the
 * same list so the two never drift into disagreeing about what LEGION can
 * navigate to (the same reasoning `docs/adr/0035` applies to voice vs hands
 * paths, pointed at desktop vs mobile chrome instead).
 */
const NAV_ITEMS = [
  { to: '/' as const, label: 'Today', icon: CalendarDays },
  { to: '/lists' as const, label: 'Lists', icon: ListChecks },
]

/**
 * The chrome every signed-in screen renders inside. The household's name
 * sits in the header rather than any assistant's - CLAUDE.md section 1: the
 * app is LEGION, the companion is a swappable persona, and this shell never
 * hardcodes one.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const household = useHousehold(true)
  const pathname = useRouterState({ select: (state) => state.location.pathname })

  async function signOut() {
    // Best-effort: whether or not the server call lands, the client drops
    // its own idea of who is signed in and sends the person back to
    // `/login`. A failed revoke here should not trap someone on a screen
    // they explicitly asked to leave.
    try {
      await api.POST('/api/auth/session/logout', {
        headers: { 'Content-Length': '0' },
      })
    } finally {
      queryClient.clear()
      await navigate({ to: '/login' })
    }
  }

  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      <header className="flex items-center justify-between gap-3 px-4 py-3 md:hidden">
        <span className="font-semibold">
          {household.data?.name ?? 'LEGION'}
        </span>
        <div className="flex items-center gap-1">
          <ThemeToggle compact />
          <Button variant="ghost" size="icon" aria-label="Sign out" onClick={signOut}>
            <LogOut />
          </Button>
        </div>
      </header>

      <nav className="hidden w-56 shrink-0 flex-col bg-surface-1 p-4 md:flex">
        <span className="mb-6 truncate px-2 text-sm font-semibold text-muted-foreground">
          {household.isPending && 'LEGION'}
          {household.isError && 'LEGION (offline)'}
          {household.data && household.data.name}
        </span>
        <div className="flex flex-1 flex-col gap-1">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon
            const active = pathname === item.to
            return (
              <Link
                key={item.to}
                to={item.to}
                className={`flex h-12 items-center gap-3 rounded-full px-4 text-sm transition-colors ${
                  active
                    ? 'bg-primary-container font-semibold text-primary-container-foreground'
                    : 'text-muted-foreground hover:bg-surface-3'
                }`}
              >
                <Icon className="size-4" />
                {item.label}
              </Link>
            )
          })}
        </div>
        <div className="mb-2">
          <ThemeToggle />
        </div>
        <Button variant="ghost" className="justify-start gap-2" onClick={signOut}>
          <LogOut className="size-4" />
          Sign out
        </Button>
      </nav>

      {/* A ceiling, not a corset. The Today route used to carry `max-w-lg`, which
          rendered the whole screen into a ~512px column inside a 1707px window and
          left the workbench about 70% empty. Width now comes from the content;
          `max-w-6xl` only stops a line of text running the full span of an
          ultrawide, which is its own kind of unreadable. */}
      <main className="mx-auto w-full max-w-6xl flex-1 p-4 pb-20 md:pb-4 lg:p-8">{children}</main>

      <nav className="fixed inset-x-0 bottom-0 z-10 flex bg-surface-2 pb-[env(safe-area-inset-bottom)] md:hidden">
        {NAV_ITEMS.map((item) => {
          const Icon = item.icon
          const active = pathname === item.to
          return (
            <Link
              key={item.to}
              to={item.to}
              className={`flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 py-2 text-xs ${
                active ? 'font-semibold text-foreground' : 'text-muted-foreground'
              }`}
            >
              <Icon className="size-5" />
              {item.label}
            </Link>
          )
        })}
      </nav>
    </div>
  )
}
