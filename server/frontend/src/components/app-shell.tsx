import type { ReactNode } from 'react'

import { EventSheetHost } from '@/components/event-sheet-host'
import { FamilyShell } from '@/components/family-shell'
import { WorkbenchShell } from '@/components/workbench-shell'
import { NAV, visibleNav, type NavItem } from '@/lib/nav'
import { useSurface } from '@/lib/surface'

/**
 * The chrome every signed-in screen renders inside: one of two DIFFERENT trees,
 * picked by viewport (`useSurface`, spec D1). Never both with one hidden by CSS:
 * the family tab bar is not in the DOM at desktop width and the workbench rail
 * is not in the DOM on a phone, so neither can be reached by Tab or a screen
 * reader when it is not the one being shown.
 *
 * `nav` is a seam for tests (an unbuilt item must never render); in the app it
 * is always the one `NAV` table.
 */
export function AppShell({
  children,
  nav = NAV,
}: {
  children: ReactNode
  nav?: readonly NavItem[]
}) {
  const surface = useSurface()
  const items = visibleNav(nav, surface)

  return (
    <EventSheetHost>
      {surface === 'workbench' ? (
        <WorkbenchShell items={items}>{children}</WorkbenchShell>
      ) : (
        <FamilyShell items={items}>{children}</FamilyShell>
      )}
    </EventSheetHost>
  )
}
