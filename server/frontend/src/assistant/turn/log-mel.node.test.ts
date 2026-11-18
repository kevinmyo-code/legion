// @vitest-environment node
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { describe, expect, it, vi } from 'vitest'

import { N_FRAMES, N_MELS, WhisperLogMel } from './log-mel'

// Fails fast, with its name, if anything hangs.
vi.setConfig({ testTimeout: 10_000 })

/**
 * The browser's Smart Turn features against Hugging Face's WhisperFeatureExtractor, on the phone's
 * own golden vector (`app/src/test/resources/turn/`, made by `tools/turn/make_turn_golden.py`),
 * with the phone test's tolerance (`WhisperLogMelGoldenTest.kt`).
 */

const TOLERANCE = 2e-3

function fixture(name: string): Buffer {
  return readFileSync(fileURLToPath(new URL(`./__fixtures__/${name}`, import.meta.url)))
}

// Read through a DataView: little-endian whatever the platform, and whatever the Buffer's offset.
function audio(): Float32Array {
  const b = fixture('golden_short.pcm')
  const view = new DataView(b.buffer, b.byteOffset, b.byteLength)
  const out = new Float32Array(b.byteLength / 2)
  for (let i = 0; i < out.length; i++) out[i] = view.getInt16(i * 2, true) / 32768
  return out
}

function features(): Float32Array {
  const b = fixture('golden_short.f32')
  const view = new DataView(b.buffer, b.byteOffset, b.byteLength)
  const out = new Float32Array(b.byteLength / 4)
  for (let i = 0; i < out.length; i++) out[i] = view.getFloat32(i * 4, true)
  return out
}

function maxAbsDiff(a: Float32Array, b: Float32Array): number {
  expect(a.length).toBe(b.length)
  let m = 0
  for (let i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]))
  return m
}

describe('WhisperLogMel', () => {
  it('matches the Python extractor on the phone golden vector', () => {
    const got = new WhisperLogMel().extract(audio())
    expect(got.length).toBe(N_MELS * N_FRAMES)
    expect(maxAbsDiff(got, features())).toBeLessThan(TOLERANCE)
  })

  it('matches on the turn path and reuses frames on a re-check', () => {
    const pcm = audio()
    const mel = new WhisperLogMel()
    const key = {}
    expect(maxAbsDiff(mel.extractTurn(pcm, pcm.length, key), features())).toBeLessThan(TOLERANCE)
    const first = mel.lastFramesComputed
    mel.extractTurn(pcm, pcm.length, key)
    expect(mel.lastFramesComputed).toBeLessThan(first)
  })
})
