import { Link } from '@tanstack/react-router'
import { ChevronLeft } from 'lucide-react'
import type { ReactNode } from 'react'

import { useSurface } from '@/lib/surface'

/**
 * The frame every Settings sub-screen sits in: a way back to the index on the
 * family surface (the workbench has its side list instead, which is a different
 * tree and not rendered here), a title, and the panels.
 */
export function SettingsPage({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle?: ReactNode
  children: ReactNode
}) {
  const surface = useSurface()
  return (
    <div className="flex max-w-2xl flex-col gap-5 pb-6">
      <header className="flex flex-col gap-1 pt-1">
        {surface === 'family' && (
          <Link
            to="/settings"
            className="-ml-2 inline-flex min-h-11 items-center gap-1 self-start rounded-full px-2 text-[0.9375rem] font-medium text-primary"
          >
            <ChevronLeft className="size-5" />
            Settings
          </Link>
        )}
        <h1 className="text-[1.75rem] leading-tight font-medium lg:text-[2rem]">{title}</h1>
        {subtitle && <p className="text-[0.9375rem] text-muted-foreground">{subtitle}</p>}
      </header>
      {children}
    </div>
  )
}

/** What a write that the engine accepted says, in words. Never a tint alone. */
export function OkSentence({ children }: { children: ReactNode }) {
  return (
    <p
      role="status"
      className="rounded-control bg-done-container px-4 py-3 text-[0.9375rem] text-foreground"
    >
      {children}
    </p>
  )
}
