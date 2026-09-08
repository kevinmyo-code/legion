import { createFileRoute } from '@tanstack/react-router'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export const Route = createFileRoute('/login')({
  component: Login,
})

/**
 * The form SHELL, and nothing behind it. `POST /api/auth/login` exists but
 * issues a device token for the phone; the browser needs a session cookie,
 * and ticket 03 is what adds that endpoint. Wiring this form to the token
 * endpoint in the meantime would store a bearer token in the page, which is
 * the shape ADR 0044 rule 3 chose per-device tokens to avoid.
 *
 * The submit button is disabled and says so in words rather than posting and
 * failing: CLAIMING nothing is the section 7 posture, and a form that looks
 * live but cannot sign anyone in is a claim.
 */
function Login() {
  return (
    <Card className="max-w-sm">
      <CardHeader>
        <CardTitle>Sign in</CardTitle>
      </CardHeader>
      <CardContent>
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => event.preventDefault()}
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="email">Email</Label>
            <Input id="email" name="email" type="email" autoComplete="username" />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
            />
          </div>
          <Button type="submit" disabled>
            Sign in
          </Button>
          <p className="text-muted-foreground text-sm">
            Not connected yet. Browser sign-in arrives with the accounts work
            (ticket 03); this form does nothing today.
          </p>
        </form>
      </CardContent>
    </Card>
  )
}
