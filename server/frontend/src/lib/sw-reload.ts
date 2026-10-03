/**
 * Reload once when a new deploy's service worker takes over this page.
 *
 * `src/sw.ts` calls `skipWaiting()` and `clients.claim()`, so a new worker takes
 * control of an open page as soon as it installs. Without a reload the page keeps
 * running the old bundle it was served from the old precache. On 2026-10-03 the
 * first deploy of the web revamp looked like "still the old site" for exactly this
 * reason.
 *
 * Rules:
 * - **First install never reloads.** If no worker controlled the page when it
 *   loaded, the page is already running the current bundle, and `clients.claim()`
 *   firing `controllerchange` means nothing changed for the person. A LATER
 *   takeover in that same page life is a real deploy, and reloads.
 * - **Never reload over unsaved input.** While a dialog is open or a field has
 *   focus, the reload waits until the page is next hidden (tab switched, app
 *   backgrounded), so the person never loses what they were typing.
 * - **Once.** A second `controllerchange` in the same page life does nothing.
 */
export interface ReloadDeps {
  hadController: boolean
  isBusy: () => boolean
  isHidden: () => boolean
  reload: () => void
  onHidden: (run: () => void) => void
}

export function makeControllerChangeHandler(deps: ReloadDeps): () => void {
  let controlled = deps.hadController
  let done = false
  return () => {
    if (!controlled) {
      controlled = true
      return
    }
    if (done) return
    done = true
    if (deps.isHidden() || !deps.isBusy()) {
      deps.reload()
      return
    }
    deps.onHidden(deps.reload)
  }
}

/** True while someone may be mid-edit: an open dialog, or focus in a field. */
export function pageIsBusy(doc: Document = document): boolean {
  if (doc.querySelector('[role="dialog"], [role="alertdialog"]')) return true
  const active = doc.activeElement
  if (!active) return false
  const tag = active.tagName
  return (
    tag === 'INPUT' ||
    tag === 'TEXTAREA' ||
    tag === 'SELECT' ||
    (active as HTMLElement).isContentEditable === true
  )
}

export function installReloadOnUpdate(): void {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return
  const container = navigator.serviceWorker
  const handler = makeControllerChangeHandler({
    hadController: container.controller !== null,
    isBusy: () => pageIsBusy(),
    isHidden: () => document.visibilityState === 'hidden',
    reload: () => window.location.reload(),
    onHidden: (run) => {
      const listener = () => {
        if (document.visibilityState !== 'hidden') return
        document.removeEventListener('visibilitychange', listener)
        run()
      }
      document.addEventListener('visibilitychange', listener)
    },
  })
  container.addEventListener('controllerchange', handler)
}
