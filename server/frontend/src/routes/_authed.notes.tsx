import { createFileRoute } from '@tanstack/react-router'

import { WorkbenchOnly } from '@/components/bigger-screen'
import { NotesScreen } from '@/screens/notes'

/** Workbench only (spec D1): at family width the screen is not rendered and a
 * "made for a bigger screen" card stands in. */
export const Route = createFileRoute('/_authed/notes')({
  component: Page,
})

function Page() {
  return (
    <WorkbenchOnly>
      <NotesScreen />
    </WorkbenchOnly>
  )
}
