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

describe('the LEGION scene (Kevin, 2026-11-22: the words move with the highlights)', () => {
  test('the stage holds the diagram and the words: it is pinned, and fits the screen', () => {
    const stage = all.find((r) => r.selector.trim() === '.recruiter .rc-scene-stage' && !r.within.includes('@media'))
    expect(stage?.body).toMatch(/position\s*:\s*sticky/)
    expect(stage?.body).toMatch(/height\s*:\s*100svh/)
  })

  test('on a phone the stage stops above the foot bar and its safe area', () => {
    const phone = all.filter((r) => r.selector.includes('.rc-scene-stage') && /max-width:\s*899px/.test(r.within))
    expect(phone.some((r) => /height\s*:\s*calc\(100svh\s*-\s*76px\s*-\s*env\(safe-area-inset-bottom/.test(r.body))).toBe(true)
  })

  test('on a screen too short for it the stage is not pinned and every part shows, so nothing pinned covers its words', () => {
    const short = all.filter((r) => /max-height:\s*499px/.test(r.within))
    expect(short.some((r) => r.selector.includes('.rc-scene-stage') && /position\s*:\s*static/.test(r.body))).toBe(true)
    expect(short.some((r) => r.selector.includes('.rc-scene-card li') && /opacity\s*:\s*1/.test(r.body))).toBe(true)
  })

  test('text at 200% never runs off the stage: the card scrolls inside it', () => {
    const stage = all.find((r) => r.selector.trim() === '.recruiter .rc-scene-stage' && !r.within.includes('@media'))
    expect(stage?.body).toMatch(/overflow\s*:\s*hidden/)
    const card = all.find((r) => r.selector.trim() === '.recruiter .rc-scene-card' && !r.within.includes('@media'))
    expect(card?.body).toMatch(/overflow-y\s*:\s*auto/)
    expect(card?.body).toMatch(/min-height\s*:\s*0/)
  })

  test('print shows every part, in order, unpinned', () => {
    const print = all.filter((r) => r.within.includes('print'))
    expect(print.some((r) => r.selector.includes('.rc-scene-stage') && /position\s*:\s*static/.test(r.body))).toBe(true)
    expect(print.some((r) => r.selector.includes('.rc-scene-card li') && /opacity\s*:\s*1\s*!important/.test(r.body))).toBe(true)
  })

  test('the highlights move by transform, opacity and stroke only', () => {
    const motion = all.filter((r) => r.selector.includes('rc-scene-') && /transition|animation\s*:/.test(r.body))
    expect(motion.length).toBeGreaterThan(0)
    for (const r of motion) {
      const props = Array.from(r.body.matchAll(/transition\s*:([^;]+)/g)).flatMap((m) => m[1].split(/,(?![^(]*\))/).map((t) => t.trim().split(/\s+/)[0]))
      for (const prop of props) expect(['transform', 'opacity', 'stroke-dashoffset', 'none'], `${r.selector}: ${prop}`).toContain(prop)
    }
  })

  test('reduced motion: nothing travels or pulses, a change is a short fade', () => {
    const off = all.filter((r) => r.selector.includes('[data-motion="off"]'))
    expect(off.some((r) => r.selector.includes('.rc-scene-packet') && /display\s*:\s*none/.test(r.body))).toBe(true)
    expect(off.some((r) => r.selector.includes('.rc-scene-pulse') && /display\s*:\s*none/.test(r.body))).toBe(true)
    expect(off.some((r) => r.selector.includes('.rc-scene-card li') && /opacity 150ms/.test(r.body))).toBe(true)
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
    const flyer = all
      .filter((r) => r.selector.trim() === '.recruiter .rc-flyer')
      .map((r) => r.body)
      .join(';')
    expect(flyer).toMatch(/position\s*:\s*fixed/)
    expect(flyer).toMatch(/will-change\s*:\s*transform/)
    expect(flyer).not.toMatch(/transition/)
    // Round to the pointer: a tap in the corner of its square reaches what is under it.
    expect(flyer).toMatch(/border-radius\s*:\s*50%/)
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
