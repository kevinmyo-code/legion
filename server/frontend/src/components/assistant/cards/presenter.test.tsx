import { act, fireEvent, render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, test } from 'vitest'

import type { Line } from '@/assistant/live-client'
import { DIAGRAM_CARD } from '@/test/cards-seed'

import { ToolCard } from './card'
import { svgSize } from './diagram'
import { fitScale, turnHint } from './fit-view'
import { ScreenModalProvider } from './presenter'
import { asCard, type Card, type ResumeCard } from './types'

/**
 * demo-modals (Kevin, 2026-11-05): a diagram and the resume open over the whole page, fitted
 * to it, by themselves when the card arrives; a newer card replaces what the open modal shows;
 * the chat keeps a thumbnail that opens it again; X, Esc and the back gesture close it.
 */

const RESUME: ResumeCard = {
  card: 'resume',
  title: "Kevin's resume",
  image: '/api/demo/asset/resume_image',
  pdf: '/api/demo/asset/resume',
  alt: "Kevin's one-page resume, as an image of the page.",
}

function tool(id: number, card: Card | undefined): Line {
  return { id, kind: 'tool', ok: true, text: '', card }
}

function Harness({ lines, footer }: { lines: Line[]; footer?: ReactNode }) {
  return (
    <ScreenModalProvider lines={lines} footer={footer}>
      {lines.map((line) => (line.kind === 'tool' && line.card ? <ToolCard key={line.id} card={line.card} /> : null))}
    </ScreenModalProvider>
  )
}

const modal = () => screen.queryByRole('dialog')

afterEach(() => {
  // Each test's pushed entry is popped by its own close; start the next one clean.
  window.history.replaceState({}, '')
})

describe('the page modal', () => {
  test('a diagram card opens it by itself, with the title, the caption and the highlights', () => {
    const { rerender } = render(<Harness lines={[]} />)
    expect(modal()).toBeNull()
    rerender(<Harness lines={[tool(1, DIAGRAM_CARD)]} />)
    const dialog = screen.getByRole('dialog', { name: 'Engine components, in layers' })
    expect(within(dialog).getByText(DIAGRAM_CARD.caption)).toBeInTheDocument()
    expect(dialog.querySelector('[data-modal="highlighted"]')).toHaveTextContent('Highlighted: /api/assistant/tool, household.tenancy')
    expect(within(dialog).getByRole('button', { name: 'Fit to screen' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Close' })).toBeInTheDocument()
  })

  test('cards already in the conversation do not pop up when it mounts', () => {
    render(<Harness lines={[tool(1, DIAGRAM_CARD)]} />)
    expect(modal()).toBeNull()
  })

  test('a newer diagram updates the open modal in place', () => {
    const { rerender } = render(<Harness lines={[tool(1, DIAGRAM_CARD)]} />)
    rerender(<Harness lines={[tool(1, DIAGRAM_CARD), tool(2, { ...DIAGRAM_CARD, highlight: ['rest'] })]} />)
    const first = screen.getByRole('dialog')
    expect(first.querySelector('[data-modal="highlighted"]')).toHaveTextContent('Highlighted: REST /api/...')
    rerender(
      <Harness
        lines={[tool(1, DIAGRAM_CARD), tool(2, undefined), tool(3, { ...DIAGRAM_CARD, highlight: ['household'], title: 'Another view' })]}
      />,
    )
    const dialogs = screen.getAllByRole('dialog')
    expect(dialogs).toHaveLength(1)
    expect(dialogs[0]).toBe(first)
    expect(within(first).getByRole('heading', { name: 'Another view' })).toBeInTheDocument()
    expect(first.querySelector('[data-modal="highlighted"]')).toHaveTextContent('Highlighted: household.tenancy')
  })

  test('the X closes it and the thumbnail in the chat opens it again', () => {
    const { rerender } = render(<Harness lines={[]} />)
    rerender(<Harness lines={[tool(1, DIAGRAM_CARD)]} />)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }))
    expect(modal()).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Open the diagram over the page/ }))
    expect(screen.getByRole('dialog', { name: 'Engine components, in layers' })).toBeInTheDocument()
  })

  test('Esc closes it', () => {
    const { rerender } = render(<Harness lines={[]} />)
    rerender(<Harness lines={[tool(1, DIAGRAM_CARD)]} />)
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(modal()).toBeNull()
  })

  test('the back gesture closes it, and opening it pushed exactly one entry', async () => {
    const before = window.history.length
    const { rerender } = render(<Harness lines={[]} />)
    rerender(<Harness lines={[tool(1, DIAGRAM_CARD)]} />)
    // The push waits a tick (StrictMode runs the effect twice).
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(window.history.length).toBe(before + 1)
    expect((window.history.state as { legionModal?: boolean }).legionModal).toBe(true)
    act(() => {
      window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
    })
    expect(modal()).toBeNull()
  })

  test('the footer (the voice controls) rides inside the modal', () => {
    const { rerender } = render(<Harness lines={[]} footer={<button type="button">Stop</button>} />)
    rerender(<Harness lines={[tool(1, DIAGRAM_CARD)]} footer={<button type="button">Stop</button>} />)
    expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Stop' })).toBeInTheDocument()
  })

  test('the highlighted parts are outlined in the modal copy too', () => {
    const { rerender } = render(<Harness lines={[]} />)
    rerender(<Harness lines={[tool(1, DIAGRAM_CARD)]} />)
    // jsdom has no layout, so the fitted copy is not drawn; the style that outlines it is there.
    const style = screen.getByRole('dialog').querySelector('style')?.textContent ?? ''
    expect(style).toContain('non-scaling-stroke')
  })
})

describe('the resume', () => {
  test('a resume card opens the modal with a Download PDF link to the engine file', () => {
    const { rerender } = render(<Harness lines={[]} />)
    rerender(<Harness lines={[tool(1, RESUME)]} />)
    const dialog = screen.getByRole('dialog', { name: "Kevin's resume" })
    const download = within(dialog).getByRole('link', { name: 'Download PDF' })
    expect(download).toHaveAttribute('href', '/api/demo/asset/resume')
    expect(download).toHaveAttribute('download', 'Kevin-Win-Resume.pdf')
    expect(download).not.toHaveAttribute('target')
  })

  test('with no page image it says so and still offers the PDF', () => {
    const { rerender } = render(<Harness lines={[]} />)
    rerender(<Harness lines={[tool(1, { ...RESUME, image: null })]} />)
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/A picture of the page is not loaded here/)).toBeInTheDocument()
    expect(within(dialog).getAllByRole('link', { name: 'Download PDF' }).length).toBeGreaterThan(0)
  })

  test('the chat line opens it again', () => {
    render(<Harness lines={[tool(1, RESUME)]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Open the resume' }))
    expect(screen.getByRole('dialog', { name: "Kevin's resume" })).toBeInTheDocument()
  })

  test('asCard takes only links to the engine asset door', () => {
    expect(asCard(RESUME)).toEqual(RESUME)
    expect(asCard({ ...RESUME, image: null })).toBeDefined()
    expect(asCard({ ...RESUME, pdf: 'https://evil.example/r.pdf' })).toBeUndefined()
    expect(asCard({ ...RESUME, image: 'javascript:alert(1)' })).toBeUndefined()
  })
})

describe('fitting', () => {
  test('contain fits both sides and keeps the ratio; width fits the width only', () => {
    expect(fitScale({ width: 390, height: 600 }, { width: 1300, height: 1000 }, 'contain')).toBeCloseTo(0.3)
    expect(fitScale({ width: 1200, height: 600 }, { width: 1300, height: 1000 }, 'contain')).toBeCloseTo(0.6)
    expect(fitScale({ width: 390, height: 600 }, { width: 1836, height: 2376 }, 'width')).toBeCloseTo(390 / 1836)
    // A page of text on a desk stays a page wide, not the whole screen.
    expect(fitScale({ width: 1390, height: 700 }, { width: 1836, height: 2376 }, 'width', 880)).toBeCloseTo(880 / 1836)
  })

  test('a wide diagram on an upright phone says how to see it bigger', () => {
    expect(turnHint({ width: 360, height: 560 }, { width: 2504, height: 1181 })).toBe(true)
    expect(turnHint({ width: 1350, height: 600 }, { width: 2504, height: 1181 })).toBe(false)
    expect(turnHint({ width: 360, height: 560 }, { width: 1004, height: 1653 })).toBe(false)
  })

  test("a diagram's own size comes from its viewBox", () => {
    expect(svgSize('<svg width="100%" viewBox="4 4 1335.59 1055.47"></svg>')).toEqual({ width: 1335.59, height: 1055.47 })
    expect(svgSize('<svg></svg>')).toBeNull()
  })
})
