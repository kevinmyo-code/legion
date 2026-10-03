import { Link, Navigate } from '@tanstack/react-router'
import { ChevronRight } from 'lucide-react'

import { SETTINGS_SECTIONS } from '@/lib/settings-nav'
import { useSurface } from '@/lib/surface'

/**
 * `/settings`. On the family surface it is the list: one row per section, each
 * saying what it is for, every row a full-width 56 px-plus target.
 *
 * On the workbench the side list beside the content already IS the index, so
 * landing here sends you to the first section rather than showing a page that
 * says "pick one on the left". `replace`, so Back does not bounce.
 */
export function SettingsIndex() {
  const surface = useSurface()
  if (surface === 'workbench') {
    return <Navigate to={SETTINGS_SECTIONS[0].to} replace />
  }
  return (
    <div className="flex flex-col gap-4 pb-6">
      <h1 className="pt-1 text-[1.75rem] leading-tight font-medium">Settings</h1>
      <ul className="flex flex-col gap-2">
        {SETTINGS_SECTIONS.map((section) => {
          const Icon = section.icon
          return (
            <li key={section.to}>
              <Link
                to={section.to}
                className="flex min-h-16 items-center gap-4 rounded-card bg-surface-1 px-4 py-3 active:bg-surface-2"
              >
                <span className="grid size-10 shrink-0 place-items-center rounded-full bg-primary-container text-primary-container-foreground">
                  <Icon className="size-5" />
                </span>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="text-[1rem] font-medium">{section.label}</span>
                  <span className="text-[0.8125rem] text-muted-foreground">{section.blurb}</span>
                </span>
                <ChevronRight className="size-5 shrink-0 text-muted-foreground" />
              </Link>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
