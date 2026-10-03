import type { ReactNode } from 'react'

/**
 * The frame for the pages a person sees BEFORE they are signed in (join, enter a
 * code): the app's name above one card, centred, and nothing else. No navigation,
 * because there is nowhere to navigate to yet and a tab bar for screens that need
 * a session would only be a locked door drawn on the wall.
 *
 * The name is LEGION, the app. It is never an assistant's (CLAUDE.md section 1).
 */
export function AuthFrame({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-6 px-4 py-10 pt-[calc(2.5rem+env(safe-area-inset-top))]">
      <p className="text-[0.9375rem] font-medium tracking-[0.2em] text-muted-foreground">LEGION</p>
      {children}
    </div>
  )
}
