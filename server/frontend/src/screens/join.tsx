import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'

import type { components } from '@/api/schema'
import { InviteThrottled, SignupRefused, signUp, useInvitePreview } from '@/api/settings'
import { AuthFrame } from '@/components/auth-frame'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorSentence } from '@/components/workbench/page'

type Preview = components['schemas']['InvitePreview']

/**
 * Why a code that exists cannot be used, in a sentence of its own for each of the
 * three ways (spec D12; `docs/design/join-invite.md`: a parent opening an old
 * text is exactly who this serves, and "something went wrong" tells them nothing).
 *
 * The engine's preview says WHETHER a code is live and gives its `reason`; the
 * kind is read from the structured fields it also sends (an expiry in the past, no
 * uses left) rather than by matching on the reason's wording, so a rewording on
 * the server cannot silently turn "expired" into "revoked" here. What is left
 * over is a revoked code.
 */
export type DeadKind = 'expired' | 'used-up' | 'revoked'

export function deadKind(preview: Preview, now = Date.now()): DeadKind {
  if (new Date(preview.expires_at).getTime() <= now) return 'expired'
  if (preview.uses_left <= 0) return 'used-up'
  return 'revoked'
}

function whoToAsk(preview: Preview): string {
  return preview.household_name
    ? `Ask ${preview.household_name} to send a new one.`
    : 'Ask whoever sent it for a new one.'
}

const DEAD_HEADLINE: Record<DeadKind, string> = {
  expired: 'This invite has expired.',
  'used-up': 'This invite has already been used by everyone it was for.',
  revoked: 'This invite was cancelled by the person who sent it.',
}

/**
 * `/join/<code>`: what the invite is for FIRST, then the form (the order the
 * research found both Apple and Life360 get backwards: identity before trust).
 *
 * It asks the engine what the code will do without spending it, and then shows
 * exactly one of: still asking, could not reach the engine, no such code, a code
 * that is dead for one of three reasons, or the signup form. Each is a different
 * sentence and none is folded into another ("could not check" must never read as
 * "this invite is bad").
 *
 * A code that FOUNDS a household (rather than joining one) swaps the line naming
 * the household for a field to name it, on the same screen: one code, one fork,
 * decided by what the code is, not by a route the person must pick blind.
 */
export function JoinScreen({ code }: { code: string }) {
  const lookup = useInvitePreview(code)

  if (lookup.isPending) {
    return (
      <AuthFrame>
        <Card className="w-full max-w-sm rounded-sheet">
          <CardContent>
            <p role="status" className="text-[0.9375rem] text-muted-foreground">
              Checking this invite…
            </p>
          </CardContent>
        </Card>
      </AuthFrame>
    )
  }

  if (lookup.isError) {
    return (
      <AuthFrame>
        <Card className="w-full max-w-sm rounded-sheet">
          <CardHeader>
            <CardTitle className="text-xl">Could not check this invite</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <ErrorSentence>
              {lookup.error instanceof InviteThrottled
                ? lookup.error.message
                : 'Could not reach the engine, so this invite could not be checked. Nothing was spent.'}
            </ErrorSentence>
            <Button size="lg" onClick={() => void lookup.refetch()}>
              Try again
            </Button>
          </CardContent>
        </Card>
      </AuthFrame>
    )
  }

  if (!lookup.data.found) {
    return (
      <AuthFrame>
        <Card className="w-full max-w-sm rounded-sheet">
          <CardHeader>
            <CardTitle className="text-xl">There is no invite with that code.</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <p className="text-[0.9375rem] text-muted-foreground">
              Check the link for a typo, or ask whoever sent it for a new one. Nothing was spent.
            </p>
            <Button asChild size="lg" variant="secondary">
              <Link to="/signup">Type a code instead</Link>
            </Button>
            <SignInInstead />
          </CardContent>
        </Card>
      </AuthFrame>
    )
  }

  const preview = lookup.data.preview
  if (!preview.live) {
    const kind = deadKind(preview)
    return (
      <AuthFrame>
        <Card className="w-full max-w-sm rounded-sheet">
          <CardHeader>
            <CardTitle className="text-xl">{DEAD_HEADLINE[kind]}</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <p className="text-[0.9375rem] text-muted-foreground">{whoToAsk(preview)}</p>
            <SignInInstead primary />
          </CardContent>
        </Card>
      </AuthFrame>
    )
  }

  return <SignupForm code={code} preview={preview} />
}

function SignInInstead({ primary = false }: { primary?: boolean }) {
  return primary ? (
    <Button asChild size="lg">
      <Link to="/login">Have an account? Sign in</Link>
    </Button>
  ) : (
    <p className="text-center text-[0.9375rem] text-muted-foreground">
      Already have an account?{' '}
      <Link to="/login" className="font-medium text-primary underline-offset-4 hover:underline">
        Sign in instead
      </Link>
    </p>
  )
}

function expiryLine(iso: string): string {
  const day = new Date(iso).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  })
  return `This invite is good until ${day}.`
}

function SignupForm({ code, preview }: { code: string; preview: Preview }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const founding = preview.creates_household
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [householdName, setHouseholdName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [halfDone, setHalfDone] = useState<string | null>(null)

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setProblem(null)
    setFields({})
    try {
      const result = await signUp({
        name: name.trim(),
        email: email.trim(),
        password,
        inviteCode: code,
        householdName: householdName.trim(),
      })
      if (!result.signedIn) {
        // Signup worked and the sign-in did not: the account exists, and saying
        // "signup failed" would send them to make a second one and be told the
        // email is taken.
        setHalfDone(result.problem ?? 'Could not sign in.')
        return
      }
      // A fresh session invalidates every cached "signed out" answer, `auth/me`
      // foremost. Not the invite lookups: this code was just used, and asking the
      // engine about it again would spend the throttle to learn nothing.
      await queryClient.invalidateQueries({
        predicate: (query) => !(query.queryKey[0] === 'auth' && query.queryKey[1] === 'invite'),
      })
      await navigate({ to: '/' })
    } catch (error) {
      if (error instanceof SignupRefused) {
        setProblem(error.message)
        setFields(error.fields)
      } else {
        setProblem(error instanceof Error ? error.message : 'No account was created.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (halfDone) {
    return (
      <AuthFrame>
        <Card className="w-full max-w-sm rounded-sheet">
          <CardHeader>
            <CardTitle className="text-xl">Your account is created</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <p className="text-[0.9375rem]">
              But signing you in did not work: {halfDone} Sign in with the email and password you
              just chose.
            </p>
            <Button asChild size="lg">
              <Link to="/login">Sign in</Link>
            </Button>
          </CardContent>
        </Card>
      </AuthFrame>
    )
  }

  return (
    <AuthFrame>
      <Card className="w-full max-w-sm rounded-sheet">
        <CardHeader>
          <p className="text-[0.9375rem] text-muted-foreground">
            {founding ? 'You have been invited to start a household' : 'You have been invited to join'}
          </p>
          <h1 className="font-heading text-[1.75rem] leading-tight font-medium">
            {founding ? 'A new household' : preview.household_name}
          </h1>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={onSubmit}>
            <p className="text-[0.9375rem] text-muted-foreground">
              {founding
                ? 'You will be its owner. You name it below, and you can invite the others yourself.'
                : 'Joining lets you see the household’s shared calendar and lists. Anything you mark Only me stays yours.'}
            </p>
            {founding && (
              <Field id="household-name" label="Name your household" error={fields.household_name}>
                <Input
                  id="household-name"
                  name="household_name"
                  required
                  value={householdName}
                  onChange={(event) => setHouseholdName(event.target.value)}
                />
              </Field>
            )}
            <Field id="name" label="Your name" error={fields.name}>
              <Input
                id="name"
                name="name"
                autoComplete="name"
                required
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </Field>
            <Field id="email" label="Email" error={fields.email}>
              <Input
                id="email"
                name="email"
                type="email"
                autoComplete="email"
                required
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </Field>
            <Field id="password" label="Password" error={fields.password}>
              <Input
                id="password"
                name="password"
                type="password"
                autoComplete="new-password"
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </Field>
            {problem && <ErrorSentence>{problem}</ErrorSentence>}
            <Button type="submit" size="lg" disabled={submitting}>
              {submitting
                ? 'Creating your account…'
                : founding
                  ? 'Create the household'
                  : 'Join the household'}
            </Button>
            <p className="text-center text-[0.8125rem] text-muted-foreground">
              {expiryLine(preview.expires_at)}
            </p>
            <SignInInstead />
          </form>
        </CardContent>
      </Card>
    </AuthFrame>
  )
}

function Field({
  id,
  label,
  error,
  children,
}: {
  id: string
  label: string
  error?: string
  children: React.ReactNode
}) {
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      {children}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-[0.8125rem] text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
