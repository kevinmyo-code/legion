import { useQuery } from '@tanstack/react-query'

import { api } from '@/api/client'

/**
 * The recruiter demo's three engine calls (recruiter-demo tickets 02 and 07).
 *
 * `POST /api/demo/enter` is in `schema.d.ts` and goes through the generated
 * client like everything else. `GET /api/demo/session` and `GET /api/demo/content`
 * are being built on the server branch in parallel and are NOT in
 * `openapi.yaml` yet, so they cannot go through `api.GET` without a cast. They
 * use `fetch` with the same two things the client's middleware adds
 * (same-origin cookies, `Accept: application/json`) and hand-written types that
 * follow the agreed contract word for word. Once the server's `openapi.yaml`
 * carries both paths, `npm run gen:api` and swap these two to `api.GET`; the
 * types below then come from the schema and this note goes.
 */

export interface DemoSession {
  role: string
  expires_at: string
  ref: string
}

export interface DemoContent {
  hero: { title: string; lead: string; facts: string[] }
  look_for: { title: string; body: string }[]
  work: {
    title: string
    intro: string
    stats: { value: string; label: string }[]
    diagram_svg: string
    diagram_caption: string
    cards: { title: string; body: string }[]
  }
  experience: { when: string; title: string; bullets: string[] }[]
  links: { linkedin: string; github: string; email: string }
  assets: { report: string | null; resume: string | null }
}

export type ContentResult = { kind: 'ok'; content: DemoContent } | { kind: 'not-set-up' }

async function getJson(path: string): Promise<Response> {
  return globalThis.fetch(
    new Request(path, { credentials: 'same-origin', headers: { Accept: 'application/json' } }),
  )
}

async function detailOf(response: Response): Promise<string | null> {
  try {
    const body = (await response.json()) as { detail?: unknown }
    return typeof body.detail === 'string' ? body.detail : null
  } catch {
    return null
  }
}

export const DEMO_SESSION_KEY = ['demo', 'session'] as const

/**
 * `GET /api/demo/session`: the recruiter visit this browser holds, or null.
 * 401 and 403 both mean "no visit" (a member's session is not a visit; an
 * anonymous browser has none). Anything else is a failure to ask, and throws.
 */
export async function fetchDemoSession(): Promise<DemoSession | null> {
  const response = await getJson('/api/demo/session')
  if (response.status === 401 || response.status === 403) return null
  if (!response.ok) throw new Error(`GET /api/demo/session answered ${response.status}`)
  return (await response.json()) as DemoSession
}

export function useDemoSession(enabled = true) {
  return useQuery({
    queryKey: DEMO_SESSION_KEY,
    queryFn: fetchDemoSession,
    enabled,
    retry: false,
  })
}

/** `GET /api/demo/content`: the recruiter section's words. 404 is "not set up yet", in words. */
export function useDemoContent(enabled: boolean) {
  return useQuery({
    queryKey: ['demo', 'content'],
    queryFn: async (): Promise<ContentResult> => {
      const response = await getJson('/api/demo/content')
      if (response.status === 404) return { kind: 'not-set-up' }
      if (!response.ok) {
        const detail = await detailOf(response)
        throw new Error(detail ?? `The engine answered ${response.status}.`)
      }
      return { kind: 'ok', content: (await response.json()) as DemoContent }
    },
    enabled,
    retry: false,
  })
}

export type EnterResult = { ok: true } | { ok: false; message: string }

let csrfPrimed = false

/**
 * `POST /api/demo/enter {code, ref?}`. The engine's refusals (wrong or retired
 * code, demo closed, too many tries, no demo household) are already sentences,
 * so they are shown as they come; a status with no words gets one here.
 */
export async function enterDemo(code: string, ref?: string): Promise<EnterResult> {
  try {
    if (!csrfPrimed) {
      // The enter door is CSRF-exempt by construction (no authentication
      // classes), but a fresh browser has no cookie yet and the later
      // assistant calls need one. Cheap, and failure is reported below.
      await api.GET('/api/auth/csrf').catch(() => undefined)
      csrfPrimed = true
    }
    const body = ref ? { code, ref } : { code }
    const { data, error, response } = await api.POST('/api/demo/enter', { body })
    if (data) return { ok: true }
    const detail = (error as { detail?: string } | undefined)?.detail
    if (detail) return { ok: false, message: detail }
    switch (response.status) {
      case 403:
        return { ok: false, message: 'That access code did not work. Nothing was created.' }
      case 429:
        return { ok: false, message: 'Too many tries from this network. Wait a while and try again.' }
      case 503:
        return { ok: false, message: 'The demo is not set up on this engine yet. Nothing was created.' }
      default:
        return { ok: false, message: `The engine could not start the demo (it answered ${response.status}).` }
    }
  } catch (networkError) {
    return {
      ok: false,
      message: `Could not reach the engine, so nothing was started. ${
        networkError instanceof Error ? networkError.message : String(networkError)
      }`,
    }
  }
}
