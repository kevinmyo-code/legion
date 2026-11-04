import { useEffect, useRef } from 'react'

import type { AudioLevels } from '@/assistant/audio'

import { OrbRenderer, parseColour, type OrbColours, type OrbMode } from './orb'

/**
 * The orb on a canvas. Colours come from CSS, so a page restyles it without
 * touching this file: the dots are the element's `color`, the sweep and ripple
 * crests are `--orb-accent` (falling back to the dots' colour).
 *
 * The loop runs only while the page is visible, and freezes (still easing
 * between modes) under `prefers-reduced-motion`. A browser with no 2D canvas
 * (jsdom) draws nothing; the words beside the orb carry the state anyway.
 */
export function VoiceOrb({
  mode,
  levels,
  className,
}: {
  mode: OrbMode
  /** Read once a frame: the mic while listening, the reply while speaking. */
  levels: () => AudioLevels
  className?: string
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const rendererRef = useRef<OrbRenderer | null>(null)
  const modeRef = useRef(mode)
  const levelsRef = useRef(levels)

  useEffect(() => {
    modeRef.current = mode
    rendererRef.current?.setMode(mode)
  }, [mode])
  useEffect(() => {
    levelsRef.current = levels
  }, [levels])

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return
    const renderer = (rendererRef.current ??= new OrbRenderer())
    renderer.setMode(modeRef.current)

    const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    let still = motion?.matches ?? false
    const onMotion = (event: MediaQueryListEvent) => {
      still = event.matches
    }
    motion?.addEventListener?.('change', onMotion)

    let colours = readColours(canvas)
    let coloursAt = 0
    let frame = 0
    let last = performance.now()
    const tick = (now: number) => {
      frame = requestAnimationFrame(tick)
      if (document.hidden) {
        last = now
        return
      }
      const dt = (now - last) / 1000
      last = now
      // Re-read the colours now and then: a theme switch changes them under us.
      if (now - coloursAt > 1000) {
        colours = readColours(canvas)
        coloursAt = now
      }
      const ratio = window.devicePixelRatio || 1
      const size = Math.max(1, Math.round(canvas.clientWidth * ratio))
      if (canvas.width !== size || canvas.height !== size) {
        canvas.width = size
        canvas.height = size
      }
      const m = modeRef.current
      const level = levelsRef.current()
      const sound = m === 'speaking' ? level.output : m === 'listening' ? level.input : 0
      renderer.step(dt, sound, still)
      renderer.draw(ctx, size, colours, ratio)
    }
    frame = requestAnimationFrame(tick)
    return () => {
      cancelAnimationFrame(frame)
      motion?.removeEventListener?.('change', onMotion)
    }
  }, [])

  return <canvas ref={canvasRef} aria-hidden="true" data-chat="orb-canvas" className={className} />
}

function readColours(element: HTMLElement): OrbColours {
  const style = getComputedStyle(element)
  const ink = parseColour(style.color) ?? [29, 26, 22]
  const accent = parseColour(style.getPropertyValue('--orb-accent')) ?? ink
  return { ink, accent }
}
