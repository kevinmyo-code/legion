import type { ReactNode } from 'react'

import { AssistantProvider } from '@/assistant/assistant-context'
import { AssistantModals } from '@/components/assistant/assistant-modals'
import { DemoBanner } from '@/components/demo/demo-banner'
import { EventSheetHost } from '@/components/event-sheet-host'
import { FamilyShell } from '@/components/family-shell'
import { WorkbenchShell } from '@/components/workbench-shell'
import { NAV, visibleNav, type NavItem } from '@/lib/nav'
import { shownToRecruiter, useIsRecruiter } from '@/lib/recruiter'
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
 *
 * A recruiter's demo visit (ticket 13, ADR 0069) gets the same shell, less the
 * screens it may not use (`lib/recruiter`), with the demo banner above every
 * screen. The assistant is the members' own component either way.
 */
export function AppShell({
  children,
  nav = NAV,
}: {
  children: ReactNode
  nav?: readonly NavItem[]
}) {
  const surface = useSurface()
  const recruiter = useIsRecruiter()
  const items = visibleNav(nav, surface).filter((item) => !recruiter || shownToRecruiter(item.to))
  const content = recruiter ? (
    <>
      <DemoBanner />
      {children}
    </>
  ) : (
    children
  )

  return (
    <AssistantProvider defaultOpen>
      <AssistantModals>
        <EventSheetHost>
          {surface === 'workbench' ? (
            <WorkbenchShell items={items}>{content}</WorkbenchShell>
          ) : (
            <FamilyShell items={items}>{content}</FamilyShell>
          )}
        </EventSheetHost>
      </AssistantModals>
    </AssistantProvider>
  )
}
