import { Link } from '@tanstack/react-router'

import { SETTINGS_SECTIONS } from '@/lib/settings-nav'

/**
 * The workbench's list of Settings sections, beside the section itself. Not
 * rendered on the family surface (which has the index page instead): it is a
 * different tree, not a hidden one.
 */
export function SettingsSideNav() {
  return (
    <nav aria-label="Settings sections" className="sticky top-8 w-56 shrink-0 self-start">
      <ul className="flex flex-col gap-1">
        {SETTINGS_SECTIONS.map((section) => {
          const Icon = section.icon
          return (
            <li key={section.to}>
              <Link
                to={section.to}
                activeProps={{
                  'aria-current': 'page',
                  className: 'bg-primary-container font-semibold text-primary-container-foreground',
                }}
                inactiveProps={{ className: 'font-medium text-muted-foreground hover:bg-surface-2' }}
                className="flex h-12 items-center gap-3 rounded-full px-4 text-[0.9375rem] transition-colors"
              >
                <Icon className="size-5" />
                {section.label}
              </Link>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
