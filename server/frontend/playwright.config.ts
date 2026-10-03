import { defineConfig } from '@playwright/test'

/**
 * Playwright here is for LOOKING, not for regression (spec: "screenshot
 * regression tests" are out of scope). `npm run shots` builds the real bundle,
 * serves it with `vite preview`, answers every `/api` call from the fake engine
 * in `src/test/engine.ts`, and writes screenshots a person can open.
 *
 * Over http, never `file://`: the app is same-origin with its API by design and
 * a `file://` page cannot resolve `/api/...` at all.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  // Screens are independent and each test builds its own engine, so they could
  // run in parallel, but a preview server on one port and ~12 full-page shots do
  // not need it, and serial output is easier to read when one fails.
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    // The PWA's service worker would answer navigations itself and sit between
    // the page and `page.route`, so shots would depend on cache state.
    serviceWorkers: 'block',
  },
  webServer: {
    command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: true,
    timeout: 180_000,
  },
})
