import { createFileRoute } from '@tanstack/react-router'

import { WorkbenchOnly } from '@/components/bigger-screen'
import { BodyScreen } from '@/screens/body'

/** Workbench only (spec D1): at family width the screen is not rendered and a
 * "made for a bigger screen" card stands in. */
export const Route = createFileRoute('/_authed/body')({
  component: Page,
})

function Page() {
  return (
    <WorkbenchOnly>
      <BodyScreen />
    </WorkbenchOnly>
  )
}
