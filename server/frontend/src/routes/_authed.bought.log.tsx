import { createFileRoute } from '@tanstack/react-router'

import { BoughtLogScreen } from '@/screens/bought-log'

/** `/bought/log`: the hand-logging form as its own page on a phone. `?item=`
 * starts the form with what was just searched for. */
export const Route = createFileRoute('/_authed/bought/log')({
  validateSearch: (search: Record<string, unknown>): { item?: string } => ({
    item: typeof search.item === 'string' && search.item.trim() !== '' ? search.item : undefined,
  }),
  component: Page,
})

function Page() {
  const { item } = Route.useSearch()
  return <BoughtLogScreen item={item} />
}
