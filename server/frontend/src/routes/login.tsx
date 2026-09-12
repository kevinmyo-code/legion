import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'

import { api } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
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

  return (
    <div className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Sign in</CardTitle>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={onSubmit}>
            <div className="flex flex-col gap-2">
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
            <div className="flex flex-col gap-2">
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
            {error && <p className="text-sm text-destructive">{error}</p>}
            <Button type="submit" disabled={submitting}>
              {submitting ? 'Signing in…' : 'Sign in'}
            </Button>
            <p className="text-center text-sm text-muted-foreground">
              Signup and invite codes are not built on the web yet - ask
              whoever set up your household for an account.
            </p>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
