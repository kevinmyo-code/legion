import { vi } from 'vitest'

/**
 * A browser with (or without) push, for a test: jsdom has no service worker, no
 * `PushManager` and no `Notification`, so each test installs the browser it is
 * about. Records what the page asked of it, so a test can say "it asked for
 * permission, then subscribed with this key" in the order it happened.
 *
 * `restore()` puts the globals back; call it in `afterEach`.
 */
export interface PushBrowserOptions {
  /** An iPhone user agent. */
  ios?: boolean
  /** `navigator.standalone`: launched from the Home Screen icon. */
  standalone?: boolean
  /** No service worker / PushManager / Notification at all. */
  unsupported?: boolean
  permission?: NotificationPermission
  /** What the permission question is answered with when asked. */
  answer?: NotificationPermission
  /** The browser already holds a subscription. */
  subscribed?: boolean
  /** `pushManager.subscribe` rejects with this. */
  subscribeError?: Error
  /** `subscription.unsubscribe()` resolves to this. */
  unsubscribeResult?: boolean
  /** The worker never becomes ready. */
  neverReady?: boolean
}

const IOS_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1'

export const ENDPOINT = 'https://push.example/sub-1'

export function stubPushBrowser(options: PushBrowserOptions = {}) {
  const originalUa = navigator.userAgent
  const permission = { value: options.permission ?? 'default' }

  let held: object | null = null
  const subscription = {
    endpoint: ENDPOINT,
    toJSON: () => ({ endpoint: ENDPOINT, keys: { p256dh: 'p256dh-key', auth: 'auth-secret' } }),
    unsubscribe: vi.fn(async () => {
      const ok = options.unsubscribeResult ?? true
      if (ok) held = null
      return ok
    }),
  }

  if (options.subscribed) held = subscription

  const pushManager = {
    getSubscription: vi.fn(async () => held),
    subscribe: vi.fn(async (_: { userVisibleOnly: boolean; applicationServerKey: Uint8Array }) => {
      if (options.subscribeError) throw options.subscribeError
      held = subscription
      return subscription
    }),
  }
  const registration = { pushManager }

  const requestPermission = vi.fn(async () => {
    permission.value = options.answer ?? 'granted'
    return permission.value
  })

  if (options.ios) {
    Object.defineProperty(navigator, 'userAgent', { value: IOS_UA, configurable: true })
  }
  if (options.standalone !== undefined) {
    Object.defineProperty(navigator, 'standalone', { value: options.standalone, configurable: true })
  }
  if (!options.unsupported) {
    vi.stubGlobal('PushManager', class {})
    vi.stubGlobal('Notification', {
      get permission() {
        return permission.value
      },
      requestPermission,
    })
    Object.defineProperty(navigator, 'serviceWorker', {
      value: { ready: options.neverReady ? new Promise(() => {}) : Promise.resolve(registration) },
      configurable: true,
    })
  }

  return {
    requestPermission,
    pushManager,
    subscription,
    /** Whether the browser holds a subscription right now. */
    get held() {
      return held !== null
    },
    restore() {
      Reflect.deleteProperty(navigator, 'serviceWorker')
      Reflect.deleteProperty(navigator, 'standalone')
      Object.defineProperty(navigator, 'userAgent', { value: originalUa, configurable: true })
      vi.unstubAllGlobals()
    },
  }
}
