import { Lock, Users } from 'lucide-react'
import { Dialog as DialogPrimitive, RadioGroup, ToggleGroup } from 'radix-ui'
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'

import {
  useCreateEvent,
  useDeleteEvent,
  useEditOccurrence,
  useSkipOccurrence,
  useUpdateEvent,
} from '@/api/mutations'
import type { SheetRequest } from '@/components/event-sheet-context'
import { Button } from '@/components/ui/button'
import { Dialog, DialogDescription, DialogOverlay, DialogPortal, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { SelectInput } from '@/components/workbench/record-form'
import { ErrorSentence } from '@/components/workbench/page'
import {
  REMINDER_OPTIONS,
  WEEKDAY_SHORT,
  blankForm,
  fieldsForSeries,
  fieldsFromForm,
  formFromOccurrence,
  validateForm,
  type EndsChoice,
  type EventForm,
  type RepeatChoice,
} from '@/lib/event-form'
import { dateForEpochDay, todayEpochDay } from '@/lib/day'
import { cn } from '@/lib/utils'
import { useSurface } from '@/lib/surface'
import { PRIVATE_WORDS, SHARED_WORDS } from '@/lib/visibility'

/**
 * The one place an event is made, changed or removed (spec D8): a bottom sheet
 * at family width and a right-hand panel at workbench width, the same form in
 * both so the two paths cannot behave differently (ADR 0035).
 *
 * **Saving is not optimistic.** The button says "Saving" while the engine
 * answers; if the engine refuses, the sheet stays open and shows its sentence
 * verbatim under a line that says what did not happen. It closes only when the
 * engine said yes.
 *
 * **A repeating series asks which.** Saving or deleting one occurrence of a
 * series asks "Just this one" or "All of them" before anything is written
 * (spec D4). "Just this one" skips that date and, on an edit, saves the changed
 * copy as a one-off keyed `<series id>:<date>`; "All of them" writes the series.
 *
 * Validation here is only what saves a round trip and mirrors the engine's:
 * a title, an end after the start, days for a weekly repeat. The engine stays
 * the authority.
 */

const REPEAT_CHOICES: readonly { value: RepeatChoice; label: string }[] = [
  { value: 'never', label: 'Never' },
  { value: 'daily', label: 'Daily' },
  { value: 'weekly', label: 'Weekly' },
  { value: 'monthly', label: 'Monthly' },
  { value: 'yearly', label: 'Yearly' },
]

const ENDS_CHOICES: readonly { value: string; label: string }[] = [
  { value: 'never', label: 'Never' },
  { value: 'on_date', label: 'On a date' },
  { value: 'after', label: 'After a number of times' },
]

type Prompt = null | 'save-scope' | 'delete-scope' | 'delete-confirm'

function initialForm(request: SheetRequest): EventForm {
  if (request.kind === 'edit') return formFromOccurrence(request.occurrence)
  const today = todayEpochDay()
  const day = request.day ?? today
  let startMinutes = request.startMinutes
  if (startMinutes === undefined) {
    // The next whole hour for today, nine o'clock for any other day.
    const now = new Date()
    startMinutes = day === today ? Math.min((now.getHours() + 1) * 60, 22 * 60) : 9 * 60
  }
  return blankForm(day, startMinutes)
}

function Field({ label, htmlFor, children }: { label: string; htmlFor?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={htmlFor} className="text-[0.8125rem] text-muted-foreground">
        {label}
      </Label>
      {children}
    </div>
  )
}

const CHIP =
  'inline-flex h-11 min-w-0 flex-1 items-center justify-center rounded-full border border-outline px-3 text-[0.8125rem] font-medium whitespace-nowrap outline-none transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary data-[state=checked]:border-transparent data-[state=checked]:bg-primary-container data-[state=checked]:text-primary-container-foreground data-[state=on]:border-transparent data-[state=on]:bg-primary-container data-[state=on]:text-primary-container-foreground'

export function EventSheet({ request, onClose }: { request: SheetRequest; onClose: () => void }) {
  const surface = useSurface()
  const edit = request.kind === 'edit' ? request.occurrence : null
  const series = edit !== null && edit.recurring
  const taskLike = edit?.event.kind === 'task'

  const [form, setForm] = useState<EventForm>(() => initialForm(request))
  const [prompt, setPrompt] = useState<Prompt>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const footer = useRef<HTMLDivElement>(null)

  const create = useCreateEvent()
  const update = useUpdateEvent()
  const remove = useDeleteEvent()
  const skip = useSkipOccurrence()
  const editOne = useEditOccurrence()

  // A question or a refusal appears at the foot of the form; bring it into view
  // (`?.` because jsdom has no layout and no `scrollIntoView`).
  useEffect(() => {
    if (prompt !== null || problem !== null) footer.current?.scrollIntoView?.({ block: 'nearest' })
  }, [prompt, problem])

  const set = <K extends keyof EventForm>(key: K, value: EventForm[K]) =>
    setForm((current) => ({ ...current, [key]: value }))

  /** Run one write; close on success, stay open and say why on refusal. */
  async function run(write: () => Promise<unknown>) {
    setBusy(true)
    setProblem(null)
    try {
      await write()
      onClose()
    } catch (error) {
      setProblem(error instanceof Error ? error.message : 'Nothing was saved.')
      setPrompt(null)
      setBusy(false)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const local = validateForm(form)
    if (local) {
      setProblem(local)
      return
    }
    setProblem(null)
    if (series) {
      setPrompt('save-scope')
      return
    }
    void save('all')
  }

  async function save(scope: 'one' | 'all') {
    if (edit === null) {
      await run(() =>
        create.mutateAsync({ ...fieldsFromForm(form), kind: 'event', visibility: form.visibility }),
      )
      return
    }
    const { event } = edit
    const visibilityChanged = form.visibility !== (event.visibility === 'private' ? 'private' : 'shared')
    if (series && scope === 'one') {
      await run(() =>
        editOne.mutateAsync({
          seriesId: event.id,
          date: edit.date,
          event: { ...fieldsFromForm(form, false), kind: event.kind, visibility: form.visibility },
        }),
      )
      return
    }
    const fields = series ? fieldsForSeries(form, edit) : fieldsFromForm(form)
    await run(() =>
      update.mutateAsync({
        id: event.id,
        fields: visibilityChanged ? { ...fields, visibility: form.visibility } : fields,
      }),
    )
  }

  async function deleteScoped(scope: 'one' | 'all') {
    if (edit === null) return
    if (series && scope === 'one') {
      await run(() => skip.mutateAsync({ id: edit.event.id, date: edit.date, verb: 'deleted' }))
      return
    }
    await run(() => remove.mutateAsync(edit.event.id))
  }

  const heading = edit === null ? 'New event' : taskLike ? 'Edit task' : 'Edit event'
  const dayLabel = edit
    ? dateForEpochDay(edit.day).toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
    : ''

  return (
    <Dialog open onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogPortal>
        <DialogOverlay />
        <DialogPrimitive.Content
          data-slot="event-sheet"
          data-surface={surface}
          className={cn(
            'fixed z-50 flex flex-col overflow-y-auto bg-popover text-sm text-popover-foreground shadow-lg outline-none duration-200 data-open:animate-in data-closed:animate-out',
            surface === 'workbench'
              ? 'inset-y-0 right-0 w-[440px] max-w-full rounded-l-sheet px-7 pt-7 data-open:slide-in-from-right data-closed:slide-out-to-right'
              : 'inset-x-0 bottom-0 max-h-[92dvh] rounded-t-sheet px-5 pt-3 data-open:slide-in-from-bottom data-closed:slide-out-to-bottom',
          )}
        >
          {surface === 'family' && (
            <div aria-hidden="true" className="mx-auto mb-3 h-1 w-9 shrink-0 rounded-full bg-outline-variant" />
          )}
          <DialogTitle className="mb-4 text-[1.375rem] leading-tight font-medium">{heading}</DialogTitle>
          <DialogDescription className="sr-only">
            {edit === null ? 'Add an event to the household calendar.' : `Change or remove ${edit.event.title}.`}
          </DialogDescription>

          <form onSubmit={submit} noValidate className="flex flex-col gap-4">
            <Field label="Title" htmlFor="event-title">
              <Input
                id="event-title"
                value={form.title}
                placeholder="What is happening?"
                autoComplete="off"
                onChange={(e) => set('title', e.target.value)}
              />
            </Field>

            <div className="flex min-h-11 items-center justify-between gap-3">
              <Label htmlFor="event-all-day" className="text-[0.9375rem] font-normal text-foreground">
                All day
              </Label>
              <Switch id="event-all-day" checked={form.allDay} onCheckedChange={(on) => set('allDay', on)} />
            </div>

            <div className={cn('grid gap-3', form.allDay ? 'grid-cols-1' : 'grid-cols-2')}>
              <Field label="Date" htmlFor="event-date">
                <Input id="event-date" type="date" value={form.date} onChange={(e) => set('date', e.target.value)} />
              </Field>
              {!form.allDay && (
                <Field label="Starts" htmlFor="event-start">
                  <Input
                    id="event-start"
                    type="time"
                    value={form.startTime}
                    onChange={(e) => set('startTime', e.target.value)}
                  />
                </Field>
              )}
            </div>
            {!form.allDay && (
              <Field label="Ends (optional)" htmlFor="event-end">
                <Input id="event-end" type="time" value={form.endTime} onChange={(e) => set('endTime', e.target.value)} />
              </Field>
            )}

            <Field label="Repeats">
              <RadioGroup.Root
                aria-label="Repeats"
                value={form.repeat}
                onValueChange={(value) => set('repeat', value as RepeatChoice)}
                className="flex flex-wrap gap-1.5"
              >
                {REPEAT_CHOICES.map((choice) => (
                  <RadioGroup.Item key={choice.value} value={choice.value} className={CHIP}>
                    {choice.label}
                  </RadioGroup.Item>
                ))}
              </RadioGroup.Root>
            </Field>

            {form.repeat === 'weekly' && (
              <Field label="On these days">
                <ToggleGroup.Root
                  type="multiple"
                  aria-label="On these days"
                  value={form.weekdays.map(String)}
                  onValueChange={(days) => set('weekdays', days.map(Number).sort((a, b) => a - b))}
                  className="flex gap-1"
                >
                  {WEEKDAY_SHORT.map((name, index) => (
                    <ToggleGroup.Item key={name} value={String(index)} aria-label={name} className={cn(CHIP, 'px-0')}>
                      {name.slice(0, 2)}
                    </ToggleGroup.Item>
                  ))}
                </ToggleGroup.Root>
              </Field>
            )}

            {form.repeat !== 'never' && (
              <>
                {form.every > 1 && (
                  <p className="text-[0.8125rem] text-muted-foreground">
                    This repeat runs every {form.every}
                    {form.repeat === 'daily'
                      ? ' days'
                      : form.repeat === 'weekly'
                        ? ' weeks'
                        : form.repeat === 'monthly'
                          ? ' months'
                          : ' years'}
                    . Saving keeps that.
                  </p>
                )}
                <div className={cn('grid gap-3', form.ends === 'never' ? 'grid-cols-1' : 'grid-cols-2')}>
                  <Field label="Repeat ends" htmlFor="event-ends">
                    <SelectInput
                      id="event-ends"
                      value={form.ends}
                      onChange={(value) => set('ends', value as EndsChoice)}
                      options={ENDS_CHOICES}
                    />
                  </Field>
                  {form.ends === 'on_date' && (
                    <Field label="Last day" htmlFor="event-ends-on">
                      <Input
                        id="event-ends-on"
                        type="date"
                        value={form.endsOn}
                        onChange={(e) => set('endsOn', e.target.value)}
                      />
                    </Field>
                  )}
                  {form.ends === 'after' && (
                    <Field label="Times" htmlFor="event-ends-after">
                      <Input
                        id="event-ends-after"
                        type="number"
                        inputMode="numeric"
                        min={1}
                        value={form.endsAfter}
                        onChange={(e) => set('endsAfter', e.target.value)}
                      />
                    </Field>
                  )}
                </div>
              </>
            )}

            <Field label="Reminder" htmlFor="event-remind">
              <SelectInput
                id="event-remind"
                value={form.remind}
                onChange={(value) => set('remind', value)}
                options={REMINDER_OPTIONS}
              />
            </Field>

            <Field label="Location (optional)" htmlFor="event-location">
              <Input
                id="event-location"
                value={form.location}
                autoComplete="off"
                onChange={(e) => set('location', e.target.value)}
              />
            </Field>
            <Field label="Notes (optional)" htmlFor="event-notes">
              <Textarea id="event-notes" value={form.notes} onChange={(e) => set('notes', e.target.value)} />
            </Field>

            <Field label="Who sees it">
              <RadioGroup.Root
                aria-label="Who sees it"
                value={form.visibility}
                onValueChange={(value) => set('visibility', value as 'shared' | 'private')}
                className="grid grid-cols-2 overflow-hidden rounded-full border border-outline"
              >
                <RadioGroup.Item
                  value="shared"
                  className="inline-flex h-12 items-center justify-center gap-2 text-[0.9375rem] font-medium outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary data-[state=checked]:bg-shared data-[state=checked]:text-shared-foreground"
                >
                  <Users className="size-4" aria-hidden="true" />
                  {SHARED_WORDS}
                </RadioGroup.Item>
                <RadioGroup.Item
                  value="private"
                  className="inline-flex h-12 items-center justify-center gap-2 border-l border-outline text-[0.9375rem] font-medium text-muted-foreground outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary data-[state=checked]:bg-surface-3 data-[state=checked]:text-foreground"
                >
                  <Lock className="size-4" aria-hidden="true" />
                  Only me
                </RadioGroup.Item>
              </RadioGroup.Root>
              <p className="text-[0.8125rem] text-muted-foreground">
                {form.visibility === 'shared'
                  ? 'Everyone in the household sees this.'
                  : `${PRIVATE_WORDS}. Nobody else in the household sees this.`}
              </p>
            </Field>

            {/* The sentence, the question and the buttons stay in reach however long the
                form is: a Save that has scrolled out of a 900 px panel is a Save nobody
                finds, and an error below the fold is an error nobody reads. */}
            <div
              ref={footer}
              className={cn(
                'sticky bottom-0 flex flex-col gap-3 bg-popover pt-3',
                surface === 'workbench'
                  ? '-mx-7 px-7 pb-7'
                  : '-mx-5 px-5 pb-[calc(1.25rem+env(safe-area-inset-bottom))]',
              )}
            >
              {problem && <ErrorSentence>{problem}</ErrorSentence>}

              {prompt === 'save-scope' && (
                <ScopePrompt
                  question={`This is one of a repeating series. Change just the ${dayLabel} one, or all of them?`}
                  busy={busy}
                  onOne={() => void save('one')}
                  onAll={() => void save('all')}
                  onBack={() => setPrompt(null)}
                />
              )}
              {prompt === 'delete-scope' && (
                <ScopePrompt
                  question={`This is one of a repeating series. Delete just the ${dayLabel} one, or all of them?`}
                  destructive
                  busy={busy}
                  onOne={() => void deleteScoped('one')}
                  onAll={() => void deleteScoped('all')}
                  onBack={() => setPrompt(null)}
                />
              )}
              {prompt === 'delete-confirm' && edit && (
                <div role="group" aria-label="Confirm delete" className="flex flex-col gap-3 rounded-card bg-surface-2 p-4">
                  <p className="text-[0.9375rem]">Delete &ldquo;{edit.event.title}&rdquo;? It leaves the calendar for everyone who sees it.</p>
                  <div className="flex justify-end gap-2">
                    <Button type="button" variant="secondary" disabled={busy} onClick={() => setPrompt(null)}>
                      Keep it
                    </Button>
                    <Button type="button" variant="destructive" disabled={busy} onClick={() => void deleteScoped('all')}>
                      {busy ? 'Deleting' : 'Delete event'}
                    </Button>
                  </div>
                </div>
              )}

              {prompt === null && (
                <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
                  {edit ? (
                    <Button
                      type="button"
                      variant="destructive"
                      disabled={busy}
                      onClick={() => setPrompt(series ? 'delete-scope' : 'delete-confirm')}
                    >
                      Delete
                    </Button>
                  ) : (
                    <span />
                  )}
                  <div className="flex items-center gap-2">
                    <Button type="button" variant="ghost" size="lg" disabled={busy} onClick={onClose}>
                      Cancel
                    </Button>
                    <Button type="submit" size="lg" disabled={busy}>
                      {busy ? 'Saving' : edit === null ? 'Add' : 'Save'}
                    </Button>
                  </div>
                </div>
              )}
            </div>
          </form>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  )
}

function ScopePrompt({
  question,
  destructive = false,
  busy,
  onOne,
  onAll,
  onBack,
}: {
  question: string
  destructive?: boolean
  busy: boolean
  onOne: () => void
  onAll: () => void
  onBack: () => void
}) {
  return (
    <div role="group" aria-label="Which events" className="flex flex-col gap-3 rounded-card bg-surface-2 p-4">
      <p className="text-[0.9375rem]">{question}</p>
      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="ghost" disabled={busy} onClick={onBack}>
          Back
        </Button>
        <Button type="button" variant={destructive ? 'destructive' : 'secondary'} disabled={busy} onClick={onOne}>
          Just this one
        </Button>
        <Button type="button" variant={destructive ? 'destructive' : 'default'} disabled={busy} onClick={onAll}>
          All of them
        </Button>
      </div>
    </div>
  )
}
