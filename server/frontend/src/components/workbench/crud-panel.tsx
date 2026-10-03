import { Pencil, Plus } from 'lucide-react'
import { useState, type ReactNode } from 'react'

import { useRemove, useRows, useSave, type Table as SyncedTable } from '@/api/synced'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ConfirmDelete } from '@/components/workbench/confirm-delete'
import { Loaded } from '@/components/workbench/loaded'
import { EmptySentence, Panel } from '@/components/workbench/page'
import { EditorDialog, type FieldSpec, type Values } from '@/components/workbench/record-form'
import { newGuid } from '@/lib/figures'

/**
 * A panel that lists one synced table and lets a person add, edit and delete
 * its rows.
 *
 * Most of the aspect screens are "a list of rows plus a form", repeated per
 * table, and writing each by hand is how the empty, could-not-reach and stale
 * sentences, the not-optimistic save and the confirm-before-delete drift apart
 * between screens. This is the one implementation: the caller supplies what is
 * specific to the table (columns, fields, how a row becomes a body) and every
 * panel inherits the same behaviour:
 *
 * - the read follows `next` to the end or fails (`useRows`), so a list is whole
 *   or absent, never a partial one that something later totals;
 * - empty, could-not-reach and stale are three different sentences (`Loaded`);
 * - a save waits for the engine and a refusal stays on screen as its sentence;
 * - a delete asks first and says what it takes.
 *
 * A row whose table key is missing (`identity` returns null: a vehicle created
 * on the engine itself has no `origin_guid`) is shown, and says in words that it
 * cannot be edited here, instead of offering a button that would write
 * somewhere else.
 */

export interface Column<T> {
  header: string
  cell: (row: T) => ReactNode
  align?: 'right'
}

export interface CrudPanelProps<T> {
  table: SyncedTable<T>
  title: string
  description?: ReactNode
  /** The add button's label: "Log weight". Absent means rows cannot be added. */
  addLabel?: string
  /** What the rows are called, for the could-not-reach sentence. */
  what: string
  /** Said when the read worked and there are no rows at all. */
  empty: ReactNode
  columns: readonly Column<T>[]
  /** Absent on a read-only list (drives): then there is no form at all. */
  fields?: readonly FieldSpec[] | ((row: T | null) => readonly FieldSpec[])
  /** The key the engine upserts and deletes by, or null if this row has none. */
  identity?: (row: T) => string | null
  /** The key for a row that does not exist yet. A fresh guid unless the table is
   * keyed by something the person types (a place's label). */
  newIdentity?: (values: Values) => string
  initial?: (row: T | null) => Values
  toBody?: (values: Values, row: T | null, identity: string) => T
  /** A name for one row, for button labels and the delete confirmation. */
  rowLabel?: (row: T) => string
  /** What a delete takes, in a sentence. */
  deleteConsequence?: string
  /** Say a row cannot be edited, and why; null means it can. */
  lockedReason?: (row: T) => string | null
  sort?: (a: T, b: T) => number
  /** Narrow the rows shown (a day, a vehicle). */
  select?: (rows: T[]) => T[]
  /** Said when `select` leaves nothing but the table has rows. */
  emptySelection?: ReactNode
  /** Drawn above the table, from the rows `select` kept. */
  above?: (rows: T[], all: T[]) => ReactNode
  /** Rows shown before "Show all N". */
  limit?: number
  /** Anything to say in the form that is not a field. */
  formExtra?: (row: T | null) => ReactNode
  /** The dialog title. */
  dialogTitle?: (row: T | null) => string
  /** Extra buttons for the panel header. */
  actions?: ReactNode
  /** False for a table the engine keeps no delete route for. */
  deletable?: boolean
}

export function CrudPanel<T>(props: CrudPanelProps<T>) {
  const {
    table,
    title,
    description,
    addLabel,
    what,
    empty,
    columns,
    identity,
    newIdentity,
    initial,
    toBody,
    rowLabel,
    deleteConsequence,
    lockedReason,
    sort,
    select,
    emptySelection,
    above,
    limit,
    formExtra,
    dialogTitle,
    actions,
    deletable = true,
  } = props

  const query = useRows(table)
  // A table the web has no PUT for (drives, receipts) is a list and nothing more.
  const editable =
    table.put !== undefined && identity !== undefined && toBody !== undefined && initial !== undefined && rowLabel !== undefined
  const save = useSave(table)
  const remove = useRemove(table)
  const [editing, setEditing] = useState<{ row: T | null } | null>(null)
  const [showAll, setShowAll] = useState(false)

  const fields =
    typeof props.fields === 'function' ? props.fields(editing?.row ?? null) : (props.fields ?? [])

  return (
    <Panel
      title={title}
      description={description}
      action={
        <>
          {actions}
          {editable && addLabel && (
            <Button variant="secondary" onClick={() => setEditing({ row: null })}>
              <Plus /> {addLabel}
            </Button>
          )}
        </>
      }
    >
      <Loaded
        query={query}
        what={what}
        quiet
        empty={empty}
        render={(all) => {
          const chosen = select ? select(all) : all
          const ordered = sort ? [...chosen].sort(sort) : chosen
          const shown = limit && !showAll ? ordered.slice(0, limit) : ordered
          return (
            <div className="flex flex-col gap-4">
              {above?.(ordered, all)}
              {ordered.length === 0 ? (
                <EmptySentence>{emptySelection ?? empty}</EmptySentence>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      {columns.map((column) => (
                        <TableHead key={column.header} className={column.align === 'right' ? 'text-right' : undefined}>
                          {column.header}
                        </TableHead>
                      ))}
                      {editable && (
                        <TableHead className="w-0">
                          <span className="sr-only">Actions</span>
                        </TableHead>
                      )}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {shown.map((row) => {
                      const key = identity ? identity(row) : null
                      const locked = editable && key === null ? 'This row has no key on the engine yet, so it cannot be edited here.' : (lockedReason?.(row) ?? null)
                      return (
                        <TableRow key={key ?? String(ordered.indexOf(row))}>
                          {columns.map((column) => (
                            <TableCell key={column.header} className={column.align === 'right' ? 'text-right' : undefined}>
                              {column.cell(row)}
                            </TableCell>
                          ))}
                          {editable && (
                          <TableCell>
                            {locked ? (
                              <span className="block max-w-48 text-right text-[0.8125rem] text-muted-foreground">{locked}</span>
                            ) : (
                              <div className="flex items-start justify-end gap-1">
                                <Button
                                  variant="ghost"
                                  size="icon-sm"
                                  className="text-muted-foreground"
                                  aria-label={`Edit ${rowLabel?.(row) ?? 'row'}`}
                                  onClick={() => setEditing({ row })}
                                >
                                  <Pencil />
                                </Button>
                                {deletable && key !== null && (
                                  <ConfirmDelete
                                    label={rowLabel?.(row) ?? 'row'}
                                    consequence={deleteConsequence ?? 'This row is removed for everyone in the household.'}
                                    onConfirm={() => remove.mutateAsync(key)}
                                  />
                                )}
                              </div>
                            )}
                          </TableCell>
                          )}
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
              )}
              {limit !== undefined && !showAll && ordered.length > limit && (
                <div>
                  <Button variant="secondary" onClick={() => setShowAll(true)}>
                    Show all {ordered.length}
                  </Button>
                </div>
              )}
            </div>
          )
        }}
      />
      {editable && editing && (
        <EditorDialog
          title={dialogTitle ? dialogTitle(editing.row) : editing.row ? `Edit ${rowLabel(editing.row)}` : (addLabel ?? 'Add')}
          fields={fields}
          initial={initial(editing.row)}
          extra={formExtra?.(editing.row)}
          onClose={() => setEditing(null)}
          onSave={async (values) => {
            const row = editing.row
            const key = (row ? identity(row) : null) ?? (newIdentity ? newIdentity(values) : newGuid())
            await save.mutateAsync({ identity: key, body: toBody(values, row, key) })
          }}
        />
      )}
    </Panel>
  )
}
