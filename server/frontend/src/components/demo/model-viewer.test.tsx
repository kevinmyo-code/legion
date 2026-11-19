import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import type { Stage, StageOptions } from '@/components/demo/model-stage'
import { HINT_POINTER, MODEL_FAILED, ModelViewer } from '@/components/demo/model-viewer'

/**
 * The /try 3D model viewer, with the three.js half (`model-stage.ts`) replaced: jsdom has no
 * WebGL. What is checked here is the page's side: the poster first, the 3D asked for only once
 * the viewer nears the screen, the poster kept with one line when it fails, and the controls.
 * A real browser check of the 3D itself is `e2e/try-media-shots.spec.ts`. Invented model.
 */

const mountStage = vi.fn<(host: HTMLElement, url: string, options: StageOptions) => Promise<Stage>>()
vi.mock('@/components/demo/model-stage', () => ({ mountStage: (...args: Parameters<typeof mountStage>) => mountStage(...args) }))

const MODEL = { title: 'Fake gadget', model: '/api/demo/media/gadget.glb', poster: '/api/demo/media/gadget.png', note: 'Fake note' }

type Callback = (entries: { isIntersecting: boolean }[]) => void
let observers: { cb: Callback; options?: IntersectionObserverInit }[] = []

function fakeStage(): Stage {
  return { setActive: vi.fn(), setVisible: vi.fn(), turn: vi.fn(), reset: vi.fn(), dispose: vi.fn() }
}

beforeEach(() => {
  observers = []
  mountStage.mockReset()
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      constructor(cb: Callback, options?: IntersectionObserverInit) {
        observers.push({ cb, options })
      }
      observe() {}
      disconnect() {}
    },
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** The first observer is the "near the screen" one; tell it the viewer is a screen away. */
async function comeNear() {
  await act(async () => observers[0].cb([{ isIntersecting: true }]))
}

describe('ModelViewer', () => {
  test('draws the poster first and loads nothing until the section nears the screen', () => {
    render(<ModelViewer model={MODEL} />)
    expect(screen.getByRole('img', { name: 'Fake gadget' })).toHaveAttribute('src', MODEL.poster)
    expect(screen.getByRole('group', { name: /^3D model: Fake gadget\. .*arrow keys/ })).toBeInTheDocument()
    expect(mountStage).not.toHaveBeenCalled()
    expect(observers[0].options?.rootMargin).toBe('100% 0px')
    expect(screen.queryByRole('button', { name: 'Reset view' })).not.toBeInTheDocument()
    expect(screen.getByText('Fake gadget. Fake note')).toBeInTheDocument()
  })

  test('near the screen it loads the model over the poster, turning by itself', async () => {
    const stage = fakeStage()
    mountStage.mockResolvedValue(stage)
    render(<ModelViewer model={MODEL} />)
    await comeNear()
    await screen.findByRole('button', { name: 'Reset view' })
    expect(mountStage).toHaveBeenCalledTimes(1)
    const [host, url, options] = mountStage.mock.calls[0]
    expect(host).toHaveAttribute('data-state', 'ready')
    expect(url).toBe(MODEL.model)
    expect(options).toEqual({ autoTurn: true })
    // The poster stays under the canvas: the swap never shows a blank box.
    expect(screen.getByRole('img', { name: 'Fake gadget' })).toBeInTheDocument()
    expect(screen.getByText(HINT_POINTER)).toBeInTheDocument()
  })

  test('with reduced motion it never turns by itself', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(prefers-reduced-motion: reduce)',
      addEventListener() {},
      removeEventListener() {},
    }))
    mountStage.mockResolvedValue(fakeStage())
    render(<ModelViewer model={MODEL} />)
    await comeNear()
    await screen.findByRole('button', { name: 'Reset view' })
    expect(mountStage.mock.calls[0][2]).toEqual({ autoTurn: false })
  })

  test('no WebGL or a failed load keeps the poster, with one plain line', async () => {
    mountStage.mockRejectedValue(new Error('WebGL is not available'))
    render(<ModelViewer model={MODEL} />)
    await comeNear()
    expect(await screen.findByText(MODEL_FAILED)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Fake gadget' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reset view' })).not.toBeInTheDocument()
  })

  test('arrow keys turn it, a tap hands it the gestures, Done and Escape hand them back', async () => {
    const stage = fakeStage()
    mountStage.mockResolvedValue(stage)
    render(<ModelViewer model={MODEL} />)
    await comeNear()
    await screen.findByRole('button', { name: 'Reset view' })
    const box = screen.getByRole('group', { name: /^3D model: Fake gadget/ })

    fireEvent.keyDown(box, { key: 'ArrowRight' })
    fireEvent.keyDown(box, { key: 'ArrowUp' })
    expect(stage.turn).toHaveBeenNthCalledWith(1, 1, 0)
    expect(stage.turn).toHaveBeenNthCalledWith(2, 0, -1)

    fireEvent.click(box)
    await waitFor(() => expect(stage.setActive).toHaveBeenLastCalledWith(true))
    expect(box).toHaveAttribute('data-active', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(stage.setActive).toHaveBeenLastCalledWith(false))

    fireEvent.click(box)
    await waitFor(() => expect(box).toHaveAttribute('data-active', 'true'))
    fireEvent.keyDown(box, { key: 'Escape' })
    await waitFor(() => expect(box).toHaveAttribute('data-active', 'false'))

    fireEvent.click(screen.getByRole('button', { name: 'Reset view' }))
    expect(stage.reset).toHaveBeenCalledTimes(1)
  })

  test('scrolling away hands the page back and stops drawing; unmounting frees the stage', async () => {
    const stage = fakeStage()
    mountStage.mockResolvedValue(stage)
    const { unmount } = render(<ModelViewer model={MODEL} />)
    await comeNear()
    await screen.findByRole('button', { name: 'Reset view' })
    const box = screen.getByRole('group', { name: /^3D model: Fake gadget/ })
    fireEvent.click(box)
    await waitFor(() => expect(box).toHaveAttribute('data-active', 'true'))
    const onScreen = observers[observers.length - 1]
    await act(async () => onScreen.cb([{ isIntersecting: false }]))
    expect(stage.setVisible).toHaveBeenLastCalledWith(false)
    expect(box).toHaveAttribute('data-active', 'false')
    unmount()
    expect(stage.dispose).toHaveBeenCalledTimes(1)
  })
})
