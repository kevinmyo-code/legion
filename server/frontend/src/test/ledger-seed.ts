import type { IngestedFile, LedgerTransaction, Spend } from '../api/ledger'
import { emptySpend } from './engine'
import type { Row } from './engine-tables'

/**
 * A believable ledger for the Money workbench: what the screenshots and the
 * tests that only need "some data" talk to.
 *
 * Fixed in October 2026 on purpose. The Money screen prints what the engine
 * says, so a seed that moved with the clock would make every assertion about a
 * month label rot. The awkward rows are the ones the trust rules exist for: card
 * rows nothing has checked (UNRECONCILED, with the engine's own note), rows no
 * one has categorised, one the bank file categorised, one a person did, and one
 * a rule did; a file the gate refused, one it could not read, and a duplicate.
 */

const NOTE =
  'Unverified: from a bank activity export that states no balance or total to check it against. It is replaced when the statement for this date passes the gate.'

let counter = 0
function uuid(): string {
  counter += 1
  return `30000000-0000-4000-8000-${counter.toString(16).padStart(12, '0')}`
}

export const CHECKING = '5521'
export const CARD = '7823'

type TxnInput = Partial<Omit<LedgerTransaction, 'id'>> & {
  description: string
  amount_cents: number
  txn_date: string
  /** The row's own category column; what the bank file said. */
  stored?: string | null
}

/** A transaction the way `GET /api/ledger/transactions/` serves one before any
 * override is laid over it (the fake engine applies overrides on read). */
export function makeTxn({ stored = null, ...input }: TxnInput): Row {
  const card = input.account_last4 === CARD
  const unverified = input.provenance === 'UNRECONCILED' || (input.provenance === undefined && card)
  return {
    id: uuid(),
    statement_id: unverified ? null : uuid(),
    account_last4: card ? CARD : CHECKING,
    account_nickname: card ? 'BofA card' : 'BofA checking',
    currency: 'USD',
    balance_cents: null,
    line_ref: '',
    category: stored,
    category_pending: false,
    stored_category: stored,
    category_source: stored === null ? null : 'stored',
    pending_logged_at: null,
    reversal_of: null,
    provenance: unverified ? 'UNRECONCILED' : 'DETERMINISTIC',
    verification_note: unverified ? NOTE : null,
    created_at: `${input.txn_date}T12:00:00Z`,
    origin_guid: null,
    ...input,
  } as unknown as Row
}

const t = (
  txn_date: string,
  description: string,
  amount_cents: number,
  account: typeof CHECKING | typeof CARD,
  stored: string | null = null,
) => makeTxn({ txn_date, description, amount_cents, account_last4: account, stored })

export function seedTransactions(): Row[] {
  return [
    t('2026-10-02', "Trader Joe's #552", -6422, CARD, 'Groceries'),
    t('2026-10-02', 'Shell 5521', -4810, CARD),
    t('2026-10-01', 'Spotify', -1699, CARD),
    t('2026-10-01', 'City Water & Power', -8640, CHECKING, 'Utilities'),
    t('2026-10-01', 'Paycheck, Northside Univ.', 231055, CHECKING, 'Income'),
    t('2026-10-01', 'Corner Market', -11237, CHECKING, 'Groceries'),
    t('2026-10-01', 'Transfer to Mia', -4500, CHECKING),
    t('2026-10-01', 'Riverside Dental', -12000, CARD, 'Health'),
    t('2026-09-30', 'Costco Wholesale', -18764, CARD),
    t('2026-09-30', 'Blue Door Coffee', -650, CARD),
    t('2026-09-29', 'Gulf Coast Electric', -9412, CHECKING, 'Utilities'),
    t('2026-09-29', 'Amazon Marketplace', -3899, CARD),
    t('2026-09-28', 'Pump & Go 118', -4130, CARD),
    t('2026-09-28', 'Oakwood Apts rent', -185000, CHECKING, 'Housing'),
    t('2026-09-15', "Trader Joe's #552", -5120, CHECKING, 'Groceries'),
  ]
}

export function seedCategories(): Row[] {
  const category = (name: string, food = false, notSpending = false): Row => ({
    id: uuid(),
    name,
    is_food_category: food,
    excluded_from_spend: notSpending,
    provenance: 'USER',
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    deleted_at: null,
    origin_guid: uuid(),
  })
  return [
    category('Groceries', true),
    category('Utilities'),
    category('Housing'),
    category('Health'),
    category('Income'),
    category('Fun'),
    category('Fuel'),
    category('Transfers', false, true),
  ]
}

export function seedRules(): Row[] {
  const rule = (substring: string, category: string, at: string): Row => ({
    id: uuid(),
    category,
    substring,
    created_at_client: at,
    provenance: 'USER',
    created_at: at,
    updated_at: at,
    deleted_at: null,
    origin_guid: uuid(),
  })
  return [
    rule('TRADER JOE', 'Groceries', '2026-08-02T00:00:00Z'),
    rule('SHELL', 'Fuel', '2026-08-03T00:00:00Z'),
    rule('SPOTIFY', 'Fun', '2026-08-04T00:00:00Z'),
  ]
}

export function seedTargets(): Row[] {
  const target = (category: string, amount_cents: number): Row => ({
    id: uuid(),
    category,
    currency: 'USD',
    amount_cents,
    effective_from_month: '2026-08-01',
    provenance: 'USER',
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    deleted_at: null,
    origin_guid: uuid(),
  })
  return [target('Groceries', 50000), target('Utilities', 20000), target('Health', 15000)]
}

export function seedFiles(): Row[] {
  const file = (
    display_name: string,
    state: IngestedFile['state'],
    quarantine_reason: string | null,
    last_attempt_at: string,
  ): Row => ({
    id: uuid(),
    content_sha256: uuid().replace(/-/g, ''),
    source_file_id: null,
    display_name,
    size_bytes: 4200,
    state,
    quarantine_reason,
    first_seen_at: '2026-10-01T06:00:00Z',
    last_attempt_at,
  })
  return [
    file('BofA_checking_Sep.csv', 'INGESTED', null, '2026-10-02T06:00:00Z'),
    file(
      'BofA_card_Sep.csv',
      'QUARANTINED',
      'The lines add to -$1,204.10 but the statement prints -$1,254.10. Nothing was written.',
      '2026-10-03T06:00:00Z',
    ),
    file('BofA_card_Aug.pdf', 'UNREADABLE', 'No text could be read from this file.', '2026-10-01T06:00:00Z'),
    file('BofA_checking_Sep_copy.csv', 'DUPLICATE_CONTENT', null, '2026-10-02T06:10:00Z'),
  ]
}

/** What the engine says this month has spent: the card carries unverified rows. */
export function seedSpend(): Spend {
  return {
    ...emptySpend('2026-10'),
    accounts: [
      {
        account_last4: CHECKING,
        label: 'BofA checking',
        spend_cents: 128410,
        unverified: false,
        unverified_cents: 0,
        latest_row_at: '2026-10-03T09:12:00Z',
      },
      {
        account_last4: CARD,
        label: 'BofA card',
        spend_cents: 64255,
        unverified: true,
        unverified_cents: 21000,
        latest_row_at: '2026-10-03T09:12:00Z',
      },
    ],
    categories: [
      { category: 'Groceries', spend_cents: 38110, target_cents: 50000, unverified: true },
      { category: 'Utilities', spend_cents: 17990, target_cents: 20000, unverified: false },
      { category: 'Health', spend_cents: 12000, target_cents: 15000, unverified: true },
      { category: 'Fun', spend_cents: 1699, target_cents: null, unverified: true },
    ],
    uncategorised_cents: 4120,
    uncategorised_unverified: true,
    excluded: {
      not_spending_cents: 120000,
      not_spending_categories: ['Transfers'],
      own_account_moves_cents: 50000,
      early_charges_moved_cents: 185000,
      early_charges_counted_here_cents: 185000,
      early_charges_counted_next_month_cents: 0,
    },
  }
}

/** The tables, keyed the way the fake engine keeps them. */
export function seedLedger(): Record<string, Row[]> {
  return {
    'ledger/transactions': seedTransactions(),
    'ledger/categories': seedCategories(),
    'ledger/category_rules': seedRules(),
    'ledger/budget_targets': seedTargets(),
    'ledger/transaction_categories': [],
    'ingest/files': seedFiles(),
  }
}
