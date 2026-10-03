import { useState, type ComponentProps, type FormEvent, type ReactNode } from 'react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { ErrorSentence } from '@/components/workbench/page'

/**
 * One form for every table the web lets a person write.
 *
 * Eleven tables get a create form and most get an edit form, and they differ
 * only in which fields they carry. So a form is a list of `FieldSpec`s, the
 * values are held as strings exactly as an input holds them, and each screen
 * turns the strings into its table's typed body in its own `onSave`. Typing
 * lives with the table, not here.
 *
 * **Saving is not optimistic.** The dialog stays open while the write runs, the
 * button says "Saving", and if the engine refuses, the dialog stays open and
 * shows the engine's own sentence under a line that says nothing was saved. It
 * closes only when the engine said yes (CLAUDE.md section 7's outcome-verb rule:
 * nothing claims a save that did not happen).
 *
 * Validation here is only what saves a round trip: a required field is filled
 * and a number is a number. The engine's rules (ranges, uniqueness, blank
 * checks) stay the authority, and its 400 sentence is shown verbatim.
 */

export type FieldKind = 'text' | 'number' | 'date' | 'datetime' | 'select' | 'textarea'

export interface FieldSpec {
  name: string
  label: string
  kind: FieldKind
  required?: boolean
  options?: readonly { value: string; label: string }[]
  hint?: ReactNode
  placeholder?: string
  step?: string
  min?: string
  max?: string
  /** Shown but not editable: a key the engine will not let a form change. */
  readOnly?: boolean
}

export type Values = Record<string, string>

const SELECT_CLASS =
  'h-12 w-full min-w-0 rounded-t-control rounded-b-md border-0 border-b-2 border-outline bg-surface-2 px-4 text-base outline-none transition-colors focus-visible:border-primary focus-visible:bg-surface-3 disabled:opacity-50'

/** A native select, styled like the other fields. Native on purpose: the
 * platform's own control is the accessible one, and it is keyboard- and
 * screen-reader-correct without anything of ours to get wrong. */
export function SelectInput({
  id,
  value,
  onChange,
  options,
  ...rest
}: {
  id?: string
  value: string
  onChange: (value: string) => void
  options: readonly { value: string; label: string }[]
} & Omit<ComponentProps<'select'>, 'onChange' | 'value'>) {
  return (
    <select
      id={id}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      className={SELECT_CLASS}
      {...rest}
    >
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  )
}

function inputType(kind: FieldKind): string {
  switch (kind) {
    case 'number':
      return 'number'
    case 'date':
      return 'date'
    case 'datetime':
      return 'datetime-local'
    default:
      return 'text'
  }
}

function Field({
  spec,
  value,
  onChange,
  formId,
}: {
  spec: FieldSpec
  value: string
  onChange: (value: string) => void
  formId: string
}) {
  const id = `${formId}-${spec.name}`
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id} className="text-[0.8125rem] text-muted-foreground">
        {spec.label}
        {spec.required ? '' : ' (optional)'}
      </Label>
      {spec.kind === 'select' ? (
        <SelectInput
          id={id}
          value={value}
          onChange={onChange}
          options={spec.options ?? []}
          disabled={spec.readOnly}
        />
      ) : spec.kind === 'textarea' ? (
        <Textarea
          id={id}
          value={value}
          placeholder={spec.placeholder}
          onChange={(event) => onChange(event.target.value)}
          disabled={spec.readOnly}
        />
      ) : (
        <Input
          id={id}
          type={inputType(spec.kind)}
          inputMode={spec.kind === 'number' ? 'decimal' : undefined}
          value={value}
          placeholder={spec.placeholder}
          step={spec.step}
          min={spec.min}
          max={spec.max}
          readOnly={spec.readOnly}
          disabled={spec.readOnly}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
      {spec.hint && <p className="text-[0.8125rem] text-muted-foreground">{spec.hint}</p>}
    </div>
  )
}

/** The first problem a person can fix without asking the engine, or null. */
export function firstProblem(fields: readonly FieldSpec[], values: Values): string | null {
  for (const spec of fields) {
    const value = (values[spec.name] ?? '').trim()
    if (spec.required && value === '') return `${spec.label} is needed.`
    if (spec.kind === 'number' && value !== '' && !Number.isFinite(Number(value))) {
      return `${spec.label} must be a number.`
    }
  }
  return null
}

export function EditorDialog({
  title,
  description,
  fields,
  initial,
  submitLabel = 'Save',
  extra,
  onSave,
  onClose,
}: {
  title: string
  description?: ReactNode
  fields: readonly FieldSpec[]
  initial: Values
  submitLabel?: string
  /** Anything to say above the buttons that is not a field. */
  extra?: ReactNode
  /** Resolve only if the engine accepted the write; reject with its sentence. */
  onSave: (values: Values) => Promise<void>
  onClose: () => void
}) {
  const [values, setValues] = useState<Values>(initial)
  const [saving, setSaving] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const formId = `form-${title.replace(/\W+/g, '-').toLowerCase()}`

  async function submit(event: FormEvent) {
    event.preventDefault()
    const local = firstProblem(fields, values)
    if (local) {
      setProblem(local)
      return
    }
    setProblem(null)
    setSaving(true)
    try {
      await onSave(values)
      onClose()
    } catch (error) {
      setProblem(error instanceof Error ? error.message : 'Nothing was saved.')
      setSaving(false)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !saving && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description ? (
            <DialogDescription>{description}</DialogDescription>
          ) : (
            <DialogDescription className="sr-only">{title}</DialogDescription>
          )}
        </DialogHeader>
        <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
          {fields.map((spec) => (
            <Field
              key={spec.name}
              spec={spec}
              formId={formId}
              value={values[spec.name] ?? ''}
              onChange={(value) => setValues((current) => ({ ...current, [spec.name]: value }))}
            />
          ))}
          {extra}
          {problem && <ErrorSentence>{problem}</ErrorSentence>}
          <DialogFooter>
            <Button type="button" variant="secondary" size="lg" onClick={onClose} disabled={saving}>
              Cancel
            </Button>
            <Button type="submit" size="lg" disabled={saving}>
              {saving ? 'Saving' : submitLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
