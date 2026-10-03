import type { Shot } from './shots.spec'
import { seedCalendar } from '../src/test/calendar-seed'
import { createEngine } from '../src/test/engine'

/**
 * The calendar tickets' screens (07 shared and private, 09 the event sheet, 13
 * the calendar workbench), kept out of `shots.spec.ts` so a ticket building
 * another screen in parallel does not collide with these rows.
 */

const calendar = () => createEngine({ ...seedCalendar(), householdName: 'The Test House' })

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
]
