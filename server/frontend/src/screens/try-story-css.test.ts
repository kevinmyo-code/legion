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
})

describe('print', () => {
  test('prints every section, revealed or not', () => {
    const print = all.filter((r) => r.within.includes('print') && r.selector.includes('[data-reveal]'))
    expect(print.some((r) => /opacity\s*:\s*1\s*!important/.test(r.body) && /transform\s*:\s*none\s*!important/.test(r.body))).toBe(true)
  })
})
