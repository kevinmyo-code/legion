import '@testing-library/jest-dom/vitest'

import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Testing Library does not unmount between tests on its own outside a
// globals-aware runner setup; without this a second render finds two copies of
// every element and the failure reads as a duplicate-element bug in the app.
afterEach(cleanup)

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
