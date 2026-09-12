import path from 'node:path'

import tailwindcss from '@tailwindcss/vite'
import { tanstackRouter } from '@tanstack/router-plugin/vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'
// `defineConfig` comes from vitest, not vite: it is the same function widened
// with the `test` block below, so one config file covers build and tests.
import { defineConfig } from 'vitest/config'

// Django serves the built bundle from its own origin (ADR 0044: one engine,
// the web app is a limb of it), so there is no CDN prefix and no second host.
// Same-origin is also what makes the session cookie work at all - a cross-origin
// SPA would need CORS and SameSite=None, and neither is worth carrying for a
// monolith.
export default defineConfig({
  // Served from the site root, never a sub-path. Stated rather than left to
  // Vite's default because `spa_index` serves one hand-written `index.html`
  // whose asset URLs have to resolve from any SPA route, `/a/b/c` included.
  base: '/',
  plugins: [
    // Must precede the React plugin: it generates `src/routeTree.gen.ts` from
    // `src/routes/**`, and React's transform has to see the generated file.
    tanstackRouter({ target: 'react', autoCodeSplitting: true }),
    react(),
    tailwindcss(),
    VitePWA({
      // The shell updates itself on the next load rather than asking. There is
      // no in-app update prompt to build yet, and a stale shell talking to a
      // moved API is the failure this avoids.
      registerType: 'autoUpdate',
      manifest: {
        name: 'LEGION',
        short_name: 'LEGION',
        description: 'The household engine, on the web.',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        // Both values are raw tokens from the phone's mission-control palette
        // (`app/.../ui/theme/Color.kt`), not eyeballed near-matches: the OS
        // chrome around an installed PWA should be the same two colours the
        // Android app already uses. `background_color` is DeckGround, the
        // pure-black OLED ground the whole palette is built on;
        // `theme_color` is DeckChrome, which is literally the chrome tier.
        background_color: '#000000',
        theme_color: '#FF5330',
        icons: [
          { src: '/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // Without this, the service worker answers EVERY navigation with the
        // cached `index.html`, including the ones Django owns. `/admin` would
        // render the SPA shell, `/api` HTML instead of JSON, `/media` a page
        // instead of a file. The denylist is the mirror image of the negative
        // lookahead in `legion/urls.py` - the same set of Django-owned prefixes,
        // enforced a second time in the browser because the service worker
        // never reaches the server to be told.
        navigateFallbackDenylist: [/^\/api/, /^\/admin/, /^\/media/, /^\/static/, /^\/health/],
        runtimeCaching: [
          {
            // NetworkOnly, and it is a rule not a default. CLAUDE.md section 4
            // makes provenance and the unverified label load-bearing; a cached
            // API response is a figure with no way to know how old it is, shown
            // as if it were current. The shell may be stale, the data may not.
            urlPattern: ({ url }) => url.pathname.startsWith('/api/'),
            handler: 'NetworkOnly',
          },
        ],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  build: {
    // Django's `STATICFILES_DIRS` points at `server/static`, so the bundle
    // lands where `collectstatic` and `web.views.spa_index` both look for it.
    // Never committed - see `server/.gitignore`; the Docker build (ticket 09)
    // produces it.
    outDir: '../static/app',
    emptyOutDir: true,
  },
  server: {
    // Everything Django owns is proxied to the dev server so the browser sees
    // ONE origin on 5173. The session cookie, CSRF and `same-origin` fetches
    // then behave in dev exactly as they will in production, where Django
    // serves both the shell and the API from the same host.
    proxy: {
      '/api': { target: 'http://127.0.0.1:8000', changeOrigin: false },
      '/admin': { target: 'http://127.0.0.1:8000', changeOrigin: false },
      '/static': { target: 'http://127.0.0.1:8000', changeOrigin: false },
      '/media': { target: 'http://127.0.0.1:8000', changeOrigin: false },
      '/health': { target: 'http://127.0.0.1:8000', changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    // Fixed to a real, non-UTC zone (not the host machine's own) so the
    // local-day arithmetic in `src/lib/day.ts` is tested against an actual
    // UTC offset - the exact bug class the phone found (a UTC date-part
    // slice reading as the wrong day). Set here, once, before any worker
    // touches `Date`: V8 caches the resolved local timezone on first use, so
    // reassigning `process.env.TZ` from inside a running test file is not
    // reliable when Vitest reuses a worker across files.
    env: { TZ: 'America/Chicago' },
  },
})
