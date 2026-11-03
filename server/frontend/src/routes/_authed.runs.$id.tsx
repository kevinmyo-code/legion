import { createFileRoute } from '@tanstack/react-router'

import { RunDetailScreen } from '@/screens/runs'

/** `/runs/<id>`: one run's steps as a timeline, and what did not run (procedures ticket 14). */
export const Route = createFileRoute('/_authed/runs/$id')({
  component: Page,
})

function Page() {
  const { id } = Route.useParams()
  return <RunDetailScreen id={id} />
}
