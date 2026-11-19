import { useEffect, useRef, useState, type KeyboardEvent } from 'react'

import type { DemoModel3D } from '@/api/demo'
import type { Stage } from '@/components/demo/model-stage'

/**
 * A 3D model on /try a visitor can turn (Kevin, 2026-11-19). The poster is drawn first and is all
 * a visitor gets until the section is within a screen of view; only then is the 3D chunk
 * (three.js, `model-stage.ts`) fetched and the model loaded, over the poster. No WebGL, or a
 * model that does not load: the poster stays, with one plain line under it.
 *
 * **The scroll rule.** A page must never be trapped under a model on a phone, so until the
 * visitor taps (or clicks) the model, a one-finger drag and the wheel scroll the page; a mouse
 * drag turns it at once, since a mouse drag never scrolls. Once tapped, every gesture over it is
 * the model's (drag turns, pinch or wheel zooms) until "Done", Escape, a tap elsewhere, the
 * mouse leaving it, or it scrolling out of view.
 *
 * Keyboard: the model takes focus, arrow keys turn it, and "Reset view" is a button. It turns
 * slowly by itself until first touched, never under `prefers-reduced-motion`.
 */

export const MODEL_FAILED = 'The 3D view could not load here, so this is a still picture of the model.'
export const HINT_TOUCH = 'Tap the model, then drag to turn it and pinch to zoom.'
export const HINT_POINTER = 'Drag to turn it. Click it, then scroll to zoom.'

type State = 'poster' | 'loading' | 'ready' | 'failed'

/** Load when within a screen of view; unload when more than three screens away. */
export const NEAR_MARGIN = '100% 0px'
export const FAR_MARGIN = '300% 0px'

const ARROWS: Record<string, [number, number]> = {
  ArrowLeft: [-1, 0],
  ArrowRight: [1, 0],
  ArrowUp: [0, -1],
  ArrowDown: [0, 1],
}

function matches(query: string): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(query).matches
}

export function ModelViewer({ model }: { model: DemoModel3D }) {
  const box = useRef<HTMLDivElement>(null)
  const stage = useRef<Stage | null>(null)
  const [state, setState] = useState<State>('poster')
  const [active, setActive] = useState(false)
  const [near, setNear] = useState(false)

  // Within a screen of view: load. More than three screens away: unload (the GPU memory and the
  // WebGL context go back; browsers cap live contexts), back to the poster until it nears again.
  useEffect(() => {
    const element = box.current
    if (!element || typeof IntersectionObserver === 'undefined') return
    const observer = near
      ? new IntersectionObserver((entries) => entries.every((e) => !e.isIntersecting) && setNear(false), {
          rootMargin: FAR_MARGIN,
        })
      : new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && setNear(true), {
          rootMargin: NEAR_MARGIN,
        })
    observer.observe(element)
    return () => observer.disconnect()
  }, [near])

  useEffect(() => {
    const element = box.current
    if (!near || !element) return
    let cancelled = false
    setState('loading')
    const onLost = () => {
      if (cancelled) return
      stage.current?.dispose()
      stage.current = null
      setActive(false)
      setState('failed')
    }
    import('@/components/demo/model-stage')
      .then(({ mountStage }) =>
        mountStage(element, model.model, { autoTurn: !matches('(prefers-reduced-motion: reduce)'), onLost }),
      )
      .then((mounted) => {
        if (cancelled) {
          mounted.dispose()
          return
        }
        stage.current = mounted
        setState('ready')
      })
      .catch(() => {
        if (!cancelled) setState('failed')
      })
    return () => {
      cancelled = true
      stage.current?.dispose()
      stage.current = null
      setActive(false)
      setState((was) => (was === 'failed' ? was : 'poster'))
    }
  }, [near, model.model])

  // On screen or not: draw only while seen, and hand the page back when it scrolls away.
  useEffect(() => {
    const element = box.current
    if (state !== 'ready' || !element || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      const seen = entries.some((entry) => entry.isIntersecting)
      stage.current?.setVisible(seen)
      if (!seen) setActive(false)
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [state])

  useEffect(() => {
    stage.current?.setActive(active)
    if (!active) return
    const outside = (event: PointerEvent) => {
      if (!box.current?.parentElement?.contains(event.target as Node)) setActive(false)
    }
    // Capture phase: another model's box stops its touches before they bubble (`model-stage.ts`),
    // and a touch on it must still hand this one back.
    document.addEventListener('pointerdown', outside, { capture: true })
    return () => document.removeEventListener('pointerdown', outside, { capture: true })
  }, [active])

  const ready = state === 'ready'

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (!ready) return
    if (event.key === 'Escape') {
      setActive(false)
      return
    }
    const step = ARROWS[event.key]
    if (!step) return
    event.preventDefault()
    stage.current?.turn(step[0], step[1])
  }

  const label = `3D model: ${model.title}. Drag or use the arrow keys to turn it.`
  return (
    <figure className="rc-figure rc-model">
      <div
        ref={box}
        className="rc-model-box"
        data-state={state}
        data-active={active ? 'true' : 'false'}
        role="group"
        aria-label={label}
        tabIndex={0}
        onKeyDown={onKeyDown}
        onClick={() => ready && setActive(true)}
        onPointerLeave={(event) => event.pointerType === 'mouse' && setActive(false)}
      >
        <img className="rc-model-poster" src={model.poster} alt={model.title} loading="lazy" decoding="async" />
      </div>
      {ready && (
        <div className="rc-model-bar">
          <button type="button" className="rc-link-button" onClick={() => stage.current?.reset()}>
            Reset view
          </button>
          {active ? (
            <button type="button" className="rc-link-button" onClick={() => setActive(false)}>
              Done
            </button>
          ) : (
            <span className="rc-model-hint">{matches('(pointer: coarse)') ? HINT_TOUCH : HINT_POINTER}</span>
          )}
        </div>
      )}
      {state === 'failed' && <p className="rc-media-note">{MODEL_FAILED}</p>}
      <figcaption className="rc-figcaption">
        {model.title}
        {model.note ? `. ${model.note}` : ''}
      </figcaption>
    </figure>
  )
}
