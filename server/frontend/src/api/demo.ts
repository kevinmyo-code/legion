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

/** One "LEGION on the phone" clip. With no `video` yet the page draws a placeholder. */
export interface DemoPhoneClip {
  title: string
  body: string
  video?: string
  poster?: string
  /** A WebVTT file, shown as the clip's captions by default. */
  captions?: string
}

export interface DemoPhone {
  title: string
  intro: string
  clips: DemoPhoneClip[]
  /** The whole recording the clips are cut from, linked under the section. */
  full_take?: string
}

export interface DemoLegion {
  title: string
  intro: string
  diagram_svg?: string
  diagram_caption?: string
  parts: { title: string; body: string }[]
}

/**
 * A clip slot that may not be filled yet: with `video` the page plays it, without one it says
 * `note` ("coming soon"), with neither it draws nothing.
 */
export interface DemoMedia {
  video?: string
  poster?: string
  captions?: string
  note?: string
}

/** One line of the Projects strip. `href` is `#anchor` or a link out; absent, the line stands alone. */
export interface DemoProject {
  title: string
  line: string
  href?: string
  media?: DemoMedia
}

/** A project section below LEGION's: the `legion` shape plus the anchor `id` and a media slot. */
export interface DemoProjectSection extends DemoLegion {
  id: string
  media?: DemoMedia
}

export interface DemoContent {
  /** The page's header line. Absent: the header says "LEGION". */
  name?: string
  /** The Projects strip under the hero. Absent (older content): no strip. */
  projects?: DemoProject[]
  /** Project sections after LEGION's (and its phone section). Absent: none. */
  project_sections?: DemoProjectSection[]
  /** "LEGION on the phone". Absent: the section is not drawn. */
  phone?: DemoPhone
  /** What LEGION is and how it is built; optional, absent draws nothing. */
  legion?: DemoLegion
  hero: { title: string; lead: string; facts: string[] }
  look_for: { title: string; body: string }[]
  /** Absent in a version that leaves the work section out (the LinkedIn one). */
  work?: {
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

/**
 * A link's `ref` as the engine takes it: `^[A-Za-z0-9_.-]{1,64}$` (`demo/views.py`), else the
 * whole enter is refused with a 400 and the visitor never gets in. A hand-typed or pasted link
 * (`?ref=Acme Corp`, `?ref=acme%26co`) must still open the demo, so it is cut to that shape
 * here: lower case, every other run of characters one `-`, at most 64. Nothing left: no ref.
 */
export function cleanRef(ref: string | undefined): string | undefined {
  if (!ref) return undefined
  const slug = ref
    .toLowerCase()
    .replace(/[^a-z0-9_.-]+/g, '-')
    .replace(/^[-.]+|[-.]+$/g, '')
    .slice(0, 64)
    .replace(/[-.]+$/, '')
  return slug || undefined
}

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
    const slug = cleanRef(ref)
    const body = slug ? { code, ref: slug } : { code }
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
        return { ok: false, message: 'The demo could not be set up just now. Nothing was created; try again in a moment.' }
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
