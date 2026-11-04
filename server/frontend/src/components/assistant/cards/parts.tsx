import type { ReactNode } from 'react'

/** "3 more not shown." */
export function More({ count }: { count: number }) {
  if (count <= 0) return null
  return (
    <p data-card="more" className="mt-1 text-[0.8125rem] text-muted-foreground">
      {count} more not shown.
    </p>
  )
}

/** A flag in words beside its value. */
export function FlagWord({ children }: { children: ReactNode }) {
  return (
    <span data-card="flag" className="ml-1.5 rounded-sm bg-unverified-bg px-1 text-[0.75rem] text-unverified-fg">
      {children}
    </span>
  )
}
