import { useEffect, useRef, type CSSProperties } from 'react'

import type { SuggestChip } from '@/assistant/cascade-client'

/**
 * The engine's suggestion chips as one row that scrolls sideways (`demo/suggestions.py` decides
 * them; the page writes none). Pills at least 44 px tall, one line, never wider than the column;
 * the edge the row can still scroll toward fades out, so a cut-off chip reads as "more this way".
 * Each chip comes in with a short rise (the stylesheet; none under reduced motion).
 */
export function ChipRow({ chips, onTap }: { chips: readonly SuggestChip[]; onTap: (chip: SuggestChip) => void }) {
  const row = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = row.current
    if (!el) return
    const edges = () => {
      const start = el.scrollLeft > 1
      const end = el.scrollLeft + el.clientWidth < el.scrollWidth - 1
      el.dataset.fade = [start && 'start', end && 'end'].filter(Boolean).join(' ')
    }
    edges()
    el.addEventListener('scroll', edges, { passive: true })
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(edges)
    observer?.observe(el)
    return () => {
      el.removeEventListener('scroll', edges)
      observer?.disconnect()
    }
  }, [chips])
  return (
    <div
      ref={row}
      data-stage="chips"
      role="group"
      aria-label="Suggested questions"
      className="rc-chips"
    >
      {chips.map((chip, index) => (
        <button
          key={chip.token}
          type="button"
          className="rc-chip"
          style={{ '--i': index } as CSSProperties}
          title={chip.question}
          onClick={() => onTap(chip)}
        >
          {chip.label}
        </button>
      ))}
    </div>
  )
}
