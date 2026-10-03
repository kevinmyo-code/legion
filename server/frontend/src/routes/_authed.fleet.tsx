import { createFileRoute } from '@tanstack/react-router'

import { WorkbenchOnly } from '@/components/bigger-screen'
import { FleetScreen } from '@/screens/fleet'

/** Workbench only (spec D1): at family width the screen is not rendered and a
 * "made for a bigger screen" card stands in. */
export const Route = createFileRoute('/_authed/fleet')({
  component: Page,
})

function Page() {
  return (
    <WorkbenchOnly>
      <FleetScreen />
    </WorkbenchOnly>
  )
}
