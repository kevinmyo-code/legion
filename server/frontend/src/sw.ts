/// <reference lib="webworker" />
import { clientsClaim } from 'workbox-core'
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from 'workbox-precaching'
import { NavigationRoute, registerRoute } from 'workbox-routing'
import { NetworkOnly } from 'workbox-strategies'

import { handleNotificationClick, handlePush } from './lib/push-sw'

/**
 * LEGION's service worker (vite-plugin-pwa `injectManifest`; web-revamp 15).
 *
 * It does exactly what the generated worker did before, plus notifications, and
 * it is written by hand only because `generateSW` has nowhere to put a `push`
 * handler. Everything with a decision in it is in `lib/push-sw.ts`, where it can
 * be tested; this file is wiring.
 *
 * **Kept from the generated worker, on purpose:**
 *
 *  - **Take over at once** (`skipWaiting` + `clientsClaim`). `registerType:
 *    'autoUpdate'` installs a new worker but, alone, leaves it `waiting` until
 *    every tab holding the old one closes, and a home-screen PWA can hold it for
 *    days: a deploy then looks like it did nothing (commit 206af39, found twice
 *    on 2026-09-12). Safe because `/api/` below is NetworkOnly: taking over can
 *    swap the shell under a person but can never serve them a cached figure.
 *  - **`/api/` is NetworkOnly, and it is a rule, not a default** (CLAUDE.md
 *    section 4): a cached API response is a figure with no way to know how old it
 *    is, shown as current. The shell may be stale, the data may not. No response
 *    from `/api/` is ever cached by this file, and the push handler below stores
 *    nothing either.
 *  - **Navigations fall back to the cached shell EXCEPT the paths Django owns**,
 *    the mirror of the negative lookahead in `legion/urls.py`. Without the
 *    denylist the worker would answer `/admin` and `/api` with the SPA.
 */
declare const self: ServiceWorkerGlobalScope

self.skipWaiting()
clientsClaim()

precacheAndRoute(self.__WB_MANIFEST)
cleanupOutdatedCaches()

registerRoute(
  new NavigationRoute(createHandlerBoundToURL('index.html'), {
    denylist: [/^\/api/, /^\/admin/, /^\/media/, /^\/static/, /^\/health/],
  }),
)

registerRoute(({ url }) => url.pathname.startsWith('/api/'), new NetworkOnly(), 'GET')

self.addEventListener('push', (event) => {
  handlePush(event, (title, options) => self.registration.showNotification(title, options))
})

self.addEventListener('notificationclick', (event) => {
  handleNotificationClick(event, {
    origin: self.location.origin,
    fetch: (input, init) => self.fetch(input, init),
    matchAll: async () => [...(await self.clients.matchAll({ type: 'window', includeUncontrolled: true }))],
    openWindow: (url) => self.clients.openWindow(url),
    show: (title, options) => self.registration.showNotification(title, options),
  })
})
