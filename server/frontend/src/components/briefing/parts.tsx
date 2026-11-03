import {
  AlertTriangle,
  Check,
  CircleHelp,
  Clock,
  Link2Off,
  Waves,
  type LucideIcon,
} from 'lucide-react'
import type { ReactNode } from 'react'

import type { TrustWord } from '@/lib/briefing'
import { cn } from '@/lib/utils'

/**
 * The small parts every section of the Morning briefing is made of
 * (recruiter-demo ticket 14, prototype A): a numbered section heading, the
 * tonal card, the trust chips and the status labels.
 *
 * **A status or a trust word is always an icon AND a word** (ADR 0053: colour
 * carries no meaning alone). The colours are the app's own tokens, so dark mode
 * is the selected dark palette, not a flip.
 */

export function BriefingSection({
  n,
  id,
  title,
  aside,
  children,
}: {
  n: number
  id: string
  title: string
  aside?: ReactNode
  children: ReactNode
}) {
  return (
    <section aria-labelledby={id} className="flex flex-col">
      <div className="mb-2.5 ml-1 flex items-center justify-between gap-3">
        <h2 id={id} className="flex items-center gap-2 text-[0.8125rem] font-semibold tracking-[0.02em] text-muted-foreground">
          <span aria-hidden="true" className="grid size-[22px] place-items-center rounded-full bg-surface-3 text-[0.75rem] text-foreground">
            {n}
          </span>
          {title}
        </h2>
        {aside}
      </div>
      {children}
    </section>
  )
}

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('rounded-sheet bg-card px-4 py-3.5 @2xl:px-5', className)}>{children}</div>
}

/** A card's own heading line: name on the left, a quiet link or note on the right. */
export function CardHead({ title, aside }: { title: ReactNode; aside?: ReactNode }) {
  return (
    <div className="mb-2 flex items-center justify-between gap-3">
      <h3 className="text-[0.9375rem] font-semibold">{title}</h3>
      {aside}
    </div>
  )
}

const TRUST: Record<TrustWord, { icon: LucideIcon; className: string }> = {
  Verified: { icon: Check, className: 'bg-done-container text-done' },
  Unverified: { icon: CircleHelp, className: 'bg-unverified-bg text-unverified-fg' },
  Estimate: { icon: Waves, className: 'border border-dashed border-current text-estimate-fg' },
}

/** "Verified", "Unverified" or "Estimate": the word, with its icon, on its chip. */
export function TrustChip({ word, className }: { word: TrustWord; className?: string }) {
  const { icon: Icon, className: tone } = TRUST[word]
  return (
    <span
      className={cn(
        'inline-flex h-5 shrink-0 items-center gap-1 rounded-full px-2 text-[0.75rem] leading-none font-semibold',
        tone,
        className,
      )}
    >
      <Icon aria-hidden="true" className="size-3" strokeWidth={2.5} />
      {word}
    </span>
  )
}

export type Status = 'Overdue' | 'Due soon' | 'Due today' | 'To confirm' | 'Paused'

const STATUS: Record<Status, { icon: LucideIcon; className: string }> = {
  Overdue: { icon: AlertTriangle, className: 'text-destructive' },
  'Due soon': { icon: AlertTriangle, className: 'text-estimate-fg' },
  'Due today': { icon: Clock, className: 'text-primary' },
  'To confirm': { icon: CircleHelp, className: 'text-unverified-fg dark:text-estimate-fg' },
  Paused: { icon: Link2Off, className: 'text-destructive' },
}

/** The left column of a "Worth a look" row: what kind of thing it is, icon and word. */
export function StatusLabel({ status }: { status: Status }) {
  const { icon: Icon, className } = STATUS[status]
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-[0.8125rem] font-semibold', className)}>
      <Icon aria-hidden="true" className="size-4" />
      {status}
    </span>
  )
}

/** A quiet sentence: an empty section, or one that could not be read. */
export function Quiet({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cn('text-[0.9375rem] text-muted-foreground', className)}>{children}</p>
}

/** The round tinted icon at the head of a row. */
export function RowIcon({ icon: Icon, tone = 'primary' }: { icon: LucideIcon; tone?: 'primary' | 'done' | 'shared' | 'unverified' }) {
  const tones = {
    primary: 'bg-primary-container text-primary-container-foreground',
    done: 'bg-done-container text-done',
    shared: 'bg-shared text-shared-foreground',
    unverified: 'bg-unverified-bg text-unverified-fg',
  }
  return (
    <span aria-hidden="true" className={cn('grid size-9 shrink-0 place-items-center rounded-xl', tones[tone])}>
      <Icon className="size-[18px]" />
    </span>
  )
}
