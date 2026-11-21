// @vitest-environment node
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import type { Object3D } from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { describe, expect, it } from 'vitest'

/**
 * The committed garage model, read by the same loader the page uses. three's GLTFLoader strips
 * dots from node names, so the ids must survive in `extras` (as `userData.legion`): this is what
 * the card's highlight ids are looked up by (`model-stage.ts` `highlight`).
 */

const GLB = fileURLToPath(new URL('../../../../public/garage/xj-front-s0.glb', import.meta.url))

function parse(): Promise<Object3D> {
  const bytes = readFileSync(GLB)
  const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
  return new Promise((resolve, reject) => new GLTFLoader().parse(buffer, '', (gltf) => resolve(gltf.scene), reject))
}

describe('the garage model', () => {
  it('keeps every id the card may name, once each', async () => {
    const scene = await parse()
    const ids: string[] = []
    scene.traverse((node) => {
      const tag = (node.userData as { legion?: { id: string } }).legion
      if (tag) ids.push(tag.id)
    })
    expect(new Set(ids).size).toBe(ids.length)
    expect(ids).toContain('xj.susp.track_bar')
    expect(ids).toContain('xj.susp.track_bar@frame_end')
    expect(ids).toContain('xj.axle.hub.passenger@axle_nut')
    expect(ids.filter((id) => id.includes('@')).length).toBe(43)
  })
})
