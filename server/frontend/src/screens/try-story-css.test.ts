import { describe, expect, test } from 'vitest'

import indexCss from '../index.css?raw'

/**
 * The scroll story's stylesheet rules that jsdom cannot lay out, read as text (review 2026-11-20):
 * contrast of the scene's parts, print, and the pin at short heights. The geometry itself is
 * measured in the browser check (`e2e/try-scroll-story-shots.spec.ts`).
 */

/** Every rule as [selector, body, the at-rule it sits in (or '')]. */
function rules(css: string): { selector: string; body: string; within: string }[] {
  const out: { selector: string; body: string; within: string }[] = []
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '')
  let depth = 0
  let start = 0
  const stack: string[] = []
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (c === '{') {
      stack.push(text.slice(start, i).trim())
      depth++
      start = i + 1
    } else if (c === '}') {
      const head = stack.pop() ?? ''
      depth--
      if (!head.startsWith('@')) {
        const within = stack.filter((s) => s.startsWith('@')).join(' ')
        out.push({ selector: head, body: text.slice(start, i), within })
      }
      start = i + 1
    } else if (c === ';' && depth > 0 && stack.length && stack[stack.length - 1].startsWith('@')) {
      // A declaration directly inside an at-rule (none here): skip it.
    }
  }
  return out
}

const all = rules(indexCss)

describe('the LEGION scene', () => {
  test('parts out of focus keep their contrast: no fade, the part in focus turns ink', () => {
    const steps = all.filter((r) => r.selector.includes('rc-scene-steps'))
    expect(steps.length).toBeGreaterThan(0)
    expect(steps.filter((r) => /(^|[\s;])opacity\s*:/.test(r.body)).map((r) => r.selector)).toEqual([])
    const lit = steps.find((r) => r.selector.includes('[data-on="true"]'))
    expect(lit?.body).toMatch(/color\s*:\s*var\(--rc-ink\)/)
  })

  test('the diagram is pinned only where the screen is tall enough to read the parts under it', () => {
    const unpinned = all.filter((r) => r.selector.includes('.rc-scene-pin') && /max-height:\s*699px/.test(r.within))
    expect(unpinned.some((r) => /position\s*:\s*static/.test(r.body))).toBe(true)
  })

  test('never pinned under 900 px wide (a phone in portrait has no room under it)', () => {
    const unpinned = all.filter((r) => r.selector.includes('.rc-scene-pin') && /max-width:\s*899px/.test(r.within))
    expect(unpinned.some((r) => /position\s*:\s*static/.test(r.body))).toBe(true)
  })
})

describe('the side orb', () => {
  test('takes clicks only on the orb and the chips, never on its status line', () => {
    const words = all.find((r) => r.selector.trim() === '.recruiter .rc-side-words' && !r.within.includes('@media'))
    expect(words?.body).toMatch(/pointer-events\s*:\s*none/)
    const live = all.filter((r) => /pointer-events\s*:\s*auto/.test(r.body)).map((r) => r.selector)
    expect(live.some((sel) => sel.includes('.rc-side-orb'))).toBe(true)
    expect(live.some((sel) => sel.includes('.rc-side-words') && sel.includes('.rc-chips'))).toBe(true)
  })

  test('in flight it moves by transform only: a fixed layer, nothing laid out again', () => {
    const flyer = all.find((r) => r.selector.trim() === '.recruiter .rc-flyer')
    expect(flyer?.body).toMatch(/position\s*:\s*fixed/)
    expect(flyer?.body).toMatch(/will-change\s*:\s*transform/)
    expect(flyer?.body).not.toMatch(/transition/)
  })
})

describe('the moods', () => {
  test('every section mood names the same tokens, so each is tuned in one place', () => {
    const tokens = ['--rc-paper', '--rc-ink', '--rc-meta', '--rc-rule', '--rc-accent', '--rc-glow-1', '--rc-glow-2', '--rc-glow-3']
    for (const mood of ['ledger', 'midcon', 'aw4']) {
      const rule = all.find((r) => r.selector.trim() === `.recruiter[data-page-theme="${mood}"]`)
      expect(rule, mood).toBeDefined()
      for (const token of tokens) expect(rule!.body, `${mood} ${token}`).toContain(`${token}:`)
    }
  })

  test('reduced motion: the light stays still and the blend is a short fade', () => {
    const still = all.filter((r) => r.within.includes('prefers-reduced-motion: reduce'))
    expect(still.some((r) => r.selector.includes('.rc-ambient') && /animation\s*:\s*none/.test(r.body))).toBe(true)
    expect(still.some((r) => r.selector.includes('[data-page-theme]') && /transition-duration\s*:\s*200ms/.test(r.body))).toBe(true)
  })
})

describe('print', () => {
  test('prints every section, revealed or not', () => {
    const print = all.filter((r) => r.within.includes('print') && r.selector.includes('[data-reveal]'))
    expect(print.some((r) => /opacity\s*:\s*1\s*!important/.test(r.body) && /transform\s*:\s*none\s*!important/.test(r.body))).toBe(true)
  })
})

describe('the side orb size', () => {
  test('its words ride in the 12rem column each section keeps room for', () => {
    const words = all.find((r) => r.selector.trim() === '.recruiter .rc-side-words' && !r.within.includes('@media'))
    expect(words?.body).toMatch(/width\s*:\s*12rem/)
  })
})
