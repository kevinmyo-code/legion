import { createFileRoute } from '@tanstack/react-router'

import { JoinScreen } from '@/screens/join'

/**
 * `/join/<code>`, outside `_authed` on purpose: the person following an invite
 * has no session yet, and a route that requires one cannot be the door that
 * makes one. The server builds this URL (`join_url_for` in `household/views.py`)
 * and its catch-all hands it to this bundle.
 */
export const Route = createFileRoute('/join/$code')({
  component: Join,
})

function Join() {
  const { code } = Route.useParams()
  return <JoinScreen code={code} />
}
