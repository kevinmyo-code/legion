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
    const side = all.find((r) => r.selector.trim() === '.recruiter .rc-voice-side' && !r.within.includes('@media'))
    expect(side?.body).toMatch(/pointer-events\s*:\s*none/)
    const live = all.filter((r) => /pointer-events\s*:\s*auto/.test(r.body)).map((r) => r.selector)
    expect(live.some((sel) => sel.includes('.rc-side-orb'))).toBe(true)
    expect(live.some((sel) => sel.includes('.rc-voice-side') && sel.includes('.rc-chips'))).toBe(true)
  })
})

describe('print', () => {
  test('prints every section, revealed or not', () => {
    const print = all.filter((r) => r.within.includes('print') && r.selector.includes('[data-reveal]'))
    expect(print.some((r) => /opacity\s*:\s*1\s*!important/.test(r.body) && /transform\s*:\s*none\s*!important/.test(r.body))).toBe(true)
  })
})

describe('the side orb size', () => {
  test('about twice the first cut: 10rem (160 px)', () => {
    const orb = all.filter((r) => r.selector.trim() === '.recruiter .rc-side-orb' && !r.within.includes('@media'))
    expect(orb.some((r) => /width\s*:\s*10rem/.test(r.body) && /height\s*:\s*10rem/.test(r.body))).toBe(true)
    // It still fits the room each section keeps for it, so it covers no section text.
    const panel = all.find((r) => r.selector.trim() === '.recruiter .rc-voice-side' && !r.within.includes('@media'))
    expect(panel?.body).toMatch(/width\s*:\s*12rem/)
  })
})
