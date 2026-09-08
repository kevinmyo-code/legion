import createClient, { type Middleware } from 'openapi-fetch'

import type { paths } from './schema'

/**
 * The one HTTP client. Every call the web app makes goes through this, and its
 * types come from `server/openapi.yaml` by way of `npm run gen:api` - a field
 * renamed on the server is a build error here, not something a user finds
 * later (`server/README.md`, "a client generates from this file").
 *
 * Nothing here hand-writes a URL or a body shape. If a path is missing from
 * `schema.d.ts`, the fix is on the server and `npm run gen:api`, never a cast.
 */

/** Django's default CSRF cookie name (`settings.CSRF_COOKIE_NAME`). */
const CSRF_COOKIE = 'csrftoken'

/** Methods Django's `CsrfViewMiddleware` treats as safe and does not check. */
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])

/**
 * Reads a cookie by name from `document.cookie`.
 *
 * Django's CSRF cookie is deliberately NOT `HttpOnly` - the double-submit
 * scheme requires the page to read it and echo it in a header - so this is the
 * intended way to get at it, not a workaround.
 */
function readCookie(name: string): string | null {
  const prefix = `${name}=`
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim()
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.slice(prefix.length))
    }
  }
  return null
}

/**
 * Session auth for the browser, in one place.
 *
 * Two things every request needs and neither of which a caller should have to
 * remember:
 *
 * 1. `credentials: 'same-origin'` - `fetch` sends no cookies by default, so
 *    without this every authenticated call would 401 while looking like a
 *    permissions bug. Same-origin rather than `include` because the SPA and the
 *    API are one origin by construction (Vite proxies in dev, Django serves
 *    both in production); `include` would additionally leak the cookie to any
 *    cross-origin URL a future caller passes in.
 * 2. `X-CSRFToken` on anything that is not a safe method. Django rejects an
 *    unsafe session-authenticated request without it with a 403 whose body is
 *    an HTML page, which is the least legible failure this app can produce.
 *
 * The header is set only when the cookie is actually present. Sending an empty
 * `X-CSRFToken` would turn "you are not signed in yet, so there is no cookie"
 * into "your token is wrong", and those want different messages.
 *
 * Ticket 03 adds the session-login endpoint that sets the cookie; until then
 * this middleware is correct and simply has nothing to attach.
 */
const djangoSession: Middleware = {
  async onRequest({ request }) {
    request.headers.set('Accept', 'application/json')
    if (!SAFE_METHODS.has(request.method.toUpperCase())) {
      const token = readCookie(CSRF_COOKIE)
      if (token) {
        request.headers.set('X-CSRFToken', token)
      }
    }
    return request
  },
}

/**
 * `baseUrl: '/'` - relative to whatever origin served the page. In dev that is
 * Vite on 5173 with `/api` proxied to Django on 8000; in production it is
 * Django itself. Neither case has a host to hardcode, and hardcoding one is
 * how a build ends up pointing at somebody else's engine (CLAUDE.md section 7:
 * nothing Kevin-hosted).
 */
export const api = createClient<paths>({
  baseUrl: '/',
  credentials: 'same-origin',
  // `openapi-fetch` reads `globalThis.fetch` ONCE, when `createClient` runs -
  // and `createClient` runs when this module is first imported, which is
  // whenever the first route that needs it happens to load. Whatever `fetch`
  // was at that instant is the one every later call uses. Passing this thunk
  // moves the lookup to call time, which is what a reader expects and what
  // any test double or instrumentation installed after startup needs. Found
  // by a test: a mock installed for one case was still serving the next one.
  fetch: (request) => globalThis.fetch(request),
})

api.use(djangoSession)
