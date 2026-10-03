/**
 * What the service worker does with a push and with a tap on one, as plain
 * functions (web-revamp ticket 15, spec D7).
 *
 * `src/sw.ts` is a thin shell that hands the real events and `self` to these; a
 * service worker cannot run under jsdom, so everything with a decision in it lives
 * HERE, takes what it needs as arguments, and is tested directly
 * (`push-sw.test.ts`). The types are structural on purpose (no `ServiceWorker*`
 * or DOM-only names) so this file compiles under both the app's DOM library and
 * the worker's own.
 *
 * **The payload is the engine's** (`server/push/dispatch.py:deliver`):
 * `{kind, title, body, url, tag, off: {kind, label}}`. Its words are shown as
 * they arrive: they are fixed in `server/push/copy.py`, where a test holds them
 * to the compulsion test (CLAUDE.md section 7). The only words written here are
 * for the two cases the engine cannot speak to: a push that arrived unreadable,
 * and a "turn these off" that did not go through.
 *
 * **A push always shows something.** Browsers (Safari most strictly) cancel a
 * subscription whose pushes do not produce a visible notification, so an
 * unreadable payload still raises one, saying so, rather than going silent.
 */

export interface PushPayload {
  kind: string
  title: string
  body: string
  url: string
  tag: string
  /** The one-tap silence: which kind it turns off, and the button's words. */
  off: { kind: string; label: string } | null
}

/** What goes into `showNotification`, in the shape both libraries accept. */
export interface NotificationSpec {
  body: string
  tag?: string
  data?: unknown
  actions?: { action: string; title: string }[]
}

export type ShowNotification = (title: string, options: NotificationSpec) => Promise<void>

/** The action id on the notification's "Turn these off" button. */
export const OFF_ACTION = 'off'

export const UNREADABLE_TITLE = 'LEGION'
export const UNREADABLE_BODY = 'A notification arrived that could not be read.'
export const OFF_FAILED_TITLE = 'Could not turn these off'
export const OFF_FAILED_BODY =
  'Nothing was changed. Open LEGION, then Settings, then Notifications to turn them off.'
export const OFF_CONFIRMED_TITLE = 'Notifications'
export const SETTINGS_URL = '/settings/notifications'

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object'
}

/** Parse the push body. Tolerant: anything unreadable becomes the fallback, never a throw. */
export function readPayload(text: string | null | undefined): PushPayload {
  const fallback: PushPayload = {
    kind: '',
    title: UNREADABLE_TITLE,
    body: UNREADABLE_BODY,
    url: '/',
    tag: '',
    off: null,
  }
  if (!text) return fallback
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return fallback
  }
  if (!isRecord(parsed)) return fallback
  const title = typeof parsed.title === 'string' && parsed.title !== '' ? parsed.title : null
  const body = typeof parsed.body === 'string' ? parsed.body : null
  if (title === null && body === null) return fallback
  const off = isRecord(parsed.off) && typeof parsed.off.kind === 'string' && parsed.off.kind !== ''
    ? {
        kind: parsed.off.kind,
        label: typeof parsed.off.label === 'string' && parsed.off.label !== '' ? parsed.off.label : 'Turn these off',
      }
    : null
  return {
    kind: typeof parsed.kind === 'string' ? parsed.kind : '',
    title: title ?? UNREADABLE_TITLE,
    body: body ?? '',
    url: typeof parsed.url === 'string' && parsed.url !== '' ? parsed.url : '/',
    tag: typeof parsed.tag === 'string' ? parsed.tag : '',
    off,
  }
}

/** The notification a payload becomes. */
export function notificationFor(payload: PushPayload): { title: string; options: NotificationSpec } {
  const options: NotificationSpec = {
    body: payload.body,
    data: { url: payload.url, off: payload.off },
  }
  // A tag makes a repeat of the same thing replace itself instead of stacking.
  if (payload.tag !== '') options.tag = payload.tag
  if (payload.off) options.actions = [{ action: OFF_ACTION, title: payload.off.label }]
  return { title: payload.title, options }
}

export interface PushEventLike {
  data: { text(): string } | null
  waitUntil(promise: Promise<unknown>): void
}

/** `push`: show what the engine sent. */
export function handlePush(event: PushEventLike, show: ShowNotification): void {
  let text: string | null = null
  try {
    text = event.data ? event.data.text() : null
  } catch {
    text = null
  }
  const { title, options } = notificationFor(readPayload(text))
  event.waitUntil(show(title, options))
}

export interface ClientLike {
  url: string
  focus(): Promise<unknown>
  navigate?(url: string): Promise<unknown>
}

export interface ClickDeps {
  /** The worker's own origin: a payload may only send you to a page of this app. */
  origin: string
  fetch: (input: string, init: RequestInit) => Promise<{ ok: boolean; json(): Promise<unknown> }>
  matchAll(): Promise<ClientLike[]>
  openWindow(url: string): Promise<unknown>
  show: ShowNotification
}

export interface ClickEventLike {
  action: string
  notification: { close(): void; data?: unknown }
  waitUntil(promise: Promise<unknown>): void
}

/** A payload url, kept only if it is a page of this app; anything else is Home. */
export function sameOriginUrl(raw: unknown, origin: string): string {
  if (typeof raw !== 'string' || raw === '') return `${origin}/`
  try {
    const url = new URL(raw, origin)
    return url.origin === origin ? url.href : `${origin}/`
  } catch {
    return `${origin}/`
  }
}

function dataOf(event: ClickEventLike): { url: unknown; offKind: string | null } {
  const data = event.notification.data
  if (!isRecord(data)) return { url: undefined, offKind: null }
  const off = data.off
  return {
    url: data.url,
    offKind: isRecord(off) && typeof off.kind === 'string' ? off.kind : null,
  }
}

/**
 * `notificationclick`.
 *
 * The notification closes first, always. Then one of two things:
 *  - the body was tapped: open or focus the app on the payload's page, reusing a
 *    window that is already open rather than stacking a second one;
 *  - "Turn these off" was tapped: POST it to the engine, and say what happened
 *    ONLY once the engine has answered (the engine's own sentence on success, a
 *    plain "nothing was changed" on any failure), never before it.
 */
export function handleNotificationClick(event: ClickEventLike, deps: ClickDeps): void {
  event.notification.close()
  const { url, offKind } = dataOf(event)

  if (event.action === OFF_ACTION && offKind !== null) {
    event.waitUntil(turnOff(offKind, deps))
    return
  }
  event.waitUntil(openOrFocus(sameOriginUrl(url, deps.origin), deps))
}

async function openOrFocus(target: string, deps: ClickDeps): Promise<void> {
  const windows = (await deps.matchAll()).filter((client) => {
    try {
      return new URL(client.url).origin === deps.origin
    } catch {
      return false
    }
  })
  const existing = windows[0]
  if (existing) {
    try {
      await existing.focus()
      if (existing.url !== target && existing.navigate) await existing.navigate(target)
      return
    } catch {
      // A window that cannot be focused or navigated is not a window we can use.
    }
  }
  await deps.openWindow(target)
}

async function turnOff(kind: string, deps: ClickDeps): Promise<void> {
  const url = `${deps.origin}/api/push/preferences/off`
  try {
    const response = await deps.fetch(url, {
      method: 'POST',
      // The browser's own session cookie. The engine takes no CSRF token on this
      // one route (it can only turn a kind OFF), which is what lets a worker, that
      // cannot read the page's cookie, call it.
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ kind }),
    })
    if (!response.ok) throw new Error('refused')
    const body = await response.json()
    const said = isRecord(body) && typeof body.detail === 'string' ? body.detail : null
    if (said === null) throw new Error('no sentence')
    await deps.show(OFF_CONFIRMED_TITLE, { body: said, data: { url: SETTINGS_URL, off: null } })
  } catch {
    await deps.show(OFF_FAILED_TITLE, { body: OFF_FAILED_BODY, data: { url: SETTINGS_URL, off: null } })
  }
}
