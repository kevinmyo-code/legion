import type {
  CategoryRule,
  IngestedFile,
  LedgerTransaction,
  SpendAccount,
} from '@/api/ledger'
import { parseDay } from '@/lib/figures'

/**
 * Pure rules for the Money workbench: what a row's chips say, how a filter
 * narrows a list, what a rule matches. No fetching and no React, so each rule is
 * a function a test can call.
 *
 * Nothing here computes SPEND. That is the engine's (spec D5: a business rule
 * lives in Django once); the only sums in this file are the plain net of the rows
 * a person has filtered to, which are shown as exactly that and carry the word
 * `unverified` whenever one of the rows added in is.
 */

/** Whether no gate ever checked this row. The engine counts `USER` rows as
 * unverified too (`api/ledger_spend.py`), so the table and the figures agree. */
export function isUnverified(txn: Pick<LedgerTransaction, 'provenance'>): boolean {
  return txn.provenance === 'UNRECONCILED' || txn.provenance === 'USER'
}

export type SourceWord = 'You' | 'Rule' | 'Bank file' | 'None'

/** Where a row's category came from, in the words the Source column uses. */
export function sourceWord(txn: Pick<LedgerTransaction, 'category' | 'category_source'>): SourceWord {
  if (txn.category === null) return 'None'
  switch (txn.category_source) {
    case 'person':
      return 'You'
    case 'rule':
      return 'Rule'
    case 'stored':
      return 'Bank file'
    default:
      return 'None'
  }
}

/** `2026-10` of a transaction's own date. A calendar month of the date printed
 * on the bank file, not the budget month (which moves late Housing charges). */
export function monthOf(txn: Pick<LedgerTransaction, 'txn_date'>): string {
  return txn.txn_date.slice(0, 7)
}

const MONTH_NAME = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' })

/** "October 2026" for `2026-10`. */
export function monthLabel(month: string): string {
  return MONTH_NAME.format(parseDay(`${month}-01`))
}

/**
 * One label per card, keyed by the last four digits.
 *
 * The engine's spend names each account (the card's most recent bank-file name,
 * else "Card ending 7823"); where it has, that wins so every figure on the page
 * calls a card the same thing. A card the spend endpoint did not list (no
 * activity this month) takes its newest row's name, else the same fallback.
 */
export function accountLabels(
  rows: readonly LedgerTransaction[],
  spendAccounts: readonly SpendAccount[] = [],
): Map<string, string> {
  const labels = new Map<string, string>()
  for (const account of spendAccounts) labels.set(account.account_last4, account.label)

  const newest = new Map<string, LedgerTransaction>()
  for (const row of rows) {
    const seen = newest.get(row.account_last4)
    if (!seen || row.created_at > seen.created_at) newest.set(row.account_last4, row)
  }
  for (const [last4, row] of newest) {
    if (labels.has(last4)) continue
    const name = row.account_nickname.trim()
    labels.set(last4, name === '' ? `Card ending ${last4}` : name)
  }
  return labels
}

export interface TransactionFilters {
  /** A card's last four digits, or `all`. */
  account: string
  /** `YYYY-MM`, or `all`. */
  month: string
  /** A category name, or `all`. */
  category: string
  /** Only rows with no category. */
  needCategory: boolean
  /** Only rows no gate checked. */
  unverified: boolean
}

export const NO_FILTERS: TransactionFilters = {
  account: 'all',
  month: 'all',
  category: 'all',
  needCategory: false,
  unverified: false,
}

export function isFiltered(filters: TransactionFilters): boolean {
  return (
    filters.account !== 'all' ||
    filters.month !== 'all' ||
    filters.category !== 'all' ||
    filters.needCategory ||
    filters.unverified
  )
}

export function applyFilters(
  rows: readonly LedgerTransaction[],
  filters: TransactionFilters,
): LedgerTransaction[] {
  return rows.filter(
    (row) =>
      (filters.account === 'all' || row.account_last4 === filters.account) &&
      (filters.month === 'all' || monthOf(row) === filters.month) &&
      (filters.category === 'all' || row.category === filters.category) &&
      (!filters.needCategory || row.category === null) &&
      (!filters.unverified || isUnverified(row)),
  )
}

/** Newest first, then the order the engine stored them in. */
export function newestFirst(a: LedgerTransaction, b: LedgerTransaction): number {
  return b.txn_date.localeCompare(a.txn_date) || b.created_at.localeCompare(a.created_at)
}

export function monthsPresent(rows: readonly LedgerTransaction[]): string[] {
  return [...new Set(rows.map(monthOf))].sort().reverse()
}

/** Every category name worth offering: the household's own list plus any a row
 * already carries (a bank file can name one the list has never heard of). */
export function categoryNames(
  rows: readonly LedgerTransaction[],
  known: readonly string[] = [],
): string[] {
  const names = new Set(known)
  for (const row of rows) {
    if (row.category !== null) names.add(row.category)
    if (row.stored_category !== null) names.add(row.stored_category)
  }
  return [...names].sort((a, b) => a.localeCompare(b))
}

export interface NetFigure {
  currency: string
  cents: number
  /** True when any row added in is one no gate checked. */
  unverified: boolean
}

/** The plain net of some rows, one figure per currency (never across them).
 * Integers all the way; the caller prints "unverified" beside any that says so. */
export function netByCurrency(rows: readonly LedgerTransaction[]): NetFigure[] {
  const figures = new Map<string, NetFigure>()
  for (const row of rows) {
    const figure = figures.get(row.currency) ?? { currency: row.currency, cents: 0, unverified: false }
    figure.cents += row.amount_cents
    figure.unverified ||= isUnverified(row)
    figures.set(row.currency, figure)
  }
  return [...figures.values()].sort((a, b) => a.currency.localeCompare(b.currency))
}

// ---- Rules ----------------------------------------------------------------

/** The rule's own test: its text appears in the description, ignoring case. */
export function ruleMatches(rule: Pick<CategoryRule, 'substring'>, description: string): boolean {
  const needle = rule.substring.trim().toUpperCase()
  return needle !== '' && description.toUpperCase().includes(needle)
}

/** The rule that governs a description: the earliest one written that matches
 * (`LedgerController.applyCategoryRules` orders by when a rule was written). */
export function governingRule(
  rules: readonly CategoryRule[],
  description: string,
): CategoryRule | null {
  const ordered = [...rules].sort((a, b) => a.created_at_client.localeCompare(b.created_at_client))
  return ordered.find((rule) => ruleMatches(rule, description)) ?? null
}

export function matchCount(rule: CategoryRule, rows: readonly LedgerTransaction[]): number {
  return rows.filter((row) => ruleMatches(rule, row.description)).length
}

/**
 * The one line under a transaction's category that says how it got there:
 * "Rule said Groceries; you chose Household."
 *
 * Only what is known is said. A person's choice replaces the row that a rule's
 * backfill wrote, so what a rule WOULD say is read from the rules as they stand
 * now, and is worded as "says", not "said".
 */
export function categoryHistory(txn: LedgerTransaction, rules: readonly CategoryRule[]): string {
  const rule = governingRule(rules, txn.description)
  const ruleSays = rule !== null && rule.category !== txn.category ? rule : null
  const bankSaid = txn.stored_category !== null && txn.stored_category !== txn.category
  switch (txn.category_source) {
    case 'person': {
      const parts = [`You chose ${txn.category}.`]
      if (bankSaid) parts.push(`The bank file said ${txn.stored_category}.`)
      if (ruleSays) parts.push(`A rule for "${ruleSays.substring}" says ${ruleSays.category}.`)
      return parts.join(' ')
    }
    case 'rule':
      return `A rule set this to ${txn.category}.${bankSaid ? ` The bank file said ${txn.stored_category}.` : ''}`
    case 'stored':
      return `The bank file said ${txn.category}.${ruleSays ? ` A rule for "${ruleSays.substring}" says ${ruleSays.category}.` : ''}`
    default:
      return rule !== null
        ? `No category yet. A rule for "${rule.substring}" says ${rule.category}.`
        : 'No category yet.'
  }
}

// ---- Files ----------------------------------------------------------------

/** How a file's state reads to a person. The engine's own words are an enum. */
export function fileStateWords(state: IngestedFile['state']): string {
  switch (state) {
    case 'INGESTED':
      return 'Ingested'
    case 'QUARANTINED':
      return 'Held for review'
    case 'UNREADABLE':
      return 'Could not be read'
    case 'DUPLICATE_CONTENT':
      return 'Duplicate'
    case 'NEEDS_LLM':
      return 'Layout not recognised'
    case 'NEW':
      return 'Waiting to be read'
  }
}
