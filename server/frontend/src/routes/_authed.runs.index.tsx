import { createFileRoute } from '@tanstack/react-router'

import { RunsScreen } from '@/screens/runs'

/** `/runs`: the run inspector (procedures ticket 14). Every procedure run in the household,
 * newest first, and a branch report per procedure. A desk rail item; it works at phone
 * width too, reached by its URL. */
export const Route = createFileRoute('/_authed/runs/')({
  component: RunsScreen,
})
