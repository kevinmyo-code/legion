import { createFileRoute } from '@tanstack/react-router'

import { WorkbenchOnly } from '@/components/bigger-screen'
import { PlacesScreen } from '@/screens/places'

/** Workbench only (spec D1): at family width the screen is not rendered and a
 * "made for a bigger screen" card stands in. */
export const Route = createFileRoute('/_authed/places')({
  component: Page,
})

function Page() {
  return (
    <WorkbenchOnly>
      <PlacesScreen />
    </WorkbenchOnly>
  )
}
