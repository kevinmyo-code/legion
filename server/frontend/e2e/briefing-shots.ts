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
]
