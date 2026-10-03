import { useState } from 'react'

import {
  categoryRules,
  ingestedFiles,
  ledgerCategories,
  useLedgerTransactions,
  type CategoryRule,
  type IngestedFile,
  type LedgerCategory,
} from '@/api/ledger'
import { useRows, useSave, wire } from '@/api/synced'
import { Switch } from '@/components/ui/switch'
import { CrudPanel } from '@/components/workbench/crud-panel'
import type { FieldSpec, Values } from '@/components/workbench/record-form'
import { formatInstant } from '@/lib/figures'
import { fileStateWords, matchCount } from '@/lib/ledger'

/**
 * Money's three setup tabs: Categories, Rules and Files.
 *
 * Categories and Rules are authored config (the household typed them), so they
 * are the ordinary list-and-form panel. Files is the reverse: it is the gate's
 * own record of what the daily bank pull brought in, read only, and it says in
 * words what became of each file. There is no upload anywhere on the web:
 * statements are not handed in by hand any more.
 */

// ---- Categories -----------------------------------------------------------

const YES_NO = [
  { value: 'no', label: 'No' },
  { value: 'yes', label: 'Yes' },
] as const

function categoryBody(row: LedgerCategory, excluded: boolean): LedgerCategory {
  return wire<LedgerCategory>({
    name: row.name,
    is_food_category: row.is_food_category,
    excluded_from_spend: excluded,
    origin_guid: row.origin_guid,
  })
}

/**
 * The "Not spending" switch. It writes at once and waits for the engine: a
 * category marked not spending drops its rows out of every spend figure, so the
 * switch only moves when the engine has said yes, and a refusal is a sentence
 * under it that says nothing changed.
 */
function NotSpendingSwitch({ row }: { row: LedgerCategory }) {
  const save = useSave(ledgerCategories)
  const [problem, setProblem] = useState<string | null>(null)
  const on = row.excluded_from_spend === true
  const keyed = Boolean(row.origin_guid)

  function change(next: boolean) {
    setProblem(null)
    save
      .mutateAsync({ identity: row.origin_guid, body: categoryBody(row, next) })
      .catch((error: unknown) =>
        setProblem(error instanceof Error ? error.message : 'Nothing was saved.'),
      )
  }

  return (
    <div className="flex flex-col gap-1">
      <label className="inline-flex items-center gap-3">
        <Switch
          checked={on}
          disabled={save.isPending || !keyed}
          onCheckedChange={change}
          aria-label={`Not spending: ${row.name}`}
        />
        <span className="text-[0.9375rem]">{on ? 'Not spending' : 'Counts as spending'}</span>
      </label>
      {problem && (
        <p role="alert" className="max-w-64 text-[0.8125rem] text-destructive">
          {problem}
        </p>
      )}
    </div>
  )
}

export function CategoriesTab() {
  const fields = (row: LedgerCategory | null): FieldSpec[] => [
    {
      name: 'name',
      label: 'Name',
      kind: 'text',
      required: true,
      placeholder: 'Groceries',
      readOnly: row !== null,
      hint:
        row !== null
          ? 'A name cannot be changed: transactions, rules and targets refer to it by name. Add a new category instead.'
          : undefined,
    },
    {
      name: 'is_food',
      label: 'A food category',
      kind: 'select',
      required: true,
      options: YES_NO,
    },
    {
      name: 'not_spending',
      label: 'Not spending',
      kind: 'select',
      required: true,
      options: YES_NO,
      hint: 'Yes for money moving between your own places (Transfers): it is left out of every spend figure, and each figure says so.',
    },
  ]

  return (
    <CrudPanel
      table={ledgerCategories}
      title="Categories"
      description="The names a transaction can be filed under. A category marked not spending leaves every spend figure."
      addLabel="Add a category"
      what="categories"
      empty="No categories yet. Add one to start filing transactions."
      columns={[
        { header: 'Name', cell: (row) => <span className="font-medium">{row.name}</span> },
        { header: 'Food', cell: (row) => (row.is_food_category ? 'Yes' : 'No') },
        { header: 'Not spending', cell: (row) => <NotSpendingSwitch row={row} /> },
      ]}
      sort={(a, b) => a.name.localeCompare(b.name)}
      fields={fields}
      identity={(row) => row.origin_guid || null}
      initial={(row): Values => ({
        name: row?.name ?? '',
        is_food: row?.is_food_category ? 'yes' : 'no',
        not_spending: row?.excluded_from_spend ? 'yes' : 'no',
      })}
      toBody={(values, row, guid) =>
        wire<LedgerCategory>({
          name: row?.name ?? values.name.trim(),
          is_food_category: values.is_food === 'yes',
          excluded_from_spend: values.not_spending === 'yes',
          origin_guid: row?.origin_guid || guid,
        })
      }
      rowLabel={(row) => `"${row.name}"`}
      deleteConsequence="The category is removed from the list. Transactions already filed under it keep the name."
    />
  )
}

// ---- Rules ----------------------------------------------------------------

/** How many of the loaded transactions a rule's text matches. Says "still
 * loading" rather than a number while the ledger is partway in. */
function MatchCount({ rule }: { rule: CategoryRule }) {
  const { query } = useLedgerTransactions()
  if (query.data === undefined) {
    return (
      <span className="text-muted-foreground">
        {query.isError ? 'Not known' : 'Still loading'}
      </span>
    )
  }
  return <span className="tabular-nums">{matchCount(rule, query.data).toLocaleString('en-US')}</span>
}

export function RulesTab() {
  const categories = useRows(ledgerCategories)
  const names = [...new Set((categories.data ?? []).map((row) => row.name))].sort((a, b) =>
    a.localeCompare(b),
  )

  const fields = (row: CategoryRule | null): FieldSpec[] => {
    const options = names.includes(row?.category ?? '') || row === null ? names : [row!.category, ...names]
    return [
      {
        name: 'substring',
        label: 'When the description contains',
        kind: 'text',
        required: true,
        placeholder: 'TRADER JOE',
        hint: 'Capital letters do not matter.',
      },
      {
        name: 'category',
        label: 'File it under',
        kind: 'select',
        required: true,
        options: options.map((name) => ({ value: name, label: name })),
        hint: options.length === 0 ? 'There are no categories yet. Add one on the Categories tab first.' : undefined,
      },
    ]
  }

  return (
    <CrudPanel
      table={categoryRules}
      title="Rules"
      description="A rule files every transaction whose description contains its text. When two rules match, the one written first wins. Your own choice on a transaction always beats a rule."
      addLabel="Add a rule"
      what="rules"
      empty="No rules yet. A rule saves filing the same merchant by hand every time."
      columns={[
        {
          header: 'Description contains',
          cell: (row) => <span className="font-mono text-[0.875rem]">{row.substring}</span>,
        },
        { header: 'Category', cell: (row) => row.category },
        { header: 'Matches in loaded rows', align: 'right', cell: (row) => <MatchCount rule={row} /> },
      ]}
      sort={(a, b) => a.created_at_client.localeCompare(b.created_at_client)}
      fields={fields}
      identity={(row) => row.origin_guid || null}
      initial={(row): Values => ({
        substring: row?.substring ?? '',
        category: row?.category ?? names[0] ?? '',
      })}
      toBody={(values, row, guid) =>
        wire<CategoryRule>({
          category: values.category,
          substring: values.substring.trim(),
          created_at_client: row?.created_at_client ?? new Date().toISOString(),
          origin_guid: row?.origin_guid || guid,
        })
      }
      rowLabel={(row) => `the rule for "${row.substring}"`}
      deleteConsequence="The rule stops filing new transactions. Ones it already filed keep their category."
    />
  )
}

// ---- Files ----------------------------------------------------------------

function FileState({ file }: { file: IngestedFile }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="font-medium">{fileStateWords(file.state)}</span>
      {file.state === 'QUARANTINED' && (
        <span className="max-w-md text-[0.8125rem] text-muted-foreground">
          {file.quarantine_reason ?? 'The engine did not say why. Nothing from this file was added.'}
        </span>
      )}
      {file.state !== 'QUARANTINED' && file.quarantine_reason && (
        <span className="max-w-md text-[0.8125rem] text-muted-foreground">{file.quarantine_reason}</span>
      )}
    </div>
  )
}

export function FilesTab() {
  return (
    <CrudPanel
      table={ingestedFiles}
      title="Files"
      description="What the daily bank pull has brought in, and what became of each file. Read only: files arrive by the pull, never by an upload here."
      what="files"
      empty="No files have come in yet. They appear here after the daily bank pull."
      columns={[
        {
          header: 'File',
          cell: (file) => <span className="font-medium break-all">{file.display_name ?? 'Unnamed file'}</span>,
        },
        { header: 'What happened', cell: (file) => <FileState file={file} /> },
        { header: 'First seen', cell: (file) => formatInstant(file.first_seen_at) },
        { header: 'Last attempt', cell: (file) => formatInstant(file.last_attempt_at) },
      ]}
      sort={(a, b) => b.last_attempt_at.localeCompare(a.last_attempt_at)}
    />
  )
}
