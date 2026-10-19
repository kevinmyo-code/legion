import { expect, type Page } from '@playwright/test'

import type { Shot } from './shots.spec'

/**
 * The PWA Tetris screen in each theme, mid-game, plus the pause overlay. Run:
 *
 *     SHOTS_LABEL=tetris-pwa npx playwright test e2e/shots.spec.ts -g tetris
 *
 * Phone shots are taken at the viewport's own size (`viewportOnly`) and fail when
 * the page scrolls, because "fits without page scroll" is the claim.
 */

const THEME_KEY = 'legion.tetris.theme'

async function play(page: Page, theme: string) {
  await page.evaluate(([key, value]) => window.localStorage.setItem(key, value), [THEME_KEY, theme] as const)
  await page.reload()
  await expect(page.getByText('Score').first()).toBeVisible()
  // Stack a few pieces so walls, grid, locked blocks and the ghost are all on screen.
  const keys = [
    ['ArrowLeft', 'ArrowLeft', 'ArrowLeft', ' '],
    ['ArrowRight', 'ArrowRight', ' '],
    ['ArrowLeft', 'ArrowLeft', 'ArrowLeft', 'ArrowLeft', 'z', ' '],
    ['ArrowRight', 'ArrowRight', 'ArrowRight', 'x'],
  ]
  for (const sequence of keys) for (const key of sequence) await page.keyboard.press(key)
  await page.waitForTimeout(150)
}

async function noPageScroll(page: Page) {
  const { scroll, inner, width } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollHeight,
    inner: window.innerHeight,
    width: window.innerWidth,
  }))
  if (width < 1024) expect(scroll, `page scrolls at phone width: ${scroll} > ${inner}`).toBeLessThanOrEqual(inner)
}

const row = (theme: string): Shot => ({
  name: `tetris-${theme}`,
  url: '/games/solo',
  ready: 'Score',
  labels: ['tetris-pwa'],
  viewportOnly: true,
  after: async (page) => {
    await play(page, theme)
    await noPageScroll(page)
  },
})

export const tetrisShots: Shot[] = [
  row('classic'),
  row('neon'),
  row('soft'),
  {
    name: 'tetris-paused',
    url: '/games/solo',
    ready: 'Score',
    labels: ['tetris-pwa'],
    viewportOnly: true,
    after: async (page) => {
      await play(page, 'neon')
      await page.keyboard.press('p')
      await expect(page.getByRole('dialog', { name: 'Paused' })).toBeVisible()
    },
  },
]
