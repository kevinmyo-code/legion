// @vitest-environment node
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { Script } from 'node:vm'

import { describe, expect, it } from 'vitest'

/**
 * The phone video's hand-written scripts (`tools/phone-walkthrough/`) are not part of the build,
 * so nothing else would notice one that no longer parses (AGENT-RULES, 2026-11-17).
 */
const tools = (name: string) => fileURLToPath(new URL(`../../../../tools/phone-walkthrough/${name}`, import.meta.url))

describe('phone video tools parse', () => {
  it('render.mjs passes node --check', () => {
    expect(() => execFileSync(process.execPath, ['--check', tools('render.mjs')], { stdio: 'pipe' })).not.toThrow()
  })

  it("scenes.html's inline script compiles", () => {
    const html = readFileSync(tools('scenes.html'), 'utf8')
    const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1])
    expect(scripts).toHaveLength(1)
    expect(() => new Script(scripts[0])).not.toThrow()
  })
})
