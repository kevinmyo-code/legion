import { Outlet, createFileRoute, useNavigate, useRouterState } from '@tanstack/react-router'
import { useEffect } from 'react'

import { useDemoSession } from '@/api/demo'
import { useMe } from '@/api/queries'
import { AppShell } from '@/components/app-shell'
import { RecruiterRefusal } from '@/components/demo/demo-banner'
import { recruiterRefusal } from '@/lib/recruiter'

/**
 * A pathless layout route (the leading underscore keeps it out of the URL -
 * TanStack Router's own convention) wrapping every screen that needs a
 * signed-in household: Today and Lists. `/login` sits outside it, since a
 * route that requires a session cannot also be the one offering to start
 * one.
 *
 * Three states, and they render three different things rather than one
 * being folded into another (CLAUDE.md section 1, "unreadable and empty are
 * different sentences", applied to auth instead of data): still asking the
 * engine, genuinely signed out, and could not reach the engine at all. Only
 * the middle one sends the person to `/login` - a network failure must not
 * look like a logged-out screen, or a real outage reads as "you got signed
 * out".
 */
export const Route = createFileRoute('/_authed')({
  component: AuthedLayout,
})

function AuthedLayout() {
  const me = useMe()
  const navigate = useNavigate()
  const pathname = useRouterState({ select: (state) => state.location.pathname })

  // A recruiter's live visit is signed in here like a member (ticket 13,
  // ADR 0069: its own copy of the demo household). A 403 from `auth/me` is a
  // visit that ended, or one the engine refuses: ask the demo door before
  // choosing where to send them, so a recruiter lands on `/try` instead of a
  // sign-in form that cannot help them. A plain 401 never asks.
  const refused = me.data?.signedIn === false && me.data.refused
  const demo = useDemoSession(refused)

  useEffect(() => {
    if (me.data?.signedIn !== false) return
    if (!me.data.refused) {
      void navigate({ to: '/login' })
      return
    }
    if (me.data.visitEnded) {
      void navigate({ to: '/try' })
      return
    }
    if (demo.isPending) return
    void navigate({ to: demo.data?.role === 'recruiter' ? '/try' : '/login' })
  }, [me.data, demo.isPending, demo.data, navigate])

  if (me.data === undefined) {
    // Only when the engine was never reached. A refetch that fails later (a
    // window focus during a cold start) keeps the last answer, so it never tears
    // down the screen underneath: a Tetris game, solo or raced, plays through it.
    if (me.isError) {
      return (
        <div className="flex min-h-screen items-center justify-center p-4 text-center text-sm">
          Could not reach the engine, so LEGION does not know whether you are
          signed in. {me.error?.message}
        </div>
      )
    }
    return (
      <div className="flex min-h-screen items-center justify-center p-4 text-sm text-muted-foreground">
        Asking the engine who you are…
      </div>
    )
  }

  if (me.data.signedIn === false) {
    // The redirect above is already in flight; render nothing rather than a
    // flash of the shell for a session that does not exist.
    return null
  }

  // A screen a visit may not use answers in words, not with a page of
  // refusals (the engine refuses its routes either way).
  const denied = me.data.me.role === 'recruiter' ? recruiterRefusal(pathname) : null

  return (
    <AppShell>{denied ? <RecruiterRefusal reason={denied} /> : <Outlet />}</AppShell>
  )
}
