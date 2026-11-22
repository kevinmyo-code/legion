import { useEffect, useRef } from 'react'

import type { AudioLevels } from '@/assistant/audio'

import { markTargets, OrbRenderer, parseColour, type OrbColours, type OrbMode } from './orb'

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
  points,
  dot,
  mark = null,
}: {
  mode: OrbMode
  /** Read once a frame: the mic while listening, the reply while speaking. */
  levels: () => AudioLevels
  className?: string
  /** How many points (default 1500) and how big each is (default 1): a small orb wants fewer and smaller. */
  points?: number
  dot?: number
  /** A mark the points gather into ('q': the finance voice demo's Q, Kevin 2026-11-22), or none. */
  mark?: 'q' | null
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
  const markRef = useRef(mark)
  useEffect(() => {
    markRef.current = mark
    const renderer = rendererRef.current
    if (renderer) renderer.setMark(mark ? glyphTargets(mark, renderer.size()) : null)
  }, [mark])

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return
    const renderer = (rendererRef.current ??= new OrbRenderer(points, dot))
    renderer.setMode(modeRef.current)
    if (markRef.current) renderer.setMark(glyphTargets(markRef.current, renderer.size()))

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
      // Re-read the colours now and then: a theme switch (or the page blending to a section's
      // palette) changes them under us.
      if (now - coloursAt > 250) {
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
    // Points and dot size are fixed for the orb's life: a new renderer would restart the motion.
  }, [])

  return <canvas ref={canvasRef} aria-hidden="true" data-chat="orb-canvas" className={className} />
}

function readColours(element: HTMLElement): OrbColours {
  const style = getComputedStyle(element)
  const ink = parseColour(style.color) ?? [29, 26, 22]
  const accent = parseColour(style.getPropertyValue('--orb-accent')) ?? ink
  return { ink, accent }
}

const GLYPH_PX = 160
const glyphCache = new Map<string, Float32Array>()

/**
 * The mark's points: the letter drawn once off screen in the heaviest system sans there is, as the
 * finance voice demo draws its Q (a letter in a system font, no logo file), sampled to one target
 * per point. No 2D canvas (jsdom): a ring.
 */
function glyphTargets(mark: 'q', count: number): Float32Array {
  const key = `${mark}:${count}`
  const cached = glyphCache.get(key)
  if (cached) return cached
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = GLYPH_PX
  const ctx = canvas.getContext('2d')
  const alpha = new Uint8ClampedArray(GLYPH_PX * GLYPH_PX)
  if (ctx) {
    ctx.fillStyle = '#000'
    ctx.font = `900 ${Math.round(GLYPH_PX * 0.9)}px "Arial Black", "Segoe UI Black", Impact, "Helvetica Neue", Arial, sans-serif`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(mark.toUpperCase(), GLYPH_PX / 2, GLYPH_PX / 2 + 3)
    const rgba = ctx.getImageData(0, 0, GLYPH_PX, GLYPH_PX).data
    for (let i = 0; i < alpha.length; i++) alpha[i] = rgba[i * 4 + 3]
  }
  const targets = markTargets(alpha, GLYPH_PX, count)
  glyphCache.set(key, targets)
  return targets
}
