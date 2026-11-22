import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'

import { type CallCard, latencyRows, money } from '@/lib/store-rep'

import { CallCardView } from './call-card'

/**
 * The store-rep demo's after-call card (`server/demo/shop/call_card.py` builds it; this only shows
 * it). Invented call, invented shop.
 */

const CARD: CallCard = {
  outcome: 'order_placed',
  outcome_words: 'Order placed',
  summary: ['2 visitor turns.', 'Discussed: Ridgeline 40L Daypack.', 'Placed LP-1234 for $143.00.'],
  transcript: [
    { who: 'visitor', text: 'I need a daypack.', typed: false, at: '11-22 10:00:00.000' },
    { who: 'assistant', text: 'The Ridgeline 40L is $129.00.', typed: false, at: '11-22 10:00:01.000' },
    { who: 'visitor', text: 'Yes, place it.', typed: true, at: '11-22 10:00:09.000' },
  ],
  turns: [
    { kind: 'turn', spoken: true, first_audio_ms: 820, answer_audio_ms: 1400, stt_ms: 300, turn_ms: 2100 },
    { kind: 'turn', spoken: false, first_audio_ms: null, answer_audio_ms: null, stt_ms: null, turn_ms: 900 },
  ],
  median_first_audio_ms: 820,
  record: { record_type: 'call', outcome: 'order_placed', orders: [{ ref: 'LP-1234', total_cents: 14300 }] },
}

describe('money', () => {
  test('always two places, thousands grouped, like code speaks them', () => {
    expect(money(12900)).toBe('$129.00')
    expect(money(5)).toBe('$0.05')
    expect(money(123456789)).toBe('$1,234,567.89')
  })
})

describe('latencyRows', () => {
  test('a typed turn says it was not spoken instead of a number never measured', () => {
    expect(latencyRows(CARD.turns)).toEqual([
      { label: 'Turn 1', via: 'spoken', ms: 820 },
      { label: 'Turn 2', via: 'typed', ms: null },
    ])
  })

  test('the greeting is labelled, and turn numbers skip it', () => {
    const rows = latencyRows([{ ...CARD.turns[0], kind: 'greeting' }, CARD.turns[0]])
    expect(rows.map((r) => r.label)).toEqual(['Greeting', 'Turn 1'])
  })
})

describe('CallCardView', () => {
  test('shows the outcome, the summary and the CRM record first', () => {
    render(<CallCardView card={CARD} />)
    const card = screen.getByTestId('call-card')
    expect(within(card).getByText('Order placed')).toHaveAttribute('data-outcome', 'order_placed')
    expect(within(card).getByText('Placed LP-1234 for $143.00.')).toBeInTheDocument()
    expect(within(card).getByText(/"record_type": "call"/)).toBeInTheDocument()
    expect(within(card).getByText('820 ms')).toBeInTheDocument()
  })

  test('the transcript marks a typed line, and the latency tab never invents a typed turn time', () => {
    render(<CallCardView card={CARD} />)
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Transcript' }))
    fireEvent.click(screen.getByRole('tab', { name: 'Transcript' }))
    expect(screen.getByText('Caller (typed)')).toBeInTheDocument()
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Latency' }))
    fireEvent.click(screen.getByRole('tab', { name: 'Latency' }))
    expect(screen.getByText('not spoken')).toBeInTheDocument()
  })
})
