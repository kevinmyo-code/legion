import { render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, test, vi } from 'vitest'

import type { Line } from '@/assistant/live-client'
import type { Stage, StageOptions } from '@/components/demo/model-stage'

import { ToolCard } from './card'
import { NOT_VERIFIED } from './part3d'
import { ScreenModalProvider } from './presenter'
import { asCard, type Card, type Part3dCard } from './types'

/**
 * The garage card (`show_part`): checked figures only, with their source; "Not verified yet" and
 * no number otherwise; it opens over the page and flies the model to the part. The three.js half
 * is replaced (jsdom has no WebGL). Every figure here is made up.
 */

const mountStage = vi.fn<(host: HTMLElement, url: string, options: StageOptions) => Promise<Stage>>()
vi.mock('@/components/demo/model-stage', () => ({ mountStage: (...args: Parameters<typeof mountStage>) => mountStage(...args) }))

function fakeStage(): Stage {
  return { setActive: vi.fn(), setVisible: vi.fn(), turn: vi.fn(), reset: vi.fn(), focus: vi.fn(), highlight: vi.fn(), dispose: vi.fn() }
}

const CARD: Part3dCard = {
  card: 'part3d',
  title: 'Track bar, frame end',
  scope: 'XJ front end',
  model: '/garage/xj-front-s0.glb',
  part_id: 'xj.susp.track_bar',
  fastener_id: 'xj.susp.track_bar@frame_end',
  highlight: ['xj.susp.track_bar', 'xj.susp.track_bar@frame_end'],
  view: { target: [0.3, 0.6, 0.1], from: [0.9, 0.9, 0.7] },
  where: 'The top end of the track bar, at the bracket on the driver frame rail.',
  facts: [
    {
      fastener_id: 'xj.susp.track_bar@frame_end',
      name: 'frame end',
      checked: true,
      figure: '12 ft-lb (converted: about 16 N·m)',
      socket: '10 mm',
      thread: '',
      applies_to: '2000, engine any, axle any, the stock part; your truck: 2000, 9.9, Example axle, 0 in lift',
      source: 'Example book',
      page: 'Page 1',
      verified_on: '2000-01-02',
    },
    { fastener_id: 'xj.susp.track_bar@axle_end', name: 'axle end', checked: false },
    {
      fastener_id: 'xj.susp.track_bar@frame_bracket_nut',
      name: 'frame bracket nut',
      checked: false,
      held: 'The checked figure on file is for a different year, engine or axle than your truck, so I won't give it.',
    },
  ],
}

beforeEach(() => {
  mountStage.mockReset()
  window.history.replaceState({}, '')
})

describe('the card', () => {
  test('asCard takes a well-formed card', () => {
    expect(asCard(CARD)).toBe(CARD)
  })

  test.each([
    ['a model off this origin', { model: 'https://example.com/x.glb' }],
    ['a model outside /garage', { model: '/api/demo/media/x.glb' }],
    ['a view that is not a point', { view: { target: [0, 0], from: [1, 1, 1] } }],
    ['a figure on an unchecked row', { facts: [{ fastener_id: 'a', name: 'a', checked: false, figure: '9 ft-lb' }] }],
    ['a number in a held line', { facts: [{ fastener_id: 'a', name: 'a', checked: false, held: 'It is 9 ft-lb' }] }],
  ])('asCard refuses %s', (_why, change) => {
    expect(asCard({ ...CARD, ...change })).toBeUndefined()
  })

  test('the asked fastener comes first and apart, its conversion labelled; the rest fold away', () => {
    render(<ToolCard card={CARD} />)
    const asked = document.querySelector('[data-asked="true"]') as HTMLElement
    expect(asked).toHaveAttribute('data-fastener', 'xj.susp.track_bar@frame_end')
    expect(asked).toHaveAttribute('aria-current', 'true')
    expect(asked).toHaveTextContent('Torque: 12 ft-lb (converted: about 16 N·m)')
    expect(asked).toHaveTextContent('Example book, Page 1. Checked 2000-01-02.')
    const others = document.querySelector('[data-card="part-others"]') as HTMLDetailsElement
    expect(others.open).toBe(false)
    expect(others.querySelector('summary')).toHaveTextContent('Other fasteners on this part (2)')
    expect(others.contains(asked)).toBe(false)
    const unchecked = others.querySelector('[data-fastener="xj.susp.track_bar@axle_end"]') as HTMLElement
    expect(unchecked).toHaveTextContent(`Torque: ${NOT_VERIFIED}`)
    expect(unchecked.textContent).not.toMatch(/\d/)
    const held = others.querySelector('[data-fastener="xj.susp.track_bar@frame_bracket_nut"]') as HTMLElement
    expect(held).toHaveTextContent('different year, engine or axle')
    expect(held.textContent).not.toMatch(/\d/)
  })

  test('a part card with no fastener asked shows them all, open', () => {
    render(<ToolCard card={{ ...CARD, fastener_id: null, highlight: ['xj.susp.track_bar'] }} />)
    expect(document.querySelector('[data-asked="true"]')).toBeNull()
    const all = document.querySelector('[data-card="part-others"]') as HTMLDetailsElement
    expect(all.open).toBe(true)
    expect(all.querySelectorAll('[data-fastener]').length).toBe(3)
  })
})

function tool(id: number, card: Card): Line {
  return { id, kind: 'tool', ok: true, text: '', card }
}

function Harness({ lines }: { lines: Line[] }) {
  return (
    <ScreenModalProvider lines={lines}>
      {lines.map((line) => (line.kind === 'tool' && line.card ? <ToolCard key={line.id} card={line.card} /> : null))}
    </ScreenModalProvider>
  )
}

describe('the 3D view over the page', () => {
  test('opens by itself, loads the model once, lights the part and flies to it', async () => {
    const stage = fakeStage()
    mountStage.mockResolvedValue(stage)
    const { rerender } = render(<Harness lines={[]} />)
    rerender(<Harness lines={[tool(1, CARD)]} />)
    const dialog = screen.getByRole('dialog', { name: CARD.title })
    expect(within(dialog).getByText(CARD.where)).toBeInTheDocument()
    await waitFor(() => expect(stage.focus).toHaveBeenCalledTimes(1))
    expect(mountStage).toHaveBeenCalledTimes(1)
    expect(mountStage.mock.calls[0][1]).toBe('/garage/xj-front-s0.glb')
    expect(mountStage.mock.calls[0][2]).toMatchObject({ autoTurn: false, explore: true })
    expect(stage.setActive).toHaveBeenCalledWith(true)
    expect(stage.highlight).toHaveBeenLastCalledWith(CARD.highlight)
    expect(stage.focus).toHaveBeenLastCalledWith(CARD.view, 600)

    // Asked about another nut: the same model, flown on, not loaded again.
    const next: Part3dCard = {
      ...CARD,
      title: 'Track bar, axle end',
      fastener_id: 'xj.susp.track_bar@axle_end',
      highlight: ['xj.susp.track_bar', 'xj.susp.track_bar@axle_end'],
      view: { target: [-0.3, 0.5, 0.1], from: [-0.9, 0.8, 0.7] },
    }
    rerender(<Harness lines={[tool(1, CARD), tool(2, next)]} />)
    await waitFor(() => expect(stage.focus).toHaveBeenLastCalledWith(next.view, 600))
    expect(stage.highlight).toHaveBeenLastCalledWith(next.highlight)
    expect(mountStage).toHaveBeenCalledTimes(1)
  })

  test('a model that does not load leaves the words', async () => {
    mountStage.mockRejectedValue(new Error('no WebGL'))
    const { rerender } = render(<Harness lines={[]} />)
    rerender(<Harness lines={[tool(1, CARD)]} />)
    const dialog = screen.getByRole('dialog', { name: CARD.title })
    await waitFor(() => expect(within(dialog).getByText(/could not load/)).toBeInTheDocument())
    expect(within(dialog).getAllByText(/Torque:/).length).toBe(3)
  })
})
