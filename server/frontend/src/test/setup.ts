import '@testing-library/jest-dom/vitest'

import { cleanup, configure } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'

import { stubSurface } from './surface'

// The default 1 s `findBy*` / `waitFor` budget is not enough for the FIRST render
// of a route in a file: the router code-splits each route, and Vite transforms
// that chunk on demand, which under a parallel full run (19 files, one cold
// transform each) took longer than a second and failed three tests that pass
// alone. Nothing here waits longer than the page actually needs; a test that
// passes still passes at once. This only stops a loaded machine failing a test
// that is correct.
configure({ asyncUtilTimeout: 8000 })

// Testing Library does not unmount between tests on its own outside a
// globals-aware runner setup; without this a second render finds two copies of
// every element and the failure reads as a duplicate-element bug in the app.
afterEach(cleanup)

// Every test starts on the family surface (the narrow one) with a light device.
// jsdom has no layout, so which surface renders is a thing the test chooses:
// call `stubSurface('workbench')` at the top of a test to widen it. Reset here
// because the stub replaces a window property that outlives a single test, and
// so do the theme class and the stored theme preference.
beforeEach(() => {
  stubSurface('family')
  window.localStorage.clear()
  document.documentElement.classList.remove('dark')
})

/**
 * Make a relative request URL resolve, the way a browser does.
 *
 * jsdom implements no `fetch` and no `Request`, so under Vitest those globals
 * come from Node's undici instead - and undici's `Request` throws
 * "Failed to parse URL" on a relative path, where every browser resolves it
 * against the document. `api` in `src/api/client.ts` is built with
 * `baseUrl: '/'` on purpose (there is no host to hardcode), so without this
 * shim every test of a real call fails on an environment gap rather than on
 * anything the app does.
 *
 * This is a test-environment repair, not a workaround for a production bug:
 * the same code path works unchanged in a browser, which is what the smoke
 * test is asserting about.
 */
const UndiciRequest = globalThis.Request

class BrowserRelativeRequest extends UndiciRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    const resolved =
      typeof input === 'string' && input.startsWith('/')
        ? new URL(input, window.location.origin).toString()
        : input
    super(resolved, init)
  }
}

globalThis.Request = BrowserRelativeRequest
