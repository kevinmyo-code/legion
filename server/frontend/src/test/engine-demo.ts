import type { DemoContent, DemoSession } from '../api/demo'
import type { Reply } from './engine'

/**
 * The fake engine's recruiter demo (recruiter-demo tickets 02 and 07).
 *
 * `POST /api/demo/enter` follows `server/demo/views.py`, sentences included.
 * `GET /api/demo/session` and `GET /api/demo/content` follow the contract agreed
 * with the server branch (not in `openapi.yaml` yet): session answers
 * `{role, expires_at, ref}` or 403; content answers the page or 404, and allows
 * a recruiter's visit and a signed-in member (the owner's preview).
 *
 * Since ticket 13 (ADR 0069) a live visit is signed in on its own copy of the
 * demo household: `auth/me` answers with role `recruiter`, the app's routes
 * answer as for a member, and the routes `household/recruiter_routes.py`
 * denies answer 403 with a sentence (`recruiterDenied`). `ended: true` is a
 * visit that has run out: `auth/me` answers 403 with `demo_visit_ended`.
 */

export const WRONG_CODE = 'That invite code is not right. Nothing was created; check the code and try again.'
export const TOO_MANY =
  'Too many tries from this network in a short time. Nothing was created; wait a while and try again.'
export const SEED_FAILED =
  'The demo could not be set up just now. Nothing was created; try again in a moment.'
export const VISIT_OVER =
  'Nothing was read or written. This demo visit has ended or was closed. Enter the invite code again to start a new one.'

/** A few of the engine's deny-list prefixes, enough for the screens under test. */
const DENIED_PREFIXES = [
  '/api/body/',
  '/api/memory/',
  '/api/ingest/',
  '/api/games/',
  '/api/books/',
  '/api/push/',
  '/api/brief/',
  '/api/auth/devices',
  '/api/households/me/',
]

export const RECRUITER_DENIED_REFUSAL =
  "Nothing was read or written. This is a recruiter's demo visit, and it reaches a device or an account outside the household's own rows."

export interface DemoState {
  /** The one live invite code. */
  code: string
  /** The visit ran out: `auth/me` answers 403 `demo_visit_ended`. */
  ended?: boolean
  /** A forced reply for enter (throttled, no demo household). */
  enterReply: Reply | null
  /** Bodies enter was sent, in order. */
  enterBodies: unknown[]
  /** This browser's visit; null is none. */
  session: DemoSession | null
  /** The page; null answers 404 ("not set up"). */
  content: DemoContent | null
}

export function defaultDemo(): DemoState {
  return { code: 'OPEN-SESAME', enterReply: null, enterBodies: [], session: null, content: sampleContent() }
}

export function isRecruiter(host: { demo: DemoState; signedIn: boolean }): boolean {
  return !host.signedIn && host.demo.session?.role === 'recruiter'
}

/** A deny-list route asked for by a live visit: 403 in words, as the engine does. */
export function recruiterDenied(
  host: { demo: DemoState; signedIn: boolean },
  pathname: string,
): Reply | undefined {
  if (!isRecruiter(host)) return undefined
  if (DENIED_PREFIXES.some((prefix) => pathname.startsWith(prefix))) {
    return { status: 403, body: { detail: RECRUITER_DENIED_REFUSAL } }
  }
  return undefined
}

export function handleDemo(
  host: { demo: DemoState; signedIn: boolean },
  method: string,
  pathname: string,
  body: unknown,
): Reply | undefined {
  const d = host.demo

  if (method === 'POST' && pathname === '/api/demo/enter') {
    d.enterBodies.push(body)
    if (d.enterReply) return d.enterReply
    const { code, ref } = (body ?? {}) as { code?: string; ref?: string }
    if (code?.trim() !== d.code) return { status: 403, body: { detail: WRONG_CODE } }
    const expires = new Date(Date.now() + 2 * 3_600_000).toISOString()
    d.session = { role: 'recruiter', expires_at: expires, ref: ref ?? '' }
    return { status: 201, body: { token: 'visitor-token', expires_at: expires, ref: ref ?? '' } }
  }

  if (method === 'GET' && pathname === '/api/demo/session') {
    if (!isRecruiter(host)) {
      return { status: 403, body: { detail: 'This browser holds no demo visit.' } }
    }
    return { status: 200, body: d.session }
  }

  if (method === 'GET' && pathname === '/api/demo/content') {
    if (!isRecruiter(host) && !host.signedIn) {
      return { status: 403, body: { detail: 'Authentication credentials were not provided.' } }
    }
    if (!d.content) return { status: 404, body: { detail: 'Not found.' } }
    return { status: 200, body: d.content }
  }

  return undefined
}

export const SAMPLE_SVG =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 760 120"><title>Warehouse</title><rect width="760" height="120" fill="#fff"/><text x="20" y="60">bronze &amp; silver &gt; gold</text></svg>'

export function sampleContent(): DemoContent {
  return {
    hero: {
      title: 'I build data platforms people trust.',
      lead: 'Data and AI engineer.',
      facts: ['Houston, open to relocation', 'Available summer 2027'],
    },
    look_for: [
      { title: 'Honest answers', body: 'Every figure is verified, an estimate, or refused.' },
      { title: 'A gate on the data', body: 'A receipt that does not add up is quarantined.' },
    ],
    work: {
      title: 'What I built at work',
      intro: 'A finance data platform that runs every night.',
      stats: [
        { value: '10+', label: 'ERP systems integrated' },
        { value: '~770', label: 'dbt models across 3 layers' },
      ],
      diagram_svg: SAMPLE_SVG,
      diagram_caption: 'Sources land in bronze, are cleaned in silver, modelled in gold.',
      cards: [{ title: 'Publish gate', body: 'Reports refresh only after the checks pass.' }],
    },
    experience: [
      { when: 'Jun - Nov 2026', title: 'Process and Data Intern', bullets: ['Built the warehouse.'] },
    ],
    links: { linkedin: 'https://www.linkedin.com/in/example', github: 'https://github.com/example', email: 'kevin@example.test' },
    assets: { report: '/static/demo/report.html', resume: '/static/demo/resume.pdf' },
  }
}
