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
 * A recruiter's visit is refused by every other route, as ADR 0068 has it:
 * `auth/me` and the companion read answer 403 with the engine's words.
 */

export const WRONG_CODE = 'That invite code is not right. Nothing was created; check the code and try again.'
export const TOO_MANY =
  'Too many tries from this network in a short time. Nothing was created; wait a while and try again.'
export const NO_DEMO_HOUSEHOLD =
  'The demo household is not set up on this engine yet. Nothing was created; the operator has to seed it first.'
export const RECRUITER_ROUTE_REFUSAL =
  'Nothing was read or written. This is a recruiter\'s demo visit: it may use the assistant and nothing else on this engine.'

export interface DemoState {
  /** The one live invite code. */
  code: string
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

  if (isRecruiter(host) && method === 'GET' && pathname === '/api/assistant/companion') {
    return { status: 403, body: { detail: RECRUITER_ROUTE_REFUSAL } }
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
      title: 'What I built at Quanex',
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
