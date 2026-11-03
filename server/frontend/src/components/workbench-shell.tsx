import { Link, useRouterState } from '@tanstack/react-router'
import type { ReactNode } from 'react'

import { AssistantDock, AssistantRailEntry } from '@/components/assistant/workbench-assistant'
import { HouseholdName } from '@/components/household-name'
import { ThemeToggle } from '@/components/theme-toggle'
import { isActive, type NavItem } from '@/lib/nav'

/**
 * The workbench surface's chrome (1024 px and up): a slim 92 px labelled icon
 * rail listing every area that exists (recruiter-demo 14, prototype A's rail), and content that uses the width, capped at 1440 px so
 * a line of text never runs the length of an ultrawide.
 *
 * Kevin's desk, not a stretched phone: the rail is persistent and labelled
 * (nothing to discover), and the household name and the theme control sit at
 * its two ends, out of the content's way. Like the family
 * shell it renders only the items it is handed, so an area whose screen is not
 * built has no rail item at all.
 */
export function WorkbenchShell({ items, children }: { items: NavItem[]; children: ReactNode }) {
  const pathname = useRouterState({ select: (state) => state.location.pathname })

  return (
    <div className="flex min-h-dvh">
      <aside className="sticky top-0 flex h-dvh w-[92px] shrink-0 flex-col items-center gap-2 bg-surface-1 px-2 py-4">
        <HouseholdName rail className="mb-1.5" />

        <nav aria-label="Sections" className="w-full flex-1 overflow-y-auto [scrollbar-width:none]">
          <ul className="flex flex-col gap-0.5">
            {items.map((item) => {
              const Icon = item.icon
              const active = isActive(item, pathname)
              return (
                <li key={item.to}>
                  {item.separated && <hr className="mx-2.5 my-1.5 border-outline-variant" />}
                  <Link
                    to={item.to as '/'}
                    aria-current={active ? 'page' : undefined}
                    className={`flex min-h-[3.375rem] flex-col items-center justify-center gap-1 rounded-2xl px-0.5 py-1.5 text-center text-[0.71875rem] transition-colors outline-none focus-visible:outline-2 focus-visible:outline-primary ${
                      active ? 'font-semibold text-foreground' : 'font-medium text-muted-foreground hover:bg-surface-3'
                    }`}
                  >
                    {/* The pill behind the icon is the active marker; the label is
                        bolder too, so the current page is told by more than a tint. */}
                    <span
                      aria-hidden="true"
                      className={`grid h-7 w-12 place-items-center rounded-full ${
                        active ? 'bg-primary-container text-primary-container-foreground' : ''
                      }`}
                    >
                      <Icon className="size-5" />
                    </span>
                    {item.label}
                  </Link>
                </li>
              )
            })}
          </ul>
        </nav>

        <div className="flex w-full flex-col items-center gap-0.5 pt-2">
          <AssistantRailEntry />
          <ThemeToggle rail />
        </div>
      </aside>

      <main className="min-w-0 flex-1">
        <div className="mx-auto w-full max-w-[1440px] px-8 py-8">{children}</div>
      </main>

      <AssistantDock />
    </div>
  )
}
