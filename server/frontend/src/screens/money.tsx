import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { PageHeader } from '@/components/workbench/page'
import { BudgetsTab } from '@/screens/money-budgets'
import { CategoriesTab, FilesTab, RulesTab } from '@/screens/money-config'
import { TransactionsTab } from '@/screens/money-transactions'

/**
 * `/money`: the household's ledger at the desk (spec D9).
 *
 * Five tabs. The open one lives in the address (`/money?tab=files`) so Home can
 * link straight to "Held for review" and a refresh keeps its place.
 *
 * What is NOT here, on purpose: any way to hand in a statement or key in a
 * transaction. Data arrives by the daily bank pull, and the reconciliation gate
 * (CLAUDE.md section 4) is the only writer of a transaction; the Files tab shows
 * what the gate did with each file instead of offering a way around it.
 */

export const MONEY_TABS = ['transactions', 'budgets', 'categories', 'rules', 'files'] as const
export type MoneyTab = (typeof MONEY_TABS)[number]

export function isMoneyTab(value: unknown): value is MoneyTab {
  return typeof value === 'string' && (MONEY_TABS as readonly string[]).includes(value)
}

export function MoneyScreen({
  tab,
  need,
  onTab,
}: {
  tab: MoneyTab
  /** Open Transactions with "Needs a category" pressed. */
  need: boolean
  onTab: (tab: MoneyTab) => void
}) {
  return (
    <div className="flex flex-col">
      <PageHeader
        title="Money"
        subtitle="What came in and went out, what each thing was for, and what each month has spent. Anything no statement has checked says so."
      />
      <Tabs value={tab} onValueChange={(next) => isMoneyTab(next) && onTab(next)}>
        <TabsList aria-label="Money sections">
          <TabsTrigger value="transactions">Transactions</TabsTrigger>
          <TabsTrigger value="budgets">Budgets</TabsTrigger>
          <TabsTrigger value="categories">Categories</TabsTrigger>
          <TabsTrigger value="rules">Rules</TabsTrigger>
          <TabsTrigger value="files">Files</TabsTrigger>
        </TabsList>
        <TabsContent value="transactions">
          <TransactionsTab need={need} />
        </TabsContent>
        <TabsContent value="budgets">
          <BudgetsTab />
        </TabsContent>
        <TabsContent value="categories">
          <CategoriesTab />
        </TabsContent>
        <TabsContent value="rules">
          <RulesTab />
        </TabsContent>
        <TabsContent value="files">
          <FilesTab />
        </TabsContent>
      </Tabs>
    </div>
  )
}
