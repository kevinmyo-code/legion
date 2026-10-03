import { expect, type Page } from '@playwright/test'

import type { Shot } from './shots.spec'
import { seedCalendar } from '../src/test/calendar-seed'
import { createEngine } from '../src/test/engine'

/**
 * The calendar tickets' screens (07 shared and private, 09 the event sheet, 13
 * the calendar workbench), kept out of `shots.spec.ts` so a ticket building
 * another screen in parallel does not collide with these rows.
 */

const calendar = () => createEngine({ ...seedCalendar(), householdName: 'The Test House' })

const openNew = async (page: Page) => {
  await page.getByRole('button', { name: 'New event' }).click()
  await expect(page.getByRole('dialog', { name: 'New event' })).toBeVisible()
}

export const calendarShots: Shot[] = [
  {
    name: 'home-shared-private',
    url: '/',
    ready: 'Soccer pickup',
    engine: calendar,
    labels: ['07', '09', '13'],
  },
  {
    name: 'lists-shared-private',
    url: '/lists',
    ready: 'Weekend ideas',
    engine: calendar,
    labels: ['07'],
  },

  // Ticket 09: the event sheet, over Home.
  {
    name: 'sheet-new',
    url: '/',
    ready: 'Soccer pickup',
    engine: calendar,
    after: openNew,
    labels: ['09'],
    viewportOnly: true,
  },
  {
    name: 'sheet-weekly',
    url: '/',
    ready: 'Soccer pickup',
    engine: calendar,
    after: async (page) => {
      await openNew(page)
      const sheet = page.getByRole('dialog')
      await sheet.getByLabel('Title').fill('Swim lesson')
      await sheet.getByRole('switch', { name: 'All day' }).click()
      await sheet.getByRole('radio', { name: 'Weekly' }).click()
      await sheet.getByRole('button', { name: 'Tue' }).click()
      await sheet.getByRole('button', { name: 'Thu' }).click()
      await sheet.getByLabel('Reminder').selectOption('30')
      await sheet.getByRole('radio', { name: 'Only me' }).click()
    },
    labels: ['09'],
    viewportOnly: true,
  },
  {
    name: 'sheet-which',
    url: '/',
    ready: 'Soccer pickup',
    engine: calendar,
    after: async (page) => {
      await page.getByRole('button', { name: 'Edit Water the ferns' }).first().click()
      const sheet = page.getByRole('dialog', { name: 'Edit event' })
      await expect(sheet).toBeVisible()
      await sheet.getByRole('button', { name: 'Delete' }).click()
      await expect(sheet.getByRole('group', { name: 'Which events' })).toBeVisible()
    },
    labels: ['09'],
    viewportOnly: true,
  },
  {
    name: 'sheet-error',
    url: '/',
    ready: 'Soccer pickup',
    engine: () => {
      const engine = calendar()
      engine.refusals['POST /api/events'] = {
        status: 400,
        body: { remind_minutes_before: ['7 is not a reminder lead time this engine offers.'] },
      }
      return engine
    },
    after: async (page) => {
      await openNew(page)
      const sheet = page.getByRole('dialog')
      await sheet.getByLabel('Title').fill('Dentist')
      await sheet.getByRole('button', { name: 'Add' }).click()
      await expect(sheet.getByRole('alert')).toBeVisible()
    },
    labels: ['09'],
    viewportOnly: true,
  },

  // Ticket 13: the calendar. At 390 these are the phone's month and day agenda;
  // at 1440 the desk's week and month.
  { name: 'calendar', url: '/calendar', ready: /Last read/, engine: calendar, labels: ['13'] },
  {
    name: 'calendar-week-next',
    url: '/calendar',
    ready: /Last read/,
    engine: calendar,
    after: async (page) => {
      await page.getByRole('button', { name: 'Next week' }).click()
      await expect(page.getByText('Planning with Mia').first()).toBeVisible()
    },
    labels: ['13'],
    workbenchOnly: true,
  },
  {
    name: 'calendar-month',
    url: '/calendar',
    ready: /Last read/,
    engine: calendar,
    after: async (page) => {
      await page.getByRole('radio', { name: 'Month' }).click()
      await expect(page.getByRole('radio', { name: 'Month' })).toBeChecked()
    },
    labels: ['13'],
    workbenchOnly: true,
  },
  {
    name: 'calendar-event-edit',
    url: '/calendar',
    ready: /Last read/,
    engine: calendar,
    after: async (page) => {
      await page.getByRole('button', { name: 'Next week' }).click()
      await page.getByRole('button', { name: /^Edit Planning with Mia/ }).click()
      await expect(page.getByRole('dialog', { name: 'Edit event' })).toBeVisible()
    },
    labels: ['13'],
    workbenchOnly: true,
    viewportOnly: true,
  },
  {
    name: 'calendar-empty',
    url: '/calendar',
    ready: /Nothing on the calendar this week/,
    engine: () => createEngine({ householdName: 'The Test House' }),
    labels: ['13'],
    workbenchOnly: true,
  },
  {
    name: 'calendar-unreachable',
    url: '/calendar',
    ready: /Could not reach the engine, so this is not the real calendar/,
    engine: () => {
      const engine = calendar()
      engine.changesFailing = true
      return engine
    },
    labels: ['13'],
  },
]
