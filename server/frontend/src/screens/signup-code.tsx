import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'

import { AuthFrame } from '@/components/auth-frame'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/**
 * What a person typed or pasted, down to the code. An invite is usually opened
 * as a link, but one that was read aloud or pasted whole into this box should
 * work too, so a `/join/<code>` URL is accepted and its last segment taken.
 */
export function codeFrom(typed: string): string {
  const trimmed = typed.trim()
  const marker = '/join/'
  const at = trimmed.lastIndexOf(marker)
  const tail = at === -1 ? trimmed : trimmed.slice(at + marker.length)
  return tail.split(/[?#/]/)[0].trim()
}

/**
 * `/signup`: a box for an invite code, for when the link was read out or typed
 * rather than clicked. One code field and one fork, asked plainly: it hands the
 * code to `/join/<code>`, which asks the engine what the code is and says so.
 *
 * It does not create an account itself and says nothing about a code before the
 * engine has been asked: whether it works is `/join`'s sentence to give.
 */
export function SignupCodeScreen() {
  const navigate = useNavigate()
  const [typed, setTyped] = useState('')
  const code = codeFrom(typed)

  return (
    <AuthFrame>
      <Card className="w-full max-w-sm rounded-sheet">
        <CardHeader>
          <CardTitle className="text-2xl">Join with an invite code</CardTitle>
        </CardHeader>
        <CardContent>
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              if (code !== '') void navigate({ to: '/join/$code', params: { code } })
            }}
          >
            <p className="text-[0.9375rem] text-muted-foreground">
              Use the link you were sent, or paste the code or the whole link here.
            </p>
            <div className="flex flex-col gap-2">
              <Label htmlFor="invite-code">Invite code or link</Label>
              <Input
                id="invite-code"
                name="invite_code"
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                required
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
              />
            </div>
            <Button type="submit" size="lg" disabled={code === ''}>
              Continue
            </Button>
            <p className="text-center text-[0.9375rem] text-muted-foreground">
              Already have an account?{' '}
              <Link to="/login" className="font-medium text-primary underline-offset-4 hover:underline">
                Sign in instead
              </Link>
            </p>
          </form>
        </CardContent>
      </Card>
    </AuthFrame>
  )
}
