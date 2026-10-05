import { createFileRoute } from '@tanstack/react-router'

import { BoughtScreen } from '@/screens/bought'

/** `/bought`: search the household's bought log (purchase-log 07). Both
 * surfaces: the phone's search-first screen and the desk's table with the form
 * beside it. `?q=` opens it already answering. */
export const Route = createFileRoute('/_authed/bought/')({
  validateSearch: (search: Record<string, unknown>): { q?: string } => ({
    q: typeof search.q === 'string' && search.q.trim() !== '' ? search.q : undefined,
  }),
  component: Page,
})

function Page() {
  const { q } = Route.useSearch()
  return <BoughtScreen q={q} />
}
