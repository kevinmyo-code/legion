import type { components } from '../api/schema'

/**
 * The fake engine's answers for join, signup and the settings screens
 * (web-revamp ticket 05), kept apart from `engine.ts` for the reason
 * `engine-tables.ts` is: a screen ticket grows its own routes without colliding
 * with another ticket's rows in the one big handler.
 *
 * It follows the real server's contract (`server/household/views.py`,
 * `households.py`), including the SENTENCES, because a screen under test shows
 * those verbatim and a fake that invented its own wording would let a test pass
 * against words the engine never says.
 */

export type Invite = components['schemas']['Invite']
export type InvitePreview = components['schemas']['InvitePreview']
export type DeviceToken = components['schemas']['DeviceToken']
export type Preference = components['schemas']['Preference']

export interface PushState {
  /** False is an engine with no VAPID keys: push is off, said in words. */
  enabled: boolean
  publicKey: string
  preferences: Preference
  /** Browsers subscribed, by the id `POST /api/push/subscriptions` handed back. */
  subscriptions: { id: string; endpoint: string; tz: string }[]
}

export interface SettingsState {
  /** The signed-in person's display name (`User.first_name`). */
  name: string
  /** What `GET /api/auth/invite/<code>` answers, by code. */
  previews: Record<string, InvitePreview>
  /** The household's LIVE invites, as `GET /api/households/me/invites` lists them. */
  invites: Invite[]
  devices: DeviceToken[]
  /** The password `POST /api/auth/password` accepts as "current". */
  password: string
  /** An email already registered, so signup can be refused for it. */
  takenEmail: string
  push: PushState
}

export function defaultSettings(): SettingsState {
  return {
    name: 'Mia',
    previews: {},
    invites: [],
    devices: [],
    password: 'old-password-123',
    takenEmail: 'taken@example.test',
    push: {
      enabled: true,
      publicKey: 'BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U',
      preferences: {
        list_changes: true,
        event_reminders: true,
        task_due_morning: true,
        morning_time: '07:30',
      },
      subscriptions: [],
    },
  }
}

/** A live invite preview that joins an existing household. */
export function livePreview(code: string, overrides: Partial<InvitePreview> = {}): InvitePreview {
  return {
    code,
    live: true,
    creates_household: false,
    household_name: 'The Test House',
    uses_left: 2,
    expires_at: '2099-01-01T00:00:00Z',
    reason: null,
    ...overrides,
  }
}

export function makeInvite(overrides: Partial<Invite> = {}): Invite {
  const code = overrides.code ?? 'Zk3-aB9_xQ2w'
  return {
    id: 1,
    code,
    join_url: `http://localhost:3000/join/${code}`,
    creates_household: false,
    max_uses: 2,
    used_count: 0,
    expires_at: '2026-10-17T12:00:00Z',
    created_at: '2026-10-03T12:00:00Z',
    ...overrides,
  }
}

export function makeDevice(overrides: Partial<DeviceToken> = {}): DeviceToken {
  return {
    id: 1,
    name: "Mia's iPhone",
    created_at: '2026-09-20T15:00:00Z',
    last_seen_at: '2026-10-03T14:10:00Z',
    current: false,
    ...overrides,
  }
}

export interface SettingsHost {
  settings: SettingsState
  householdName: string
  householdTimezone: string | null
  members: components['schemas']['HouseholdMember'][]
  signedIn: boolean
}

interface Reply {
  status: number
  body?: unknown
}

const OWNER_ONLY = {
  detail:
    'Nothing was changed: this route is owner-only. `owner` is the ONLY role there is and it governs membership alone - an owner and a member see exactly the same data (ADR 0045).',
}

const TIMEZONE_OWNER_ONLY = {
  detail: 'Only the household owner can change the timezone. Nothing was changed.',
}

/** The one `ME` user id, passed in so this file does not import `engine.ts` (which imports it). */
export function handleSettings(
  host: SettingsHost,
  meId: string,
  meEmail: string,
  method: string,
  pathname: string,
  body: unknown,
): Reply | undefined {
  const s = host.settings
  const isOwner = host.members.some((member) => member.user_id === meId && member.role === 'owner')

  if (method === 'PATCH' && pathname === '/api/auth/me') {
    const name = String((body as { name?: string }).name ?? '').trim()
    if (name === '') {
      return { status: 400, body: { name: ['A name cannot be blank. Nothing was changed.'] } }
    }
    s.name = name
    const mine = host.members.find((member) => member.user_id === meId)
    if (mine) mine.name = name
    return { status: 200, body: { user_id: meId, email: meEmail, device_name: '', name } }
  }

  if (method === 'POST' && pathname === '/api/auth/password') {
    const sent = body as { current_password: string; new_password: string }
    if (sent.current_password !== s.password) {
      return {
        status: 400,
        body: { detail: 'Your current password is not right. Nothing was changed.' },
      }
    }
    if (sent.new_password.length < 8) {
      return {
        status: 400,
        body: {
          detail:
            'Nothing was changed. This password is too short. It must contain at least 8 characters.',
        },
      }
    }
    s.password = sent.new_password
    return {
      status: 200,
      body: { detail: 'Your password is changed. You are still signed in here.' },
    }
  }

  let match = pathname.match(/^\/api\/auth\/invite\/([^/]+)$/)
  if (match && method === 'GET') {
    const preview = s.previews[decodeURIComponent(match[1])]
    if (!preview) {
      return {
        status: 404,
        body: {
          detail:
            'No invite has that code. Nothing was spent - check the link for a typo, or ask for a new one.',
        },
      }
    }
    return { status: 200, body: preview }
  }

  if (method === 'POST' && pathname === '/api/auth/signup') {
    const sent = body as {
      email: string
      password: string
      name?: string
      invite_code?: string
      household_name?: string
      device_name: string
    }
    const preview = s.previews[sent.invite_code ?? '']
    if (!preview) {
      return {
        status: 400,
        body: {
          detail:
            'No account was created. There is no invite with that code - check it for a typo, or ask for a new link.',
        },
      }
    }
    if (!preview.live) {
      return { status: 400, body: { detail: `No account was created. ${preview.reason ?? ''}` } }
    }
    if (sent.email.toLowerCase() === s.takenEmail) {
      return {
        status: 400,
        body: {
          detail:
            'No account was created. An account with that email already exists - sign in instead, or use a different address.',
        },
      }
    }
    if (sent.password.length < 8) {
      return {
        status: 400,
        body: { password: ['This password is too short. It must contain at least 8 characters.'] },
      }
    }
    if (preview.creates_household && !(sent.household_name ?? '').trim()) {
      return {
        status: 400,
        body: {
          detail:
            'No account was created. This code creates a NEW household, so it needs a `household_name` - what your family calls itself on this server.',
        },
      }
    }
    const userId = '9c1d0000-0000-4000-8000-000000000001'
    host.members.push({
      role: preview.creates_household ? 'owner' : 'member',
      user_id: userId,
      email: sent.email,
      name: sent.name ?? '',
      joined_at: new Date().toISOString(),
    })
    preview.uses_left = Math.max(preview.uses_left - 1, 0)
    if (preview.uses_left === 0) {
      preview.live = false
      preview.reason = 'That invite code has already been used 2 of 2 times. Ask for a new one.'
    }
    return { status: 201, body: { token: 'device-token-shown-once', user_id: userId } }
  }

  if (method === 'POST' && pathname === '/api/auth/logout') return { status: 204 }

  if (method === 'PATCH' && pathname === '/api/households/me') {
    const sent = (body ?? {}) as { name?: string; timezone?: string | null }
    if (!isOwner) {
      // The real engine names what was asked for (`IsOwnerForHouseholdPatch`).
      if ('timezone' in sent && !('name' in sent)) return { status: 403, body: TIMEZONE_OWNER_ONLY }
      return { status: 403, body: OWNER_ONLY }
    }
    if (!('name' in sent) && !('timezone' in sent)) {
      return { status: 400, body: { detail: 'Nothing was changed. Send `name`, `timezone`, or both.' } }
    }
    if ('timezone' in sent && sent.timezone !== null) {
      const zone = String(sent.timezone).trim()
      if (!Intl.supportedValuesOf('timeZone').includes(zone)) {
        return {
          status: 400,
          body: {
            detail: `Nothing was changed. '${zone}' is not a timezone this server knows. Use an IANA name such as America/Chicago.`,
          },
        }
      }
    }
    if ('name' in sent) {
      const name = String(sent.name ?? '').trim()
      if (name === '') return { status: 400, body: { detail: 'Nothing was changed. name: This field may not be blank.' } }
      host.householdName = name
    }
    if ('timezone' in sent) host.householdTimezone = sent.timezone ?? null
    return {
      status: 200,
      body: { id: 'h1', name: host.householdName, timezone: host.householdTimezone, members: host.members },
    }
  }

  if (pathname === '/api/households/me/invites') {
    if (!isOwner) return { status: 403, body: OWNER_ONLY }
    if (method === 'GET') return { status: 200, body: s.invites }
    if (method === 'POST') {
      const sent = (body ?? {}) as { max_uses?: number; expires_in_days?: number }
      const code = `NewCode${s.invites.length + 1}-xYz`
      const expires = new Date(Date.now() + (sent.expires_in_days ?? 14) * 86_400_000)
      const created = makeInvite({
        id: s.invites.length + 10,
        code,
        max_uses: sent.max_uses ?? 2,
        expires_at: expires.toISOString(),
        created_at: new Date().toISOString(),
      })
      s.invites.unshift(created)
      return { status: 201, body: created }
    }
  }

  match = pathname.match(/^\/api\/households\/me\/invites\/([^/]+)$/)
  if (match && method === 'DELETE') {
    if (!isOwner) return { status: 403, body: OWNER_ONLY }
    const code = decodeURIComponent(match[1])
    const at = s.invites.findIndex((invite) => invite.code === code)
    if (at === -1) {
      return {
        status: 404,
        body: { detail: 'Nothing was revoked: this household minted no invite with that code.' },
      }
    }
    s.invites.splice(at, 1)
    return { status: 204 }
  }

  match = pathname.match(/^\/api\/households\/me\/members\/([^/]+)$/)
  if (match && method === 'DELETE') {
    if (!isOwner) return { status: 403, body: OWNER_ONLY }
    const at = host.members.findIndex((member) => member.user_id === match![1])
    if (at === -1) {
      return {
        status: 404,
        body: { detail: 'Nobody was removed: no member of this household has that user id.' },
      }
    }
    if (host.members[at].user_id === meId) {
      return {
        status: 400,
        body: {
          detail:
            'Nobody was removed. You are the last owner of this household, and a household with no owner has nobody who can ever invite or remove anyone again. Make someone else an owner first.',
        },
      }
    }
    host.members.splice(at, 1)
    return { status: 204 }
  }

  const pushReply = handlePush(s.push, method, pathname, body)
  if (pushReply) return pushReply

  if (method === 'GET' && pathname === '/api/auth/devices') return { status: 200, body: s.devices }

  match = pathname.match(/^\/api\/auth\/devices\/(\d+)$/)
  if (match && method === 'DELETE') {
    const id = Number(match[1])
    const at = s.devices.findIndex((device) => device.id === id)
    if (at === -1) {
      return {
        status: 404,
        body: { detail: 'Nothing was revoked: no device token of yours has that id.' },
      }
    }
    s.devices.splice(at, 1)
    return { status: 204 }
  }

  return undefined
}

const PUSH_OFF = 'Notifications are not set up on this server.'
const KIND_NAMES: Record<string, string> = {
  list_changes: 'list changes',
  event_reminders: 'event reminders',
  task_due_morning: 'the morning list of what is due',
}

function subscriptionBody(id: string, tz: string) {
  return { id, user_agent: '', tz, created_at: '2026-10-03T12:00:00Z', last_ok_at: null }
}

/** `/api/push/*`, with the real server's sentences (`server/push/views.py`, `copy.py`). */
function handlePush(push: PushState, method: string, pathname: string, body: unknown): Reply | undefined {
  if (!pathname.startsWith('/api/push/')) return undefined

  if (method === 'GET' && pathname === '/api/push/vapid-public-key') {
    return {
      status: 200,
      body: push.enabled
        ? { enabled: true, public_key: push.publicKey, detail: null }
        : { enabled: false, public_key: null, detail: PUSH_OFF },
    }
  }

  if (method === 'POST' && pathname === '/api/push/subscriptions') {
    if (!push.enabled) {
      return { status: 503, body: { detail: `${PUSH_OFF} Nothing was saved.` } }
    }
    const sent = body as { endpoint: string; keys: { p256dh: string; auth: string }; tz?: string }
    if (!sent.endpoint.startsWith('https://')) {
      return {
        status: 400,
        body: { endpoint: ['A push endpoint is an https:// address from the browser. Nothing was saved.'] },
      }
    }
    const existing = push.subscriptions.find((sub) => sub.endpoint === sent.endpoint)
    if (existing) {
      existing.tz = sent.tz ?? existing.tz
      return { status: 200, body: subscriptionBody(existing.id, existing.tz) }
    }
    const created = {
      id: `00000000-0000-4000-8000-${(push.subscriptions.length + 1).toString(16).padStart(12, '0')}`,
      endpoint: sent.endpoint,
      tz: sent.tz ?? 'UTC',
    }
    push.subscriptions.push(created)
    return { status: 201, body: subscriptionBody(created.id, created.tz) }
  }

  const match = pathname.match(/^\/api\/push\/subscriptions\/([^/]+)$/)
  if (match && method === 'DELETE') {
    const at = push.subscriptions.findIndex((sub) => sub.id === match[1])
    if (at === -1) {
      return { status: 404, body: { detail: 'No subscription of yours has that id. Nothing was removed.' } }
    }
    push.subscriptions.splice(at, 1)
    return { status: 204 }
  }

  if (method === 'GET' && pathname === '/api/push/preferences') return { status: 200, body: push.preferences }
  if (method === 'PUT' && pathname === '/api/push/preferences') {
    const sent = body as Preference
    if (!/^\d\d:\d\d$/.test(sent.morning_time)) {
      return {
        status: 400,
        body: { morning_time: ['Time has wrong format. Use one of these formats instead: hh:mm.'] },
      }
    }
    push.preferences = { ...sent }
    return { status: 200, body: push.preferences }
  }

  if (method === 'POST' && pathname === '/api/push/preferences/off') {
    const kind = (body as { kind: string }).kind
    if (!(kind in KIND_NAMES)) {
      return {
        status: 400,
        body: {
          detail: `'${kind}' is not a kind of notification. Use one of: ${Object.keys(KIND_NAMES).join(', ')}. Nothing was changed.`,
        },
      }
    }
    push.preferences = { ...push.preferences, [kind]: false }
    return {
      status: 200,
      body: {
        ...push.preferences,
        detail: `You will not get ${KIND_NAMES[kind]} any more. Turn them back on in Settings, Notifications.`,
      },
    }
  }

  return undefined
}
