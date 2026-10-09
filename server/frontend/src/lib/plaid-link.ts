/**
 * Plaid Link, loaded at runtime from Plaid's own CDN.
 *
 * Plaid's terms want Link's script served from their host, so it is injected
 * once as a script tag and never bundled or installed from npm (ADR 0057). This
 * module is the only place that knows the script's URL or the shape of
 * `window.Plaid`; the screen asks `loadPlaid()` for it. A test that sets
 * `window.Plaid` before pressing a button skips the network entirely, which is
 * how the settings tests stand a fake Link in.
 */

export const PLAID_LINK_SRC = 'https://cdn.plaid.com/link/v2/stable/link-initialize.js'

export interface PlaidLinkError {
  error_code?: string
  error_message?: string
  display_message?: string | null
}

export interface PlaidCreateOptions {
  token: string
  onSuccess: (publicToken: string, metadata?: unknown) => void
  onExit: (error: PlaidLinkError | null, metadata?: unknown) => void
}

export interface PlaidHandler {
  open: () => void
}

export interface PlaidGlobal {
  create: (options: PlaidCreateOptions) => PlaidHandler
}

declare global {
  interface Window {
    Plaid?: PlaidGlobal
  }
}

let pending: Promise<PlaidGlobal> | null = null

/** Resolve with `window.Plaid`, injecting Plaid's script on the first call.
 * Rejects (and forgets the attempt, so a retry is a real retry) when the script
 * cannot be fetched, e.g. offline or blocked. */
export function loadPlaid(): Promise<PlaidGlobal> {
  if (window.Plaid) return Promise.resolve(window.Plaid)
  if (pending) return pending
  pending = new Promise<PlaidGlobal>((resolve, reject) => {
    const fail = () => {
      pending = null
      document.querySelector(`script[src="${PLAID_LINK_SRC}"]`)?.remove()
      reject(new Error('Could not load Plaid Link from Plaid.'))
    }
    const script = document.createElement('script')
    script.src = PLAID_LINK_SRC
    script.async = true
    script.onload = () => (window.Plaid ? resolve(window.Plaid) : fail())
    script.onerror = fail
    document.head.appendChild(script)
  })
  return pending
}

/** What Link said when it closed without connecting, in a sentence. */
export function exitSentence(error: PlaidLinkError | null): string | null {
  if (!error) return null
  const said = error.display_message?.trim() || error.error_message?.trim() || error.error_code
  return said ? said : 'Plaid gave no reason.'
}
