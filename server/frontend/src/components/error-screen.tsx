import type { ErrorComponentProps } from '@tanstack/react-router'
import { Component, type ErrorInfo, type ReactNode } from 'react'

/**
 * What a person sees when a screen throws (architecture review 2026-11-10, item 5).
 *
 * Before this there was no error boundary anywhere, the public `/try` demo included:
 * a render error left a blank page, or TanStack's own developer screen with the raw
 * message. This says in words that the page failed, offers the two things that help
 * (try again, reload), and never shows the error's text or stack: those are for the
 * server log and the browser console, not for a recruiter's screen.
 */
function ErrorPanel({ onRetry }: { onRetry: () => void }) {
  return (
    <div role="alert" className="mx-auto max-w-md p-6">
      <h1 className="text-lg font-semibold">This page stopped working</h1>
      <p className="text-muted-foreground mt-2 text-sm">
        Something went wrong while showing it. Nothing you entered was sent because of this error.
        Try again, or reload the page.
      </p>
      <div className="mt-4 flex gap-3">
        <button type="button" className="rounded-md border px-3 py-1.5 text-sm" onClick={onRetry}>
          Try again
        </button>
        <button
          type="button"
          className="rounded-md border px-3 py-1.5 text-sm"
          onClick={() => window.location.reload()}
        >
          Reload
        </button>
      </div>
    </div>
  )
}

/** The router's `defaultErrorComponent`: any route that throws while loading or rendering. */
export function RouteErrorScreen({ error, reset }: ErrorComponentProps) {
  console.error(error)
  return <ErrorPanel onRetry={reset} />
}

type BoundaryState = { failed: boolean }

/** Around the whole app, for anything that throws outside a route (providers, the router itself). */
export class AppErrorBoundary extends Component<{ children: ReactNode }, BoundaryState> {
  state: BoundaryState = { failed: false }

  static getDerivedStateFromError(): BoundaryState {
    return { failed: true }
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error(error, info.componentStack)
  }

  render() {
    if (this.state.failed) {
      return <ErrorPanel onRetry={() => this.setState({ failed: false })} />
    }
    return this.props.children
  }
}
