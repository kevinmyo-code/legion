import { expect } from '@playwright/test'

import type { Shot } from './shots.spec'
import { briefingEngine, briefingVisit } from '../src/test/briefing-seed'

/**
 * Recruiter-demo ticket 14: Home as the Morning briefing on the desk, for a
 * member and for a recruiter's demo visit. At 390 px the family Home renders
 * (ADR 0053: two trees), so the phone shots show that it kept working.
 *
 *     SHOTS_LABEL=14 npm run shots
 */
export const briefingShots: Shot[] = [
  { name: 'home-briefing', url: '/', ready: /Worth a look|On today/, engine: briefingEngine, labels: ['14'] },
  {
    // The docked assistant open beside it: the column narrows to one card per row.
    name: 'home-briefing-assistant-open',
    url: '/',
    ready: /Worth a look|On today/,
    engine: briefingEngine,
    labels: ['14'],
    after: async (page) => {
      if ((page.viewportSize()?.width ?? 0) < 1024) return
      await page.getByRole('button', { name: 'Dorothy' }).click()
      await expect(page.getByRole('complementary', { name: 'Conversation with Dorothy' })).toBeVisible()
    },
  },
  { name: 'home-briefing-recruiter', url: '/', ready: /Worth a look|On today/, engine: briefingVisit, labels: ['14'] },
  // One chat (Kevin, 2026-11-03): the conversation opens only in the dock (desk)
  // or the sheet (phone), from Home and from /try alike.
  {
    name: 'home-one-chat-open',
    url: '/',
    ready: /Worth a look|On today|Today/,
    engine: briefingEngine,
    labels: ['one-chat'],
    viewportOnly: true,
    after: async (page) => {
      const desk = (page.viewportSize()?.width ?? 0) >= 1024
      if (desk) await page.getByRole('search', { name: /^Ask / }).getByRole('button', { name: 'Ask' }).click()
      else await page.getByRole('button', { name: /^Ask / }).last().click()
      await expect(page.getByRole('log')).toHaveCount(1)
    },
  },
  {
    name: 'try-one-chat-open',
    url: '/try',
    ready: 'Talk to my assistant',
    engine: briefingVisit,
    labels: ['one-chat'],
    viewportOnly: true,
    after: async (page) => {
      await page.getByRole('search', { name: /^Ask / }).getByRole('button', { name: 'Ask' }).click()
      await expect(page.getByRole('log')).toHaveCount(1)
    },
  },
  {
    name: 'try-one-chat-closed',
    url: '/try',
    ready: 'Talk to my assistant',
    engine: briefingVisit,
    labels: ['one-chat'],
    after: async (page) => {
      await expect(page.getByRole('log')).toHaveCount(0)
    },
  },
]
