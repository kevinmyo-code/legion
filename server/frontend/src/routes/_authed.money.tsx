import { createFileRoute } from '@tanstack/react-router'

import { WorkbenchOnly } from '@/components/bigger-screen'
import { MoneyScreen, isMoneyTab, type MoneyTab } from '@/screens/money'

/**
 * Workbench only (spec D1). `?tab=` picks the tab and `?need=1` opens
 * Transactions with "Needs a category" pressed; both are what Home links to.
 */
export const Route = createFileRoute('/_authed/money')({
  validateSearch: (search: Record<string, unknown>): { tab?: MoneyTab; need?: true } => ({
    tab: isMoneyTab(search.tab) ? search.tab : undefined,
    need: search.need === true || search.need === 1 || search.need === '1' ? true : undefined,
  }),
  component: Page,
})

function Page() {
  const { tab, need } = Route.useSearch()
  const navigate = Route.useNavigate()
  return (
    <WorkbenchOnly>
      <MoneyScreen
        tab={tab ?? 'transactions'}
        need={need === true}
        onTab={(next) => void navigate({ search: { tab: next } })}
      />
    </WorkbenchOnly>
  )
}
