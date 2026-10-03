import { Trash2 } from 'lucide-react'
import { useState } from 'react'

import { Button } from '@/components/ui/button'

/**
 * A delete that asks first and says what it does.
 *
 * Same flow as `checklist-delete.tsx`, which this mirrors so the web has one
 * delete gesture and not two: a click opens an inline confirmation (a mis-tap
 * needs a moment to back out of), the confirmation says what the delete takes
 * and what it leaves, and nothing is removed from the screen until the engine
 * confirms. On a refusal the control stays in its confirming state and shows
 * the engine's sentence, which begins with what was NOT deleted.
 *
 * Every delete in this API is a soft tombstone (the row stays so a phone that
 * has not synced learns it is gone), so "delete" is accurate for the person and
 * the consequence sentence is where a table's own caveat goes.
 */
export function ConfirmDelete({
  label,
  consequence,
  confirmText = 'Delete',
  onConfirm,
}: {
  /** What is being deleted, for the accessible name: `Delete "Eggs"`. */
  label: string
  consequence: string
  confirmText?: string
  /** Resolve only if the engine accepted the delete; reject with its sentence. */
  onConfirm: () => Promise<void>
}) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  if (!confirming) {
    return (
      <Button
        variant="ghost"
        size="icon-sm"
        className="text-muted-foreground"
        aria-label={`Delete ${label}`}
        onClick={() => setConfirming(true)}
      >
        <Trash2 />
      </Button>
    )
  }

  async function run() {
    setBusy(true)
    setProblem(null)
    try {
      await onConfirm()
      // The row is gone from the refetched list; this control goes with it.
      setConfirming(false)
    } catch (error) {
      setProblem(error instanceof Error ? error.message : 'Nothing was deleted.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex max-w-xs flex-col items-end gap-1.5 text-right" role="group" aria-label={`Confirm delete ${label}`}>
      <p className="text-[0.8125rem] text-muted-foreground">{consequence}</p>
      <div className="flex gap-2">
        <Button variant="secondary" size="sm" onClick={() => setConfirming(false)} disabled={busy}>
          Keep
        </Button>
        <Button variant="destructive" size="sm" onClick={run} disabled={busy}>
          {busy ? 'Deleting' : confirmText}
        </Button>
      </div>
      {problem && (
        <span role="alert" className="text-[0.8125rem] text-destructive">
          {problem}
        </span>
      )}
    </div>
  )
}
