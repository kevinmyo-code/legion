import { createFileRoute } from '@tanstack/react-router'

import { TryScreen } from '@/screens/try'

/**
 * `/try`: the recruiter section (recruiter-demo ticket 07). Public, outside
 * `_authed` on purpose: a recruiter's visit is refused by every route but the
 * assistant (ADR 0068), and someone with no visit yet needs the code box.
 *
 * `?code=&ref=` is the resume's direct link (Kevin, 2026-11-02). The code is
 * submitted once and taken out of the address bar at once; `ref` stays, since
 * it is only where the link came from.
 */
function text(value: unknown): string | undefined {
  if (typeof value === 'number') return String(value)
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined
}

export const Route = createFileRoute('/try')({
  validateSearch: (search: Record<string, unknown>): { code?: string; ref?: string } => ({
    code: text(search.code),
    ref: text(search.ref),
  }),
  component: Page,
})

function Page() {
  const { code, ref } = Route.useSearch()
  return <TryScreen code={code} refTag={ref} />
}
