import { useEffect, useState } from 'react'

/**
 * While a visit is being entered (`POST /api/demo/enter`, about 15 s: each visitor gets a seeded
 * copy of the demo household of their own): what is happening, roughly how long, and a calm bar.
 * The bar is an estimate from the clock, not the server's progress: it eases toward the end and
 * stops short of full, so it never says "done" before the answer comes. With reduced motion it
 * moves once a second, with no glide.
 */
export const ENTER_WAIT_LINE = 'Setting up your own private copy of the demo. This takes about 15 seconds.'

/** How full the bar is after `ms`: about 88% at 16 s, never more than 95%. */
export function enterProgress(ms: number): number {
  return 0.95 * (1 - Math.exp(-Math.max(ms, 0) / 6000))
}

function reducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export function EnterWait({ className }: { className?: string }) {
  const [elapsed, setElapsed] = useState(0)
  const [still] = useState(reducedMotion)
  useEffect(() => {
    const start = Date.now()
    const timer = setInterval(() => setElapsed(Date.now() - start), still ? 1000 : 250)
    return () => clearInterval(timer)
  }, [still])
  return (
    <div className={className} data-stage="enter-wait">
      <p role="status">{ENTER_WAIT_LINE}</p>
      {/* Decoration: the words above are the status; an estimate is never read out as a figure. */}
      <div aria-hidden="true" className="mt-2 h-1 w-full max-w-[22rem] overflow-hidden bg-current/15">
        <div
          data-stage="enter-bar"
          className={`h-full origin-left bg-current ${still ? '' : 'transition-transform duration-300 ease-linear'}`}
          style={{ transform: `scaleX(${enterProgress(elapsed).toFixed(3)})` }}
        />
      </div>
    </div>
  )
}
