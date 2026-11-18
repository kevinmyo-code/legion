import { mergeCar, type Card } from '@/components/assistant/cards/types'

import type { Line } from './live-client'

/**
 * As `LiveClient.mergedCard`: one car card per car, one diagram card per view. Returns the card to
 * show and the earlier line whose card it replaces (that line's card is cleared), if any.
 */
export function mergedCard(lines: readonly Line[], card: Card | undefined): { card: Card | undefined; replaces: number | null } {
  if (!card) return { card: undefined, replaces: null }
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i]
    if (line.kind !== 'tool' || !line.card) continue
    if (card.card === 'car' && line.card.card === 'car' && line.card.name === card.name) {
      return { card: mergeCar(line.card, card), replaces: line.id }
    }
    if (
      card.card === 'diagram' &&
      line.card.card === 'diagram' &&
      line.card.project === card.project &&
      line.card.view === card.view
    ) {
      return { card, replaces: line.id }
    }
    return { card, replaces: null }
  }
  return { card, replaces: null }
}
