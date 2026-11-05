import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

import { BarsView } from './bars'
import { CarView } from './car'
import { ChecklistView } from './checklist'
import { DiagramView } from './diagram'
import { StepsView } from './steps'
import { TimelineView } from './timeline'
import type { Card } from './types'

/**
 * A tool's card, drawn where the tool ran (Kevin, 2026-11-04: generative UI).
 * Every value is text: a bar's length repeats a figure printed beside it, a flag
 * is a word, never a colour alone (CLAUDE.md section 4 rule 7).
 *
 * `data-card="..."` marks the parts a page may restyle (`.recruiter [data-card]`
 * in `index.css`); like `data-chat`, they are stable hooks.
 */
export function ToolCard({ card, className }: { card: Card; className?: string }) {
  return (
    <CardFrame card={card} className={className}>
      {card.card === 'bars' ? (
        <BarsView card={card} />
      ) : card.card === 'timeline' ? (
        <TimelineView card={card} />
      ) : card.card === 'car' ? (
        <CarView card={card} />
      ) : card.card === 'steps' ? (
        <StepsView card={card} />
      ) : card.card === 'diagram' ? (
        <DiagramView card={card} />
      ) : (
        <ChecklistView card={card} />
      )}
    </CardFrame>
  )
}

function CardFrame({ card, className, children }: { card: Card; className?: string; children: ReactNode }) {
  const sub = [card.scope, card.period].filter(Boolean).join(' · ')
  return (
    <section
      role="region"
      aria-label={card.title}
      data-card="root"
      data-kind={card.card}
      className={cn(
        'w-full max-w-[26rem] self-start rounded-card border border-outline-variant bg-surface-1 px-3.5 py-3 text-[0.875rem]',
        className,
      )}
    >
      <header data-card="head" className="mb-2">
        <h3 data-card="title" className="leading-tight font-semibold">
          {card.title}
        </h3>
        {sub && (
          <p data-card="sub" className="text-[0.8125rem] text-muted-foreground">
            {sub}
          </p>
        )}
      </header>
      {children}
      {card.note && (
        <p data-card="note" className="mt-2 text-[0.8125rem] text-muted-foreground">
          {card.note}
        </p>
      )}
    </section>
  )
}
