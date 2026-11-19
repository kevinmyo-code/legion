import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter } from '@tanstack/react-router'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import { AppErrorBoundary } from './components/error-screen'
import './index.css'
import { clearStaleModalEntry } from './lib/modal-entry'
import { installReloadOnUpdate } from './lib/sw-reload'
import { applyTheme, watchSystemTheme } from './lib/theme'
import { appRouterOptions } from './router-options'
import { routeTree } from './routeTree.gen'

// `index.html` already set the `dark` class before first paint; this finishes the
// job (the `theme-color` metas) and follows the device live while the preference
// is System.
applyTheme()
watchSystemTheme()

// A deploy's new service worker takes over open pages at once; reload into its
// bundle, never over unsaved input (`lib/sw-reload.ts`).
installReloadOnUpdate()

// A modal's history entry left on top by a reload is an ordinary entry again (`lib/modal-entry.ts`).
clearStaleModalEntry()
const router = createRouter({ routeTree, ...appRouterOptions })

// Makes `<Link to="...">` and `useParams()` typed against the generated route
// tree. Without it every route path is a bare string again, which is most of
// what TanStack Router was chosen for.
declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

/**
 * Query is the web's Room: a cache per query key, not a store.
 *
 * Retries are off by default because a slow first response and a failed one are
 * different things, and silently retrying hides which of the two happened from
 * the screen whose job is to say so in words. (This comment first justified
 * that by Cloud Run's scale-to-zero cold start; hosting was reopened the same
 * day - web-and-households ticket 12 moves the engine to an always-on VM - and
 * the reasoning survives the move, so only the example is gone.)
 */
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AppErrorBoundary>
  </StrictMode>,
)
