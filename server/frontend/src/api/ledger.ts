import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
  type QueryKey,
} from '@tanstack/react-query'

import { api } from '@/api/client'
import { useVisibleInterval } from '@/api/refetch'
import { runWrite } from '@/api/refusal'
import { ASPECT_POLL_MS, readAll, rowsKey, wire, type Table } from '@/api/synced'
import type { components } from '@/api/schema'

/**
 * The ledger as the Money workbench reads and writes it.
 *
 * Which routes exist was read from `server/api/ledger.py`, not assumed:
 *
 * - `ledger_transactions` is **GET only** and keyset-paged. It is the
 *   reconciliation gate's own output (CLAUDE.md section 4), so this client can
 *   never edit an amount or a date; a transaction's category is the one thing a
 *   person may lay over it, as a row in `transaction_categories`.
 * - `category` on a transaction is the EFFECTIVE category (person over rule over
 *   the stored one) and `category_source` says which of the three it is.
 * - Spend is computed once, on the engine (`GET /api/ledger/spend`), and this
 *   client never recomputes it: a figure is shown as the engine sent it.
 */

type Schemas = components['schemas']

export type LedgerTransaction = Schemas['LedgerTransaction']
export type LedgerCategory = Schemas['Category']
export type CategoryRule = Schemas['CategoryRule']
export type BudgetTarget = Schemas['BudgetTarget']
export type TransactionCategory = Schemas['LedgerTransactionCategory']
export type IngestedFile = Schemas['IngestedFile']
export type Spend = Schemas['Spend']
export type SpendAccount = Schemas['SpendAccount']

const live = '1' as const

export const ledgerTransactions: Table<LedgerTransaction> = {
  name: 'ledger/transactions',
  page: ({ since, after }) =>
    api.GET('/api/ledger/transactions/', { params: { query: { since, after } } }),
}

export const ledgerCategories: Table<LedgerCategory> = {
  name: 'ledger/categories',
  page: ({ since, after }) =>
    api.GET('/api/ledger/categories/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/ledger/categories/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) =>
    api.DELETE('/api/ledger/categories/{identity}/', { params: { path: { identity } } }),
}

export const categoryRules: Table<CategoryRule> = {
  name: 'ledger/category_rules',
  page: ({ since, after }) =>
    api.GET('/api/ledger/category_rules/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/ledger/category_rules/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) =>
    api.DELETE('/api/ledger/category_rules/{identity}/', { params: { path: { identity } } }),
}

export const budgetTargets: Table<BudgetTarget> = {
  name: 'ledger/budget_targets',
  page: ({ since, after }) =>
    api.GET('/api/ledger/budget_targets/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/ledger/budget_targets/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) =>
    api.DELETE('/api/ledger/budget_targets/{identity}/', { params: { path: { identity } } }),
}

/** Read only: the gate writes this table, a person never does. */
export const ingestedFiles: Table<IngestedFile> = {
  name: 'ingest/files',
  page: ({ since, after }) => api.GET('/api/ingest/files/', { params: { query: { since, after } } }),
}

// ---- Transactions ---------------------------------------------------------

const TRANSACTIONS_KEY = rowsKey(ledgerTransactions)
const PROGRESS_KEY: QueryKey = ['rows-progress', ledgerTransactions.name]
const SPEND_KEY: QueryKey = ['ledger', 'spend']

/**
 * Every transaction, complete or not at all, plus how many have arrived so far.
 *
 * The full ledger is thousands of rows and the engine hands out 500 at a time,
 * so the first read takes several round trips. While they arrive `query.data` is
 * undefined and `loaded` counts the rows so far: a screen says "Still loading N"
 * with that number and shows no figure, because a total over part of the ledger
 * is a wrong number that looks right. Once a read has completed, a background
 * refetch keeps the old complete list on screen until the new one is whole.
 *
 * The count lives in the query cache (not in component state) so that two
 * screens reading the same ledger - Home's counts and the Money table - agree
 * about how far along the one shared fetch is.
 */
export function useLedgerTransactions(enabled = true) {
  const queryClient = useQueryClient()
  const interval = useVisibleInterval(ASPECT_POLL_MS)
  const query = useQuery({
    queryKey: TRANSACTIONS_KEY,
    queryFn: async () => {
      queryClient.setQueryData(PROGRESS_KEY, 0)
      return readAll(ledgerTransactions, (count) => queryClient.setQueryData(PROGRESS_KEY, count))
    },
    enabled,
    retry: false,
    refetchInterval: interval,
    refetchOnWindowFocus: 'always',
    refetchOnReconnect: 'always',
  })
  const progress = useQuery({
    queryKey: PROGRESS_KEY,
    queryFn: () => 0,
    enabled: false,
    initialData: 0,
  })
  return { query, loaded: progress.data }
}

/** What a person's category choice does to the engine's records. */
export interface CategoryChoice {
  txn: LedgerTransaction
  /** The category to set, or null to take back this person's own choice. */
  category: string | null
}

function withCategory(txn: LedgerTransaction, category: string | null): LedgerTransaction {
  if (category !== null) {
    return { ...txn, category, category_source: 'person', category_pending: false }
  }
  // Taking a choice back lets the row's own (bank file) category show again.
  return {
    ...txn,
    category: txn.stored_category,
    category_source: txn.stored_category === null ? null : 'stored',
  }
}

function replaceRow(
  queryClient: QueryClient,
  id: string,
  next: (row: LedgerTransaction) => LedgerTransaction,
) {
  queryClient.setQueryData<LedgerTransaction[]>(TRANSACTIONS_KEY, (rows) =>
    rows?.map((row) => (row.id === id ? next(row) : row)),
  )
}

/**
 * Set or take back a transaction's category.
 *
 * **Optimistic**, unlike every other write on the workbench: a category is a
 * person's choice and as cheap as a tick, and a dropdown that waits for the
 * engine feels broken. So the row changes at once and, if the engine refuses or
 * cannot be reached, only THAT row is put back as it was (never the whole list,
 * which would undo another row's choice still in flight) and the caller shows a
 * sentence saying so. The refusal text is the engine's own, after "Nothing was
 * saved".
 *
 * Whether the engine accepted it is then re-read, along with spend, because a
 * category moves money between lines of the month.
 */
export function useSetTransactionCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ txn, category }: CategoryChoice) => {
      if (category === null) {
        await runWrite('deleted', () =>
          api.DELETE('/api/ledger/transaction_categories/{identity}/', {
            params: { path: { identity: txn.id } },
          }),
        )
        return
      }
      await runWrite('saved', () =>
        api.PUT('/api/ledger/transaction_categories/{identity}/', {
          params: { path: { identity: txn.id } },
          body: wire<TransactionCategory>({
            transaction_id: txn.id,
            category,
            source: 'person',
          }),
        }),
      )
    },
    onMutate: async ({ txn, category }) => {
      await queryClient.cancelQueries({ queryKey: TRANSACTIONS_KEY })
      replaceRow(queryClient, txn.id, (row) => withCategory(row, category))
    },
    onError: (_error, { txn }) => {
      replaceRow(queryClient, txn.id, () => txn)
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: TRANSACTIONS_KEY })
      void queryClient.invalidateQueries({ queryKey: SPEND_KEY })
    },
  })
}

// ---- Spend ----------------------------------------------------------------

function browserZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone
  } catch {
    return undefined
  }
}

/**
 * This month's spend, as the engine computed it. `month` omitted means the
 * current month in the browser's zone (the engine picks it from `tz`).
 */
export function useSpend(month?: string) {
  const interval = useVisibleInterval(ASPECT_POLL_MS)
  return useQuery({
    queryKey: [...SPEND_KEY, month ?? 'current'],
    queryFn: async () => {
      const reply = await api.GET('/api/ledger/spend', {
        params: { query: { month, tz: browserZone() } },
      })
      if (reply.data === undefined) {
        throw new Error(`GET /api/ledger/spend answered ${reply.response.status}`)
      }
      return reply.data
    },
    retry: false,
    refetchInterval: interval,
    refetchOnWindowFocus: 'always',
    refetchOnReconnect: 'always',
  })
}

export function invalidateSpend(queryClient: QueryClient) {
  return queryClient.invalidateQueries({ queryKey: SPEND_KEY })
}
