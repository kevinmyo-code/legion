import { render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'

import { DIAGRAM_CARD } from '@/test/cards-seed'

import { DiagramBody } from './diagram'

/**
 * Live demo, 2026-11-18 (Kevin): "the doc diagram also flickers when it opens". The same diagram
 * was shown twice 33 s apart (the tour, then his question). Opening is one paint: the picture is in
 * place before the browser paints the dialog; and showing the diagram already on screen again
 * changes nothing in the page.
 */

// jsdom lays nothing out: give the modal's box a size, as the browser does, so the picture fits.
const realWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth')
const realHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight')
beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 800 })
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => 600 })
})
afterEach(() => {
  if (realWidth) Object.defineProperty(HTMLElement.prototype, 'clientWidth', realWidth)
  if (realHeight) Object.defineProperty(HTMLElement.prototype, 'clientHeight', realHeight)
})

describe('the diagram in the modal', () => {
  test('showing the same diagram again keeps the very same picture (no redraw)', () => {
    const { rerender, container } = render(<DiagramBody card={DIAGRAM_CARD} />)
    const first = container.querySelector('[data-diagram="full"] svg')
    expect(first).not.toBeNull()
    // What a second `show_diagram` of the same view hands the modal: equal content, new objects.
    rerender(<DiagramBody card={{ ...DIAGRAM_CARD, highlight: [...DIAGRAM_CARD.highlight] }} />)
    expect(container.querySelector('[data-diagram="full"] svg')).toBe(first)
  })
})
