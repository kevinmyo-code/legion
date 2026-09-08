import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter } from '@tanstack/react-router'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import './index.css'
import { routeTree } from './routeTree.gen'

const router = createRouter({ routeTree })

// Makes `<Link to="...">` and `useParams()` typed against the generated route
// tree. Without it every route path is a bare string again, which is most of
// what TanStack Router was chosen for.
declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

/**
 * Query is the web's Room: a cache per query key, not a store. Retries are off
 * by default here because the engine is a Cloud Run service that scales to
 * zero - a cold start is a slow first response, not a failed one, and silently
 * retrying hides which of the two happened from the screen that has to say so
 * in words.
 */
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
