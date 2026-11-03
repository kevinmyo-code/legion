import { createFileRoute } from '@tanstack/react-router'

import { ClaudeScreen } from '@/screens/claude'

/** `/claude`: the Claude session's tasks and their delivered pages (claude-session ticket 09).
 * A desk rail item; it works at phone width too, reached by its URL. */
export const Route = createFileRoute('/_authed/claude')({
  component: ClaudeScreen,
})
