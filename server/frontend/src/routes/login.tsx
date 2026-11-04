import { useQueryClient } from '@tanstack/react-query'
import { Link, createFileRoute, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'

import { api } from '@/api/client'
import { AccessCodeForm } from '@/components/demo/access-code-form'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export const Route = createFileRoute('/login')({
  component: Login,
})

/**
 * `POST /api/auth/session/login` - a Django session cookie for the browser,
 * ticket 03. This is deliberately NOT the device-token `LoginRequest`: a
 * browser session and a phone's per-device token are different credentials
 * with different revocation stories (ADR 0044 rule 3), and this form only
 * ever asks for the session one.
 */
function Login() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      const { error: apiError, response } = await api.POST('/api/auth/session/login', {
        body: { email, password },
      })
      if (apiError || !response.ok) {
        if (response.status === 401) {
          setError('Wrong email or password.')
        } else if (response.status === 429) {
          setError('Too many attempts. Try again in a minute.')
        } else {
          setError(`Could not sign in (the engine answered ${response.status}).`)
        }
        return
      }
      // A fresh session invalidates every cached "signed out" answer from
      // before this submit, `auth/me` foremost - without this the Today
      // redirect below reads the stale cache and bounces straight back here.
      await queryClient.invalidateQueries()
      await navigate({ to: '/' })
    } catch (networkError) {
      setError(
        `Could not reach the engine. ${networkError instanceof Error ? networkError.message : String(networkError)}`,
      )
    } finally {
      setSubmitting(false)
    }
  }

  // The door (prototype D "typeset", 2026-11-03): the recruiter section's
  // look, because a recruiter's code is typed here too. `.recruiter` in
  // `index.css` holds every value: ruled fields, small-caps labels, ink buttons.
  return (
    <div className="recruiter rc-prose min-h-screen">
      <main className="rc-door">
        <span className="rc-caps">LEGION</span>
        <h1 className="rc-h1">Sign in</h1>
        <form className="flex flex-col" onSubmit={onSubmit}>
          <div className="rc-field">
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              name="email"
              type="email"
              autoComplete="username"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </div>
          <div className="rc-field">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>
          {error && <p className="text-destructive">{error}</p>}
          <Button type="submit" size="lg" className="self-start" disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </Button>
          <p className="rc-access mt-4">
            Have an invite? Use the link you were sent, or <Link to="/signup">enter its code</Link>.
          </p>
        </form>
        {/* Secondary to sign-in on purpose: members are who this page is for, and a
            recruiter's code is one line in a resume (recruiter-demo ticket 07). */}
        <section aria-labelledby="access-code-heading" className="rc-or">
          <h2 id="access-code-heading" className="rc-h3">
            Have an access code?
          </h2>
          <p className="rc-access">From Kevin's resume. It opens a demo on made-up data, with no account.</p>
          <AccessCodeForm
            onEntered={async () => {
              await queryClient.invalidateQueries()
              await navigate({ to: '/try' })
            }}
          />
        </section>
      </main>
    </div>
  )
}
