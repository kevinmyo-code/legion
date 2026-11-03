import { Link } from '@tanstack/react-router'

import { useVisitEnd } from '@/lib/recruiter'

/**
 * The slim strip a recruiter's visit sees on every app screen (ticket 13,
 * ruling 2): what this is, until when it is theirs, and the way back to the
 * recruiter section. Words only, no colour-coded state.
 */
export function DemoBanner() {
  const until = useVisitEnd(true)
  return (
    <div
      role="note"
      aria-label="Demo visit"
      className="mb-4 flex flex-wrap items-center gap-x-2 gap-y-1 rounded-2xl bg-surface-3 px-4 py-2 text-[0.875rem] text-muted-foreground"
    >
      <span>
        Demo: made-up household, yours{until ? ` until ${until}` : ' for this visit'}.
      </span>
      <Link
        to="/try"
        className="font-medium text-primary underline-offset-4 outline-none hover:underline focus-visible:outline-2 focus-visible:outline-primary"
      >
        About Kevin
      </Link>
    </div>
  )
}

/** What a screen a visit may not use shows instead of breaking (ticket 13). */
export function RecruiterRefusal({ reason }: { reason: string }) {
  return (
    <section aria-labelledby="demo-refusal" className="flex max-w-xl flex-col gap-2 py-8">
      <h1 id="demo-refusal" className="text-xl font-semibold">
        Not on a demo visit
      </h1>
      <p className="text-muted-foreground">{reason} Nothing was read or changed.</p>
      <p>
        <Link to="/" className="font-medium text-primary underline-offset-4 hover:underline">
          Back to Home
        </Link>
      </p>
    </section>
  )
}
