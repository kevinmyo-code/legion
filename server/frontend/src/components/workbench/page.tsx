import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

/**
 * The parts every workbench screen is made of: a page header, a tonal panel,
 * and the two trust words.
 *
 * **The trust words are text, never a colour or an icon alone** (CLAUDE.md
 * section 7, ADR 0053: "colour carries no meaning alone"). `unverified` is the
 * amber chip with the word in it; `estimate` is the same word in the amber ink on
 * the page ground. Both render in the surrounding font, beside the figure they
 * qualify, and nothing here collapses them behind a tooltip or a "learn more" -
 * if a layout only looks clean once the disclosure is hidden, the layout is
 * wrong.
 */

export function PageHeader({
  title,
  subtitle,
  action,
}: {
  title: string
  subtitle?: ReactNode
  action?: ReactNode
}) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-[2rem] leading-tight font-medium">{title}</h1>
        {subtitle && <p className="mt-1 max-w-3xl text-muted-foreground">{subtitle}</p>}
      </div>
      {action && <div className="flex items-center gap-2">{action}</div>}
    </header>
  )
}

export function Panel({
  title,
  description,
  action,
  children,
  className,
}: {
  title: string
  description?: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={cn('rounded-sheet bg-card p-5 md:p-6', className)}>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-xl leading-snug">{title}</h2>
          {description && (
            <p className="mt-0.5 max-w-3xl text-[0.8125rem] text-muted-foreground">{description}</p>
          )}
        </div>
        {action && <div className="flex shrink-0 items-center gap-2">{action}</div>}
      </div>
      {children}
    </section>
  )
}

/** The word "unverified", on the amber chip, beside the figure or row it qualifies. */
export function Unverified({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex h-6 items-center rounded-full bg-unverified-bg px-2.5 text-[0.8125rem] font-medium text-unverified-fg',
        className,
      )}
    >
      unverified
    </span>
  )
}

/** The word "estimate", in the amber ink, beside a figure a source never stated. */
export function Estimate({ className }: { className?: string }) {
  return (
    <span className={cn('text-[0.8125rem] font-medium text-estimate-fg', className)}>estimate</span>
  )
}

/** A figure with its disclosure word next to it, as one unbreakable unit. */
export function Figure({
  children,
  word,
}: {
  children: ReactNode
  word: 'unverified' | 'estimate'
}) {
  return (
    <span className="inline-flex flex-wrap items-center gap-x-1.5">
      <span>{children}</span>
      {word === 'unverified' ? <Unverified /> : <Estimate />}
    </span>
  )
}

/** A quiet one-line sentence, for an empty panel. */
export function EmptySentence({ children }: { children: ReactNode }) {
  return <p className="rounded-control bg-surface-2 px-4 py-3 text-[0.9375rem] text-muted-foreground">{children}</p>
}

/** The error sentence, in the tonal error container, never a bare red line. */
export function ErrorSentence({ children, role = 'alert' }: { children: ReactNode; role?: 'alert' | 'status' }) {
  return (
    <p
      role={role}
      className="rounded-control bg-destructive-container px-4 py-3 text-[0.9375rem] text-destructive-container-foreground"
    >
      {children}
    </p>
  )
}
