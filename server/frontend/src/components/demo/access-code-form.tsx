import { useId, useState, type FormEvent } from 'react'

import { enterDemo } from '@/api/demo'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/**
 * The recruiter demo's code box (recruiter-demo ticket 07), on `/login` beside
 * sign-in and on `/try` when there is no visit yet. One form, so the two places
 * cannot disagree about what a wrong code says.
 *
 * It only enters the demo; where to go next is the caller's (`/login` moves to
 * `/try`, `/try` re-reads its own session). The engine's refusals are already
 * sentences and are shown as they come.
 *
 * Under the box: the demo's conversations may be recorded (Kevin, 2026-11-03,
 * "like how on a customer service call they say it may be recorded"). The
 * assistant is not named here: nobody is signed in yet, so the engine has not
 * said who it is, and a name is never typed into copy.
 */
export const DEMO_RECORDING_NOTICE = 'Conversations in the demo may be recorded to improve the assistant.'

export function AccessCodeForm({
  refTag,
  onEntered,
  initialError = null,
  label = 'Access code',
  submitLabel = 'Open the demo',
}: {
  /** Where the visitor came from (`/try?ref=acme`), passed through to the visit. */
  refTag?: string
  onEntered: () => void | Promise<void>
  /** A failure from before this form existed (an auto-submitted link). */
  initialError?: string | null
  label?: string
  submitLabel?: string
}) {
  const id = useId()
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(initialError)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmed = code.trim()
    if (!trimmed) {
      setError('Type the access code first.')
      return
    }
    setSubmitting(true)
    setError(null)
    const result = await enterDemo(trimmed, refTag)
    if (result.ok) {
      setCode('')
      await onEntered()
    } else {
      setError(result.message)
    }
    setSubmitting(false)
  }

  const errorId = `${id}-error`
  return (
    <form className="flex flex-col gap-2" onSubmit={onSubmit} noValidate>
      <Label htmlFor={`${id}-code`}>{label}</Label>
      <div className="flex flex-wrap gap-2">
        <Input
          id={`${id}-code`}
          name="access-code"
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          value={code}
          onChange={(event) => setCode(event.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          className="min-w-0 flex-1 basis-40"
        />
        <Button type="submit" variant="secondary" size="lg" className="h-12" disabled={submitting}>
          {submitting ? 'Checking…' : submitLabel}
        </Button>
      </div>
      {error && (
        <p id={errorId} role="alert" className="text-[0.9375rem] text-destructive">
          {error}
        </p>
      )}
      <p className="text-[0.8125rem] text-muted-foreground">{DEMO_RECORDING_NOTICE}</p>
    </form>
  )
}
