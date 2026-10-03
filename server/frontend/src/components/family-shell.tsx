import { Link, useRouterState } from '@tanstack/react-router'
import type { ReactNode } from 'react'

import { HouseholdName } from '@/components/household-name'
import { ThemeToggle } from '@/components/theme-toggle'
import { isActive, type NavItem } from '@/lib/nav'

/**
 * The family surface's chrome (below 1024 px): a top bar with the household's
 * name and the theme toggle, and a bottom tab bar. Sign-out is not here: it is
 * in Settings, Account (spec D12), so it is not a thumb away from the tabs.
 *
 * Built for a thumb on a phone, and specifically for the iPhone PWA:
 *  - the top bar and the tab bar both pad by `env(safe-area-inset-*)`, because
 *    an installed PWA runs under the notch and the home indicator
 *    (`viewport-fit=cover` is on in `index.html`);
 *  - every tab is at least 56 px tall and a full flex share wide, comfortably
 *    past the 44 px floor (spec user story 30);
 *  - the tab bar is a fixed bar and the content leaves room for it, so the last
 *    row is never hidden behind it.
 *
 * It renders exactly the items it is handed (`visibleNav` already filtered them
 * to built ones for this surface), so a tab for a screen that does not exist is
 * not merely hidden, it is not in the tree.
 */
export function FamilyShell({ items, children }: { items: NavItem[]; children: ReactNode }) {
  const pathname = useRouterState({ select: (state) => state.location.pathname })

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-20 flex items-center justify-between gap-3 bg-background px-4 pt-[calc(0.5rem+env(safe-area-inset-top))] pb-2">
        <HouseholdName />
        <div className="flex shrink-0 items-center gap-1">
          <ThemeToggle compact />
        </div>
      </header>

      <main className="mx-auto w-full max-w-xl flex-1 px-4 pb-[calc(6.5rem+env(safe-area-inset-bottom))]">
        {children}
      </main>

      <nav
        aria-label="Tabs"
        className="fixed inset-x-0 bottom-0 z-20 bg-surface-2 pb-[env(safe-area-inset-bottom)]"
      >
        <ul className="mx-auto flex max-w-xl px-2 pt-2 pb-2">
          {items.map((item) => {
            const Icon = item.icon
            const active = isActive(item, pathname)
            return (
              <li key={item.to} className="flex-1">
                <Link
                  to={item.to as '/'}
                  aria-current={active ? 'page' : undefined}
                  className={`flex min-h-14 flex-col items-center justify-center gap-1 rounded-2xl text-xs font-medium ${
                    active ? 'font-semibold text-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {/* The pill behind the icon is the active marker; the label is
                      bolder too, so the active tab is told by more than a tint. */}
                  <span
                    className={`grid h-8 w-16 place-items-center rounded-full transition-colors ${
                      active ? 'bg-primary-container text-primary-container-foreground' : ''
                    }`}
                  >
                    <Icon className="size-6" />
                  </span>
                  {item.label}
                </Link>
              </li>
            )
          })}
        </ul>
      </nav>
    </div>
  )
}
