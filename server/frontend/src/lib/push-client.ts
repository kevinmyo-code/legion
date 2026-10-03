/**
 * What THIS browser can do about notifications, read straight from the browser
 * (web-revamp ticket 15, spec D7). No engine calls here: the engine's half is in
 * `src/api/push.ts`.
 *
 * Everything reads the globals at call time, never at import, so a test (jsdom has
 * no service worker, no `PushManager`, no `Notification`) installs the ones it
 * wants first (`src/test/push-browser.ts`).
 */

export type PushSupport =
  /** iPhone or iPad, in Safari rather than the installed app: push is not possible yet. */
  | { kind: 'ios-install' }
  /** No service worker, no `PushManager` or no `Notification` in this browser. */
  | { kind: 'unsupported' }
  | { kind: 'ready' }

export type BrowserPermission = 'granted' | 'denied' | 'default'

/** iPhone, iPod and iPad, including an iPad that says it is a Mac (iPadOS 13+). */
export function isIos(): boolean {
  const ua = navigator.userAgent
  if (/iPhone|iPad|iPod/.test(ua)) return true
  return navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1
}

/** Safari's own flag for "launched from the Home Screen icon". Not in the DOM types. */
export function isStandalone(): boolean {
  return (navigator as Navigator & { standalone?: boolean }).standalone === true
}

/**
 * Whether push can work here at all. On iOS it needs iOS 16.4 or later AND the
 * installed app: in a Safari tab there is no `PushManager`, so asking the browser
 * would say "unsupported", which is true but useless. What a person needs to hear
 * there is how to install, so iOS outside the installed app is checked FIRST.
 */
export function readSupport(): PushSupport {
  if (isIos() && !isStandalone()) return { kind: 'ios-install' }
  if (
    !('serviceWorker' in navigator) ||
    typeof window.PushManager === 'undefined' ||
    typeof window.Notification === 'undefined'
  ) {
    return { kind: 'unsupported' }
  }
  return { kind: 'ready' }
}

export function readPermission(): BrowserPermission {
  return typeof window.Notification === 'undefined' ? 'default' : window.Notification.permission
}

/** The browser's IANA zone, which decides which local day "this morning" is. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}

/** A VAPID public key (URL-safe base64) as the bytes `pushManager.subscribe` wants. */
export function keyBytes(key: string): Uint8Array<ArrayBuffer> {
  const padded = key + '='.repeat((4 - (key.length % 4)) % 4)
  const raw = atob(padded.replace(/-/g, '+').replace(/_/g, '/'))
  const bytes = new Uint8Array(new ArrayBuffer(raw.length))
  for (let index = 0; index < raw.length; index += 1) bytes[index] = raw.charCodeAt(index)
  return bytes
}

/** A problem the page says in words; its message always opens with what did not happen. */
export class PushProblem extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PushProblem'
  }
}

const WORKER_WAIT_MS = 5000

/** This site's service worker registration, or a `PushProblem` if it never becomes ready. */
export async function workerRegistration(): Promise<ServiceWorkerRegistration> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const wait = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () =>
        reject(
          new PushProblem(
            'Could not start the notification worker in this browser, so nothing was turned on. Reload the page and try again.',
          ),
        ),
      WORKER_WAIT_MS,
    )
  })
  try {
    return await Promise.race([navigator.serviceWorker.ready, wait])
  } finally {
    clearTimeout(timer)
  }
}

/** The subscription this browser holds, or null. Reads the browser; asks nobody. */
export async function currentSubscription(): Promise<PushSubscription | null> {
  if (readSupport().kind !== 'ready') return null
  const registration = await workerRegistration()
  return registration.pushManager.getSubscription()
}
