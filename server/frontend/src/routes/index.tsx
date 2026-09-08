import { useQuery } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'

import { api } from '@/api/client'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

export const Route = createFileRoute('/')({
  component: Home,
})

/**
 * The scaffold's one real round trip. It exists to prove three things at once
 * that nothing else in this ticket can prove on its own: the client generated
 * from `openapi.yaml` types a real response, the session middleware attaches
 * cookies, and Vite's proxy (dev) or Django's own routing (production) puts
 * `/api/auth/me` in front of Django rather than in front of the SPA shell.
 *
 * Ticket 05 replaces this with the real Today screen.
 */
function Home() {
  const me = useQuery({
    queryKey: ['auth', 'me'],
    // `throwOnError` is off by default in TanStack Query, and this hook leans
    // on that: a 401 is not an exception here, it is the answer "nobody is
    // signed in". Only a transport failure reaches `error`.
    queryFn: async () => {
      const { data, error, response } = await api.GET('/api/auth/me')
      if (response.status === 401 || response.status === 403) {
        return { signedIn: false as const, status: response.status }
      }
      if (error || !data) {
        throw new Error(`GET /api/auth/me answered ${response.status}`)
      }
      return { signedIn: true as const, me: data }
    },
    retry: false,
  })

  return (
    <Card className="max-w-md">
      <CardHeader>
        <CardTitle>Session</CardTitle>
      </CardHeader>
      <CardContent className="text-sm">
        {/*
          Four states, not two. CLAUDE.md section 1 is explicit that
          "unreadable" and "empty" are different sentences: a request that
          never reached the engine must not render as "not signed in", which
          would tell the user their credentials are the problem when the
          server is simply down.
        */}
        {me.isPending && <p>Asking the engine who you are…</p>}
        {me.isError && (
          <p>
            Could not reach the engine, so LEGION does not know whether you are
            signed in. {me.error.message}
          </p>
        )}
        {me.data?.signedIn === false && <p>Not signed in</p>}
        {me.data?.signedIn === true && (
          <p>
            Signed in as {me.data.me.email} on {me.data.me.device_name || 'an unnamed device'}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
