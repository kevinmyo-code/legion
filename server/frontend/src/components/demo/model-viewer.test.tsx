import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

import type { Stage, StageOptions } from '@/components/demo/model-stage'
import { FAR_MARGIN, HINT_POINTER, MODEL_FAILED, ModelViewer, NEAR_MARGIN } from '@/components/demo/model-viewer'

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
  return { setActive: vi.fn(), setVisible: vi.fn(), turn: vi.fn(), reset: vi.fn(), focus: vi.fn(), highlight: vi.fn(), dispose: vi.fn() }
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
    // Posters load only as the page nears them, not all at page open.
    expect(screen.getByRole('img', { name: 'Fake gadget' })).toHaveAttribute('loading', 'lazy')
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
    expect(options).toMatchObject({ autoTurn: true })
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
    expect(mountStage.mock.calls[0][2]).toMatchObject({ autoTurn: false, scrollTurn: false })
  })

  test('with motion allowed (the /try scroll story) it turns with the page until touched', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(prefers-reduced-motion: no-preference)',
      addEventListener() {},
      removeEventListener() {},
    }))
    mountStage.mockResolvedValue(fakeStage())
    render(<ModelViewer model={MODEL} />)
    await comeNear()
    await screen.findByRole('button', { name: 'Reset view' })
    expect(mountStage.mock.calls[0][2]).toMatchObject({ autoTurn: true, scrollTurn: true })
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

  test('two models: touching the second hands the first one back, though the second stops the event', async () => {
    const stages = new Map<string, Stage>()
    mountStage.mockImplementation(async (host, url) => {
      // The real stage stops an inactive model's touch at its box, in the capture phase.
      host.addEventListener('pointerdown', (event) => event.stopPropagation(), { capture: true })
      const stage = fakeStage()
      stages.set(url, stage)
      return stage
    })
    const other = { ...MODEL, title: 'Fake widget', model: '/api/demo/media/widget.glb' }
    render(
      <>
        <ModelViewer model={MODEL} />
        <ModelViewer model={other} />
      </>,
    )
    const nearObservers = observers.slice(0, 2)
    for (const observer of nearObservers) await act(async () => observer.cb([{ isIntersecting: true }]))
    const first = screen.getByRole('group', { name: /^3D model: Fake gadget/ })
    const second = screen.getByRole('group', { name: /^3D model: Fake widget/ })
    await waitFor(() => expect(first).toHaveAttribute('data-state', 'ready'))
    await waitFor(() => expect(second).toHaveAttribute('data-state', 'ready'))
    fireEvent.click(first)
    await waitFor(() => expect(first).toHaveAttribute('data-active', 'true'))
    fireEvent.pointerDown(second, { pointerType: 'touch' })
    await waitFor(() => expect(first).toHaveAttribute('data-active', 'false'))
    expect(stages.get(MODEL.model)?.setActive).toHaveBeenLastCalledWith(false)
  })

  test('more than three screens away the 3D is freed (poster again), and it loads again when near', async () => {
    const stage = fakeStage()
    mountStage.mockResolvedValue(stage)
    render(<ModelViewer model={MODEL} />)
    await comeNear()
    const box = screen.getByRole('group', { name: /^3D model: Fake gadget/ })
    await waitFor(() => expect(box).toHaveAttribute('data-state', 'ready'))
    const far = observers.find((o) => o.options?.rootMargin === FAR_MARGIN)!
    await act(async () => far.cb([{ isIntersecting: false }]))
    expect(stage.dispose).toHaveBeenCalledTimes(1)
    expect(box).toHaveAttribute('data-state', 'poster')
    const near = observers.filter((o) => o.options?.rootMargin === NEAR_MARGIN).pop()!
    await act(async () => near.cb([{ isIntersecting: true }]))
    await waitFor(() => expect(mountStage).toHaveBeenCalledTimes(2))
  })

  test('a lost WebGL context frees the stage and keeps the poster with its line', async () => {
    const stage = fakeStage()
    mountStage.mockResolvedValue(stage)
    render(<ModelViewer model={MODEL} />)
    await comeNear()
    await screen.findByRole('button', { name: 'Reset view' })
    await act(async () => mountStage.mock.calls[0][2].onLost?.())
    expect(stage.dispose).toHaveBeenCalledTimes(1)
    expect(screen.getByText(MODEL_FAILED)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Fake gadget' })).toBeInTheDocument()
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
