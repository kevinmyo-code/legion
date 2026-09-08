import { Link, Outlet, createRootRoute } from '@tanstack/react-router'

/**
 * The shell every route renders inside. Deliberately almost empty: ticket 05
 * owns the real navigation and the design language, and a placeholder chrome
 * built here would be thrown away by it. What this scaffold has to prove is
 * that routing, the generated client and the Django proxy all work end to end.
 */
export const Route = createRootRoute({
  component: RootLayout,
  notFoundComponent: NotFound,
})

function RootLayout() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b px-4 py-3">
        <nav className="flex items-center gap-4 text-sm">
          <Link to="/" className="font-semibold">
            LEGION
          </Link>
          <Link to="/login">Sign in</Link>
        </nav>
      </header>
      <main className="p-4">
        <Outlet />
      </main>
    </div>
  )
}

/**
 * The SPA owns its own 404s. Django's catch-all hands any unmatched path to
 * this bundle (see `legion/urls.py`), so an unknown URL has to be answered
 * here or the user gets a blank page with no explanation.
 */
function NotFound() {
  return (
    <div>
      <h1 className="text-lg font-semibold">No such page</h1>
      <p className="text-muted-foreground text-sm">
        That address does not match anything in LEGION.
      </p>
    </div>
  )
}
