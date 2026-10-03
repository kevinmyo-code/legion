import { Link, useRouterState } from '@tanstack/react-router'
import { LogOut } from 'lucide-react'
import type { ReactNode } from 'react'

import { HouseholdName } from '@/components/household-name'
import { ThemeToggle } from '@/components/theme-toggle'
import { Button } from '@/components/ui/button'
import { useSignOut } from '@/components/use-sign-out'
import { isActive, type NavItem } from '@/lib/nav'

/**
 * The workbench surface's chrome (1024 px and up): a 232 px left rail listing
 * every area that exists, and content that uses the width, capped at 1440 px so
 * a line of text never runs the length of an ultrawide.
 *
 * Kevin's desk, not a stretched phone: the rail is persistent and labelled
 * (nothing to discover), and the household name and the theme and sign-out
 * controls sit at its two ends, out of the content's way. Like the family
 * shell it renders only the items it is handed, so an area whose screen is not
 * built has no rail item at all.
 */
export function WorkbenchShell({ items, children }: { items: NavItem[]; children: ReactNode }) {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const signOut = useSignOut()

  return (
    <div className="flex min-h-dvh">
      <aside className="sticky top-0 flex h-dvh w-[232px] shrink-0 flex-col bg-surface-1 px-3 py-5">
        <HouseholdName className="mb-6 px-3" />

        <nav aria-label="Sections" className="flex-1 overflow-y-auto">
          <ul className="flex flex-col gap-1">
            {items.map((item) => {
              const Icon = item.icon
              const active = isActive(item, pathname)
              return (
                <li key={item.to}>
                  {item.separated && <hr className="mx-3 my-2 border-outline-variant" />}
                  <Link
                    to={item.to as '/'}
                    aria-current={active ? 'page' : undefined}
                    className={`flex h-12 items-center gap-3 rounded-full px-4 text-[0.9375rem] transition-colors ${
                      active
                        ? 'bg-primary-container font-semibold text-primary-container-foreground'
                        : 'font-medium text-muted-foreground hover:bg-surface-3'
                    }`}
                  >
                    <Icon className="size-5" />
                    {item.label}
                  </Link>
                </li>
              )
            })}
          </ul>
        </nav>

        <div className="flex flex-col items-start gap-1 px-1 pt-3">
          <ThemeToggle />
          <Button variant="ghost" className="gap-2 text-muted-foreground" onClick={signOut}>
            <LogOut />
            Sign out
          </Button>
        </div>
      </aside>

      <main className="min-w-0 flex-1">
        <div className="mx-auto w-full max-w-[1440px] px-8 py-8">{children}</div>
      </main>
    </div>
  )
}
