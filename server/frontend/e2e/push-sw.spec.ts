import { expect, test } from '@playwright/test'

import { createEngine } from '../src/test/engine'

/**
 * The BUILT service worker, in a real Chromium: it registers, takes over, and
 * shows a notification for a push (web-revamp ticket 15).
 *
 *     npm run push-sw
 *
 * `push-sw.test.ts` tests the handlers as plain functions; this is the one check
 * that the file `vite build` actually emits (`sw.js`, bundled from `src/sw.ts`)
 * loads as a classic worker, activates, and wires those handlers to the real
 * `push` event. The push is delivered through the DevTools protocol, so it is the
 * browser's own `push` dispatch, not a call into our code.
 *
 * What it cannot do: deliver through a real push service (that needs VAPID keys
 * and a browser endpoint) or press a notification's button (CDP has no way to
 * dispatch `notificationclick`). Both stay "owed on live" in the ticket.
 */

// `channel: 'chromium'` is the full browser in its new headless mode. The default
// headless shell answers `Notification.permission` with "denied" whatever the
// context grants, and a worker cannot show a notification without it.
test.use({ serviceWorkers: 'allow', channel: 'chromium' })

test('the built worker activates and shows a pushed notification', async ({ page, context }) => {
  await context.grantPermissions(['notifications'])
  const engine = createEngine({ signedIn: false })
  await context.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const reply = engine.handle(request.method(), url.pathname, url.searchParams, undefined)
    await route.fulfill({
      status: reply.status,
      contentType: 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    })
  })

  const cdp = await context.newCDPSession(page)
  await cdp.send('ServiceWorker.enable')
  const registrationId = new Promise<string>((resolve) => {
    cdp.on('ServiceWorker.workerRegistrationUpdated', (event) => {
      const registration = event.registrations.find((entry) => entry.scopeURL.endsWith('/'))
      if (registration) resolve(registration.registrationId)
    })
  })

  await page.goto('/login')
  // Registered by the plugin's `registerSW.js` on load, as a classic script.
  await expect
    .poll(() =>
      page.evaluate(async () => {
        const registration = await navigator.serviceWorker.ready
        return registration.active?.state
      }),
    )
    .toBe('activated')
  const state = await page.evaluate(async () => {
    const registration = await navigator.serviceWorker.ready
    return { script: registration.active?.scriptURL }
  })
  expect(state.script).toMatch(/\/sw\.js$/)

  const payload = {
    kind: 'list_changes',
    title: 'Groceries',
    body: 'Kevin added oat milk, eggs and 2 more to Groceries.',
    url: '/lists',
    tag: 'list:abc:2026-10-03T14:00:00Z',
    off: { kind: 'list_changes', label: 'Turn these off' },
  }
  await cdp.send('ServiceWorker.deliverPushMessage', {
    origin: new URL(page.url()).origin,
    registrationId: await registrationId,
    data: JSON.stringify(payload),
  })

  await expect
    .poll(async () =>
      page.evaluate(async () => {
        const registration = await navigator.serviceWorker.ready
        const shown = await registration.getNotifications()
        return shown.map((notification) => ({
          title: notification.title,
          body: notification.body,
          tag: notification.tag,
          data: notification.data,
        }))
      }),
    )
    .toEqual([
      {
        title: 'Groceries',
        body: 'Kevin added oat milk, eggs and 2 more to Groceries.',
        tag: 'list:abc:2026-10-03T14:00:00Z',
        data: { url: '/lists', off: { kind: 'list_changes', label: 'Turn these off' } },
      },
    ])
})

test('the built worker never answers /api/ from a cache', async ({ page, context }) => {
  const engine = createEngine({ signedIn: false })
  let served = 0
  await context.route('**/api/**', async (route) => {
    served += 1
    const request = route.request()
    const url = new URL(request.url())
    const reply = engine.handle(request.method(), url.pathname, url.searchParams, undefined)
    await route.fulfill({ status: reply.status, contentType: 'application/json', body: JSON.stringify(reply.body ?? {}) })
  })
  await page.goto('/login')
  await page.evaluate(() => navigator.serviceWorker.ready)
  await page.reload()
  // The page is under the worker's control, so what follows goes THROUGH it.
  expect(await page.evaluate(() => navigator.serviceWorker.controller !== null)).toBe(true)

  // Two asks of the same address, both of which must reach the network.
  const before = served
  for (let ask = 0; ask < 2; ask += 1) {
    await page.evaluate(() => fetch('/api/auth/me').then((response) => response.status))
  }
  expect(served - before).toBe(2)
  const caches = await page.evaluate(async () => {
    const names = await globalThis.caches.keys()
    const urls: string[] = []
    for (const name of names) {
      for (const request of await (await globalThis.caches.open(name)).keys()) urls.push(new URL(request.url).pathname)
    }
    return urls
  })
  expect(caches.filter((path) => path.startsWith('/api/'))).toEqual([])
})

test('"Turn these off" in the built worker posts to the engine and then says what the engine said', async ({
  page,
  context,
}) => {
  await context.grantPermissions(['notifications'])
  const engine = createEngine({ signedIn: false })
  await context.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const raw = request.postData()
    const reply = engine.handle(request.method(), url.pathname, url.searchParams, raw ? JSON.parse(raw) : undefined)
    await route.fulfill({
      status: reply.status,
      contentType: 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    })
  })
  await page.goto('/login')
  await expect
    .poll(() => page.evaluate(async () => (await navigator.serviceWorker.ready).active?.state))
    .toBe('activated')

  // A notification as the worker itself would have shown for a push.
  await page.evaluate(async () => {
    const registration = await navigator.serviceWorker.ready
    await registration.showNotification('Groceries', {
      body: 'Kevin added oat milk to Groceries.',
      tag: 'list:abc',
      data: { url: '/lists', off: { kind: 'list_changes', label: 'Turn these off' } },
    })
  })

  // The browser cannot be made to press a notification's button, so the click is
  // dispatched inside the worker with the real event class: the handler, its
  // `waitUntil` and its `fetch` are the built ones.
  const worker = context.serviceWorkers().find((candidate) => candidate.url().endsWith('/sw.js'))
  expect(worker).toBeDefined()
  await worker!.evaluate(async () => {
    // Typed by hand: this file compiles under the DOM library, and the worker's own
    // globals are not in it.
    const scope = globalThis as unknown as {
      registration: ServiceWorkerRegistration
      dispatchEvent(event: Event): boolean
      NotificationEvent: new (type: string, init: { notification: Notification; action: string }) => Event
    }
    const [notification] = await scope.registration.getNotifications({ tag: 'list:abc' })
    scope.dispatchEvent(new scope.NotificationEvent('notificationclick', { notification, action: 'off' }))
  })

  await expect
    .poll(() =>
      page.evaluate(async () => {
        const shown = await (await navigator.serviceWorker.ready).getNotifications()
        return shown.map((notification) => `${notification.title}: ${notification.body}`)
      }),
    )
    .toEqual([
      'Notifications: You will not get list changes any more. Turn them back on in Settings, Notifications.',
    ])
  expect(engine.settings.push.preferences.list_changes).toBe(false)
  expect(engine.writes.at(-1)).toEqual({
    method: 'POST',
    pathname: '/api/push/preferences/off',
    body: { kind: 'list_changes' },
  })
})
