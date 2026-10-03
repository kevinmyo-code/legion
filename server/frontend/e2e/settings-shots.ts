import { expect, type Page } from '@playwright/test'

import type { Shot } from './shots.spec'
import { ME, createEngine, seedHousehold, type Engine } from '../src/test/engine'
import { livePreview, makeDevice, makeInvite } from '../src/test/engine-settings'

/**
 * Tickets 05 and 15: join, signup and every Settings screen. Rows live here, not
 * in `shots.spec.ts`, so another ticket adding rows in parallel does not collide
 * with these.
 *
 * Each row is a state a person can actually reach, including the ones the happy
 * path never shows: a code that is dead for each of its three reasons, one that
 * does not exist, one that could not be checked, a refused signup, a member's
 * view of the household with the owner-only parts absent.
 */

const DAD = '00000000-0000-4000-8000-0000000000d1'
const MOM = '00000000-0000-4000-8000-0000000000e2'

const roster = (myRole: 'owner' | 'member') => [
  { role: myRole, user_id: ME.user_id, email: ME.email, name: 'Mia', joined_at: '2026-09-01T12:00:00Z' },
  {
    role: myRole === 'owner' ? ('member' as const) : ('owner' as const),
    user_id: DAD,
    email: 'dad@example.test',
    name: 'Dad',
    joined_at: '2026-09-12T12:00:00Z',
  },
  { role: 'member' as const, user_id: MOM, email: 'mom@example.test', name: 'Mom', joined_at: '2026-09-14T12:00:00Z' },
]

const settingsEngine = (myRole: 'owner' | 'member' = 'owner', settings = {}) =>
  createEngine({ ...seedHousehold(), householdName: 'The Myo House', members: roster(myRole), settings })

const withInvites = () =>
  settingsEngine('owner', {
    invites: [
      makeInvite({ id: 2, code: 'Zk3-aB9_xQ2w', max_uses: 2, used_count: 0, expires_at: '2026-10-17T12:00:00Z' }),
      makeInvite({ id: 3, code: 'Hq7_mN4-pR8s', max_uses: 1, used_count: 0, expires_at: '2026-10-10T12:00:00Z' }),
    ],
  })

const withDevices = () =>
  settingsEngine('owner', {
    devices: [
      makeDevice({ id: 1, name: "Mia's iPhone" }),
      makeDevice({ id: 2, name: 'Old Pixel', created_at: '2026-06-02T09:00:00Z', last_seen_at: null }),
    ],
  })

const signedOut = (previews: Record<string, ReturnType<typeof livePreview>>) => () =>
  createEngine({ ...seedHousehold(), householdName: 'The Myo House', signedIn: false, settings: { previews } })

const fill = (page: Page, label: string, value: string) => page.getByLabel(label).fill(value)

const BOTH = ['05'] as const

export const settingsShots: Shot[] = [
  // Join and signup (ticket 05). Outside `_authed`, so no chrome.
  {
    name: 'join',
    url: '/join/abc123',
    ready: 'You have been invited to join',
    engine: signedOut({ abc123: livePreview('abc123', { household_name: 'The Myo House' }) }),
    labels: [...BOTH],
  },
  {
    name: 'join-founding',
    url: '/join/founder',
    ready: 'You have been invited to start a household',
    engine: signedOut({ founder: livePreview('founder', { creates_household: true, household_name: null }) }),
    labels: [...BOTH],
  },
  {
    name: 'join-refused',
    url: '/join/abc123',
    ready: 'You have been invited to join',
    engine: signedOut({ abc123: livePreview('abc123', { household_name: 'The Myo House' }) }),
    after: async (page) => {
      await fill(page, 'Your name', 'Dad')
      await fill(page, 'Email', 'taken@example.test')
      await fill(page, 'Password', 'a-long-enough-password')
      await page.getByRole('button', { name: 'Join the household' }).click()
      await expect(page.getByText(/An account with that email already exists/)).toBeVisible()
    },
    labels: [...BOTH],
  },
  {
    name: 'join-weak-password',
    url: '/join/abc123',
    ready: 'You have been invited to join',
    engine: signedOut({ abc123: livePreview('abc123', { household_name: 'The Myo House' }) }),
    after: async (page) => {
      await fill(page, 'Your name', 'Dad')
      await fill(page, 'Email', 'dad@example.test')
      await fill(page, 'Password', 'short')
      await page.getByRole('button', { name: 'Join the household' }).click()
      await expect(page.getByText(/This password is too short/)).toBeVisible()
    },
    labels: [...BOTH],
  },
  {
    name: 'join-expired',
    url: '/join/old',
    ready: 'This invite has expired.',
    engine: signedOut({
      old: livePreview('old', { live: false, expires_at: '2020-01-01T00:00:00Z', household_name: 'The Myo House', reason: 'expired' }),
    }),
    labels: [...BOTH],
  },
  {
    name: 'join-used-up',
    url: '/join/spent',
    ready: 'This invite has already been used by everyone it was for.',
    engine: signedOut({
      spent: livePreview('spent', { live: false, uses_left: 0, household_name: 'The Myo House', reason: 'used' }),
    }),
    labels: [...BOTH],
  },
  {
    name: 'join-revoked',
    url: '/join/pulled',
    ready: 'This invite was cancelled by the person who sent it.',
    engine: signedOut({
      pulled: livePreview('pulled', { live: false, household_name: 'The Myo House', reason: 'revoked' }),
    }),
    labels: [...BOTH],
  },
  { name: 'join-unknown', url: '/join/nope', ready: 'There is no invite with that code.', engine: signedOut({}), labels: [...BOTH] },
  {
    name: 'join-unreachable',
    url: '/join/abc123',
    ready: /Could not reach the engine, so this invite could not be checked/,
    engine: () => {
      const engine = signedOut({ abc123: livePreview('abc123') })()
      engine.refusals['GET /api/auth/invite/*'] = { status: 503, body: { detail: 'down' } }
      return engine
    },
    labels: [...BOTH],
  },
  { name: 'signup-code', url: '/signup', ready: 'Join with an invite code', engine: signedOut({}), labels: [...BOTH] },

  // Settings (ticket 05).
  { name: 'settings', url: '/settings', ready: 'Phones signed in to your account', engine: () => settingsEngine(), labels: [...BOTH], familyOnly: true },
  { name: 'settings-household', url: '/settings/household', ready: 'No invite links are open. Create one to add someone.', engine: () => settingsEngine(), labels: [...BOTH] },
  { name: 'settings-household-invites', url: '/settings/household', ready: '2 of 2 uses left', engine: withInvites, labels: [...BOTH] },
  {
    name: 'settings-household-remove',
    url: '/settings/household',
    ready: 'Dad',
    engine: () => settingsEngine(),
    after: async (page) => {
      await page.getByRole('button', { name: 'Remove Dad' }).click()
      await expect(page.getByRole('group', { name: 'Remove Dad from this household?' })).toBeVisible()
    },
    labels: [...BOTH],
  },
  {
    name: 'settings-household-revoke',
    url: '/settings/household',
    ready: '2 of 2 uses left',
    engine: withInvites,
    after: async (page) => {
      await page.getByRole('button', { name: 'Revoke' }).first().click()
      await expect(page.getByRole('group', { name: 'Revoke this invite link?' })).toBeVisible()
    },
    labels: [...BOTH],
  },
  {
    name: 'settings-household-member',
    url: '/settings/household',
    ready: 'Only the owner can rename the household.',
    engine: () => settingsEngine('member'),
    labels: [...BOTH],
  },
  {
    name: 'settings-household-unreachable',
    url: '/settings/household',
    ready: /Could not reach the engine, so this is not the real household/,
    engine: () => {
      const engine: Engine = settingsEngine()
      engine.householdFailing = true
      return engine
    },
    labels: [...BOTH],
  },
  { name: 'settings-devices', url: '/settings/devices', ready: "Mia's iPhone", engine: withDevices, labels: [...BOTH] },
  {
    name: 'settings-devices-revoke',
    url: '/settings/devices',
    ready: 'Old Pixel',
    engine: withDevices,
    after: async (page) => {
      await page.getByRole('button', { name: 'Revoke Old Pixel' }).click()
      await expect(page.getByRole('group', { name: 'Revoke Old Pixel?' })).toBeVisible()
    },
    labels: [...BOTH],
  },
  { name: 'settings-devices-empty', url: '/settings/devices', ready: 'No phones or apps are signed in to your account.', engine: () => settingsEngine(), labels: [...BOTH] },
  { name: 'settings-account', url: '/settings/account', ready: 'Change password', engine: () => settingsEngine(), labels: [...BOTH] },
  {
    name: 'settings-account-refused',
    url: '/settings/account',
    ready: 'Change password',
    engine: () => settingsEngine(),
    after: async (page) => {
      await fill(page, 'Current password', 'not-it')
      await fill(page, 'New password', 'a-brand-new-password')
      await page.getByRole('button', { name: 'Change password' }).click()
      await expect(page.getByText('Your current password is not right. Nothing was changed.')).toBeVisible()
    },
    labels: [...BOTH],
  },
  { name: 'settings-appearance', url: '/settings/appearance', ready: 'Saved on this device only. Each device keeps its own.', engine: () => settingsEngine(), labels: [...BOTH] },
]
