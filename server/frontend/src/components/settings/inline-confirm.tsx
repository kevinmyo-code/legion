import { useState } from 'react'

import { Button } from '@/components/ui/button'

/**
 * The one confirm every Settings action that cannot be undone uses: it opens in
 * place (no interstitial, so nothing to lose your place behind), restates WHO or
 * WHAT in the question, says in one plain sentence what happens next, and offers
 * a cancel that is the first thing a thumb lands on.
 *
 * Nothing is done until the engine confirms. A refusal keeps the confirm open and
 * shows the engine's sentence, which opens with what did NOT happen
 * (`src/api/refusal.ts`).
 */
export function InlineConfirm({
  question,
  consequence,
  confirmLabel,
  busyLabel,
  onConfirm,
  onClose,
}: {
  question: string
  consequence: string
  confirmLabel: string
  busyLabel: string
  /** Resolve only if the engine accepted it; reject with its sentence. */
  onConfirm: () => Promise<unknown>
  onClose: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  async function run() {
    setBusy(true)
    setProblem(null)
    try {
      await onConfirm()
      onClose()
    } catch (error) {
      setProblem(error instanceof Error ? error.message : 'Nothing was changed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      role="group"
      aria-label={question}
      className="mt-3 flex flex-col gap-3 rounded-control bg-surface-2 p-4"
    >
      <p className="text-[0.9375rem] font-medium">{question}</p>
      <p className="text-[0.9375rem] text-muted-foreground">{consequence}</p>
      {problem && (
        <p
          role="alert"
          className="rounded-control bg-destructive-container px-4 py-3 text-[0.9375rem] text-destructive-container-foreground"
        >
          {problem}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={onClose} disabled={busy}>
          Keep
        </Button>
        <Button variant="destructive" onClick={run} disabled={busy}>
          {busy ? busyLabel : confirmLabel}
        </Button>
      </div>
    </div>
  )
}
