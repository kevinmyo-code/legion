import { Outlet, createRootRoute } from '@tanstack/react-router'

/**
 * The shell every route renders inside, deliberately thin: `/login` needs
 * no chrome of its own, and `/_authed` (ticket 05's real navigation -
 * `src/components/app-shell.tsx`) wraps everything that does. Putting the
 * rail/bottom-bar chrome here instead would render it around the sign-in
 * form too, before there is anyone to show a household name for.
 */
export const Route = createRootRoute({
  component: RootLayout,
  notFoundComponent: NotFound,
})

function RootLayout() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Outlet />
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
    <div className="p-4">
      <h1 className="text-lg font-semibold">No such page</h1>
      <p className="text-muted-foreground text-sm">
        That address does not match anything in LEGION.
      </p>
    </div>
  )
}
