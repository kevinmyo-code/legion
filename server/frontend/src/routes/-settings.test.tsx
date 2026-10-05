import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { ME, createEngine, seedHousehold, type Member } from '@/test/engine'
import { makeDevice, makeInvite } from '@/test/engine-settings'
import { renderApp } from '@/test/render-app'

/**
 * Settings (ticket 05), both surfaces: the index and the shell around it, the
 * household (rename, members, invites), devices, the account and appearance.
 *
 * The owner-only controls are asserted ABSENT for a member with `queryBy*`, and
 * every sentence the engine says about a refusal is asserted verbatim, because
 * that is what the screens promise to show.
 */

afterEach(() => {
  vi.unstubAllGlobals()
  Reflect.deleteProperty(navigator, 'clipboard')
  Reflect.deleteProperty(navigator, 'share')
})

const DAD = '00000000-0000-4000-8000-0000000000d1'
const MOM = '00000000-0000-4000-8000-0000000000e2'

function roster(myRole: 'owner' | 'member'): Member[] {
  return [
    { role: myRole, user_id: ME.user_id, email: ME.email, name: 'Mia', joined_at: '2026-09-01T12:00:00Z' },
    { role: myRole === 'owner' ? 'member' : 'owner', user_id: DAD, email: 'dad@example.test', name: 'Dad', joined_at: '2026-09-12T12:00:00Z' },
    { role: 'member', user_id: MOM, email: 'mom@example.test', name: 'Mom', joined_at: '2026-09-14T12:00:00Z' },
  ]
}

function engineFor(role: 'owner' | 'member' = 'owner', settings = {}) {
  return createEngine({ ...seedHousehold(), members: roster(role), settings })
}

function press(name: string | RegExp) {
  fireEvent.click(screen.getByRole('button', { name }))
}

describe('the shell and the index', () => {
  test('the family tab bar has a Settings tab, and the index lists every section', async () => {
    renderApp('/', engineFor(), 'family')

    const tabs = await screen.findByRole('navigation', { name: 'Tabs' })
    expect(within(tabs).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Home',
      'Lists',
      'Calendar',
      'Settings',
    ])

    fireEvent.click(within(tabs).getByRole('link', { name: 'Settings' }))
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeInTheDocument()
    for (const label of ['Account', 'Household', 'Devices', 'Appearance']) {
      expect(screen.getByRole('link', { name: new RegExp(`^${label}`) })).toBeInTheDocument()
    }
    // The side list is the workbench's; here it is not in the tree at all.
    expect(screen.queryByRole('navigation', { name: 'Settings sections' })).not.toBeInTheDocument()
  })

  test('the workbench rail has Settings, and /settings lands on a section with a side list', async () => {
    const { router } = renderApp('/settings', engineFor(), 'workbench')

    const rail = await screen.findByRole('navigation', { name: 'Sections' })
    expect(within(rail).getByRole('link', { name: 'Settings' })).toBeInTheDocument()

    expect(await screen.findByRole('heading', { name: 'Account' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/settings/account')
    const side = screen.getByRole('navigation', { name: 'Settings sections' })
    expect(within(side).getByRole('link', { name: 'Account' })).toHaveAttribute('aria-current', 'page')
    // A sub-route keeps Settings lit in the rail.
    expect(within(rail).getByRole('link', { name: 'Settings' })).toHaveAttribute('aria-current', 'page')
  })

  test('a sub-screen on the family surface has a way back to the index', async () => {
    const { router } = renderApp('/settings/devices', engineFor(), 'family')
    await screen.findByRole('heading', { name: 'Devices' })
    fireEvent.click(within(screen.getByRole('main')).getByRole('link', { name: 'Settings' }))
    expect(await screen.findByRole('link', { name: /^Appearance/ })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/settings')
  })

  test('sign-out is not in the chrome of either surface, only in Account', async () => {
    renderApp('/', engineFor(), 'family')
    await screen.findByRole('navigation', { name: 'Tabs' })
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument()
  })

  test('the workbench rail has no sign-out either, and keeps the theme toggle', async () => {
    renderApp('/', engineFor(), 'workbench')
    await screen.findByRole('navigation', { name: 'Sections' })
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Theme: / })).toBeInTheDocument()
  })
})

describe('the household, as the owner', () => {
  test('lists members with role and joined date, and renames the household', async () => {
    const engine = engineFor('owner')
    renderApp('/settings/household', engine, 'family')

    expect(await screen.findByText('Mia')).toBeInTheDocument()
    expect(screen.getByText('(you)')).toBeInTheDocument()
    expect(screen.getByText(/Owner · Joined Sep 1, 2026/)).toBeInTheDocument()
    expect(screen.getByText(/Member · Joined Sep 12, 2026/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Household name'), { target: { value: 'The Myos' } })
    press('Rename')
    expect(await screen.findByText('The household is now called The Myos.')).toBeInTheDocument()
    expect(engine.householdName).toBe('The Myos')
    expect(engine.writes.at(-1)).toMatchObject({ method: 'PATCH', pathname: '/api/households/me', body: { name: 'The Myos' } })
  })

  test('a refused rename says what did not happen, in the engine words', async () => {
    const engine = engineFor('owner')
    engine.refusals['PATCH /api/households/me'] = { status: 400, body: { detail: 'Nothing was changed: the name is taken.' } }
    renderApp('/settings/household', engine, 'family')
    await screen.findByText('Mia')
    fireEvent.change(screen.getByLabelText('Household name'), { target: { value: 'The Myos' } })
    press('Rename')
    expect(await screen.findByText('Nothing was changed: the name is taken.')).toBeInTheDocument()
  })

  test('removing a member asks first, says their private things go too, then removes them', async () => {
    const engine = engineFor('owner')
    renderApp('/settings/household', engine, 'family')
    await screen.findByText('Dad')

    press('Remove Dad')
    const confirm = screen.getByRole('group', { name: 'Remove Dad from this household?' })
    expect(within(confirm).getByText(/Their private events and lists are deleted with them\./)).toBeInTheDocument()
    expect(within(confirm).getByText(/What they shared with the household stays\./)).toBeInTheDocument()
    // Nothing happened yet.
    expect(engine.members.some((member) => member.user_id === DAD)).toBe(true)
    expect(engine.writes).toEqual([])

    fireEvent.click(within(confirm).getByRole('button', { name: 'Remove Dad' }))
    await waitFor(() => expect(screen.queryByText('Dad')).not.toBeInTheDocument())
    expect(engine.writes).toEqual([{ method: 'DELETE', pathname: `/api/households/me/members/${DAD}`, body: undefined }])
    expect(screen.getByText('Mom')).toBeInTheDocument()
  })

  test('keeping a member closes the confirm and writes nothing', async () => {
    const engine = engineFor('owner')
    renderApp('/settings/household', engine, 'family')
    await screen.findByText('Dad')
    press('Remove Dad')
    press('Keep')
    expect(screen.queryByRole('group', { name: /Remove Dad/ })).not.toBeInTheDocument()
    expect(engine.writes).toEqual([])
  })

  test('a refused removal keeps the confirm open and shows the engine sentence verbatim', async () => {
    const engine = engineFor('owner')
    engine.refusals['DELETE /api/households/me/members/*'] = {
      status: 400,
      body: { detail: 'Nobody was removed. Dad is the last owner of this household.' },
    }
    renderApp('/settings/household', engine, 'family')
    await screen.findByText('Dad')
    press('Remove Dad')
    fireEvent.click(within(screen.getByRole('group', { name: /Remove Dad/ })).getByRole('button', { name: 'Remove Dad' }))
    expect(await screen.findByText('Nobody was removed. Dad is the last owner of this household.')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: /Remove Dad/ })).toBeInTheDocument()
  })

  test('the owner has no Remove on their own row', async () => {
    renderApp('/settings/household', engineFor('owner'), 'family')
    await screen.findByText('Mia')
    expect(screen.queryByRole('button', { name: 'Remove Mia' })).not.toBeInTheDocument()
  })

  test('creates an invite link with the chosen uses and expiry, and lists it', async () => {
    const engine = engineFor('owner')
    renderApp('/settings/household', engine, 'family')
    expect(await screen.findByText('No invite links are open. Create one to add someone.')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('People'), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText('Expires after'), { target: { value: '7' } })
    press('Create invite link')

    expect(await screen.findByText(/Invite link created\./)).toBeInTheDocument()
    expect(engine.writes.at(-1)).toMatchObject({
      method: 'POST',
      pathname: '/api/households/me/invites',
      body: { max_uses: 3, expires_in_days: 7, creates_household: false },
    })
    expect(await screen.findByLabelText('Invite link')).toHaveValue('http://localhost:3000/join/NewCode1-xYz')
    expect(screen.getByText(/3 of 3 uses left/)).toBeInTheDocument()
  })

  test('copies a link and says so in words; says so when it could not', async () => {
    const engine = engineFor('owner', { invites: [makeInvite()] })
    renderApp('/settings/household', engine, 'family')
    await screen.findByLabelText('Invite link')

    // jsdom has no clipboard: the failure sentence, and the link is still there to select.
    press('Copy link')
    expect(await screen.findByText(/Could not copy\. The link is in the box above/)).toBeInTheDocument()

    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    press('Copy link')
    expect(await screen.findByText('Link copied.')).toBeInTheDocument()
    expect(writeText).toHaveBeenCalledWith('http://localhost:3000/join/Zk3-aB9_xQ2w')
  })

  test('offers Share only where the browser can share', async () => {
    renderApp('/settings/household', engineFor('owner', { invites: [makeInvite()] }), 'family')
    await screen.findByLabelText('Invite link')
    expect(screen.queryByRole('button', { name: 'Share' })).not.toBeInTheDocument()
  })

  test('shares through the native sheet when there is one', async () => {
    const share = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'share', { value: share, configurable: true })
    renderApp('/settings/household', engineFor('owner', { invites: [makeInvite()] }), 'family')
    await screen.findByLabelText('Invite link')
    press('Share')
    expect(share).toHaveBeenCalledWith({ title: 'Join our household', url: 'http://localhost:3000/join/Zk3-aB9_xQ2w' })
  })

  test('revoking a link asks first, then it is gone from the list', async () => {
    const engine = engineFor('owner', { invites: [makeInvite()] })
    renderApp('/settings/household', engine, 'family')
    await screen.findByLabelText('Invite link')

    press('Revoke')
    const confirm = screen.getByRole('group', { name: 'Revoke this invite link?' })
    expect(within(confirm).getByText(/Nobody can make an account with it afterwards\./)).toBeInTheDocument()
    expect(engine.writes).toEqual([])
    fireEvent.click(within(confirm).getByRole('button', { name: 'Revoke link' }))

    expect(await screen.findByText('No invite links are open. Create one to add someone.')).toBeInTheDocument()
    expect(engine.writes).toEqual([{ method: 'DELETE', pathname: '/api/households/me/invites/Zk3-aB9_xQ2w', body: undefined }])
  })

  test('an invite list that could not be read is not drawn as no invites', async () => {
    const engine = engineFor('owner')
    engine.refusals['GET /api/households/me/invites'] = { status: 503, body: { detail: 'down' } }
    renderApp('/settings/household', engine, 'family')
    expect(await screen.findByText(/this is not the real list of invite links/)).toBeInTheDocument()
    expect(screen.queryByText(/No invite links are open/)).not.toBeInTheDocument()
  })
})

describe('the household, as a member', () => {
  test('sees the sentences and none of the owner controls', async () => {
    renderApp('/settings/household', engineFor('member'), 'family')

    expect(await screen.findByText('Only the owner can rename the household.')).toBeInTheDocument()
    expect(screen.getByText('Only the owner can remove people from the household.')).toBeInTheDocument()
    expect(screen.getByText(/Only the owner can invite people\./)).toBeInTheDocument()

    expect(screen.queryByLabelText('Household name')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rename' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Remove / })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create invite link' })).not.toBeInTheDocument()
  })

  test('never asks the engine for the owner-only invite list', async () => {
    const engine = engineFor('member')
    renderApp('/settings/household', engine, 'family')
    await screen.findByText('Only the owner can rename the household.')
    expect(engine.calls['GET /api/households/me/invites']).toBeUndefined()
  })

  test('says it could not reach the engine rather than showing an empty household', async () => {
    const engine = engineFor('owner')
    engine.householdFailing = true
    renderApp('/settings/household', engine, 'family')
    expect(await screen.findByText(/Could not reach the engine, so this is not the real household\./)).toBeInTheDocument()
    expect(screen.queryByText('Members')).not.toBeInTheDocument()
  })
})

describe('devices', () => {
  test('lists the signed-in devices with dates, and says why this browser is not one', async () => {
    const engine = engineFor('owner', {
      devices: [
        makeDevice({ id: 1, name: "Mia's iPhone" }),
        makeDevice({ id: 2, name: 'Old Pixel', last_seen_at: null }),
      ],
    })
    renderApp('/settings/devices', engine, 'family')

    expect(await screen.findByText("Mia's iPhone")).toBeInTheDocument()
    expect(screen.getByText(/This browser signs in a different way and is not listed\./)).toBeInTheDocument()
    expect(screen.getAllByText(/Signed in Sep 20, 2026/)).toHaveLength(2)
    expect(screen.getByText(/Last seen Oct 3, 2026/)).toBeInTheDocument()
    expect(screen.getByText(/Not seen yet/)).toBeInTheDocument()
  })

  test('revoking asks first, then removes that device and leaves the others', async () => {
    const engine = engineFor('owner', {
      devices: [makeDevice({ id: 1, name: "Mia's iPhone" }), makeDevice({ id: 2, name: 'Old Pixel' })],
    })
    renderApp('/settings/devices', engine, 'family')
    await screen.findByText('Old Pixel')

    press('Revoke Old Pixel')
    const confirm = screen.getByRole('group', { name: 'Revoke Old Pixel?' })
    expect(within(confirm).getByText(/It stops working at once and has to sign in again\./)).toBeInTheDocument()
    expect(engine.writes).toEqual([])
    fireEvent.click(within(confirm).getByRole('button', { name: 'Revoke Old Pixel' }))

    await waitFor(() => expect(screen.queryByText('Old Pixel')).not.toBeInTheDocument())
    expect(screen.getByText("Mia's iPhone")).toBeInTheDocument()
    expect(engine.writes).toEqual([{ method: 'DELETE', pathname: '/api/auth/devices/2', body: undefined }])
  })

  test('says in words when nothing is signed in, and when it could not read the list', async () => {
    const empty = engineFor('owner', { devices: [] })
    const first = renderApp('/settings/devices', empty, 'family')
    expect(await screen.findByText('No phones or apps are signed in to your account.')).toBeInTheDocument()
    first.unmount()

    const failing = engineFor('owner')
    failing.refusals['GET /api/auth/devices'] = { status: 503, body: { detail: 'down' } }
    renderApp('/settings/devices', failing, 'family')
    expect(await screen.findByText(/this is not the real list of devices\. Nothing was revoked\./)).toBeInTheDocument()
    expect(screen.queryByText(/No phones or apps/)).not.toBeInTheDocument()
  })

  test('names the device you are using when the engine says so', async () => {
    renderApp('/settings/devices', engineFor('owner', { devices: [makeDevice({ current: true })] }), 'family')
    expect(await screen.findByText(/\(the device you are using now\)/)).toBeInTheDocument()
  })
})

describe('account', () => {
  test('changes the name', async () => {
    const engine = engineFor('owner')
    renderApp('/settings/account', engine, 'family')

    const field = await screen.findByLabelText('Name')
    expect(field).toHaveValue('Mia')
    fireEvent.change(field, { target: { value: 'Mia Myo' } })
    press('Save name')

    expect(await screen.findByText('Your name is saved.')).toBeInTheDocument()
    expect(engine.settings.name).toBe('Mia Myo')
    expect(engine.writes.at(-1)).toMatchObject({ method: 'PATCH', pathname: '/api/auth/me', body: { name: 'Mia Myo' } })
  })

  test('a wrong current password shows the engine sentence verbatim and claims nothing', async () => {
    renderApp('/settings/account', engineFor('owner'), 'family')
    fireEvent.change(await screen.findByLabelText('Current password'), { target: { value: 'not-it' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a-brand-new-password' } })
    press('Change password')

    expect(await screen.findByText('Your current password is not right. Nothing was changed.')).toBeInTheDocument()
    expect(screen.queryByText(/Your password is changed/)).not.toBeInTheDocument()
  })

  test('a rejected new password shows the engine rule, not a paraphrase', async () => {
    renderApp('/settings/account', engineFor('owner'), 'family')
    fireEvent.change(await screen.findByLabelText('Current password'), { target: { value: 'old-password-123' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'short' } })
    press('Change password')
    expect(
      await screen.findByText('Nothing was changed. This password is too short. It must contain at least 8 characters.'),
    ).toBeInTheDocument()
  })

  test('a changed password says what the engine says, and empties the boxes', async () => {
    const engine = engineFor('owner')
    renderApp('/settings/account', engine, 'family')
    fireEvent.change(await screen.findByLabelText('Current password'), { target: { value: 'old-password-123' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a-brand-new-password' } })
    press('Change password')

    expect(await screen.findByText('Your password is changed. You are still signed in here.')).toBeInTheDocument()
    expect(screen.getByLabelText('Current password')).toHaveValue('')
    expect(screen.getByLabelText('New password')).toHaveValue('')
    expect(engine.signedIn).toBe(true)
  })

  test('throttled attempts say nothing was changed', async () => {
    const engine = engineFor('owner')
    engine.refusals['POST /api/auth/password'] = {
      status: 429,
      body: { detail: 'Request was throttled. Expected available in 40 seconds.' },
    }
    renderApp('/settings/account', engine, 'family')
    fireEvent.change(await screen.findByLabelText('Current password'), { target: { value: 'x' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'y' } })
    press('Change password')
    expect(
      await screen.findByText('Nothing was changed. Request was throttled. Expected available in 40 seconds.'),
    ).toBeInTheDocument()
  })

  test('signing out ends the session and goes to the sign-in page', async () => {
    const engine = engineFor('owner')
    const { router } = renderApp('/settings/account', engine, 'family')
    await screen.findByLabelText('Name')
    press('Sign out')
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(engine.signedIn).toBe(false)
    expect(await screen.findByLabelText('Email')).toBeInTheDocument()
  })
})

describe('appearance', () => {
  test('choosing Dark sets the page and is the same choice the top-bar toggle shows', async () => {
    renderApp('/settings/appearance', engineFor('owner'), 'family')

    const dark = await screen.findByRole('radio', { name: /^Dark/ })
    expect(screen.getByRole('radio', { name: /^System/ })).toBeChecked()
    expect(document.documentElement).not.toHaveClass('dark')

    act(() => {
      fireEvent.click(dark)
    })
    await waitFor(() => expect(document.documentElement).toHaveClass('dark'))
    expect(dark).toBeChecked()
    expect(window.localStorage.getItem('legion.theme.v1')).toBe('dark')
    expect(screen.getByRole('button', { name: /^Theme: Dark/ })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('radio', { name: /^Light/ }))
    await waitFor(() => expect(document.documentElement).not.toHaveClass('dark'))
    expect(screen.getByRole('button', { name: /^Theme: Light/ })).toBeInTheDocument()
  })

  test('says it is kept on this device only', async () => {
    renderApp('/settings/appearance', engineFor('owner'), 'family')
    expect(await screen.findByText('Saved on this device only. Each device keeps its own.')).toBeInTheDocument()
  })
})

describe('the household timezone (Kevin, 2026-10-05)', () => {
  function engineWithZone(role: 'owner' | 'member', zone: string | null) {
    return createEngine({ ...seedHousehold(), members: roster(role), householdTimezone: zone })
  }

  test('unset, the owner is offered this browser zone and it is saved only when the engine says so', async () => {
    const engine = engineWithZone('owner', null)
    renderApp('/settings/household', engine, 'family')

    expect(await screen.findByRole('heading', { name: 'Household timezone' })).toBeInTheDocument()
    // Vitest runs with TZ=America/Chicago (vite.config.ts).
    expect(screen.getByText(/Not set yet\. This browser is in America\/Chicago/)).toBeInTheDocument()
    expect(screen.getByLabelText('Household timezone')).toHaveValue('America/Chicago')
    expect(engine.householdTimezone).toBeNull()

    press('Save timezone')
    expect(await screen.findByText('The household now keeps time in America/Chicago.')).toBeInTheDocument()
    expect(engine.householdTimezone).toBe('America/Chicago')
    expect(engine.writes.at(-1)).toMatchObject({
      method: 'PATCH',
      pathname: '/api/households/me',
      body: { timezone: 'America/Chicago' },
    })
  })

  test('the owner finds a zone by searching, and the select narrows to it', async () => {
    const engine = engineWithZone('owner', 'America/Chicago')
    renderApp('/settings/household', engine, 'workbench')

    const select = await screen.findByLabelText('Household timezone')
    expect(select).toHaveValue('America/Chicago')
    expect(screen.getByRole('button', { name: 'Save timezone' })).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Find a timezone'), { target: { value: 'tokyo' } })
    const options = within(select).getAllByRole('option').map((option) => option.getAttribute('value'))
    expect(options).toContain('Asia/Tokyo')
    expect(options).not.toContain('Europe/London')

    fireEvent.change(select, { target: { value: 'Asia/Tokyo' } })
    press('Save timezone')
    expect(await screen.findByText('The household now keeps time in Asia/Tokyo.')).toBeInTheDocument()
    expect(engine.householdTimezone).toBe('Asia/Tokyo')
  })

  test('a refusal is said in the engine words and nothing is shown as saved', async () => {
    const engine = engineWithZone('owner', null)
    engine.refusals['PATCH /api/households/me'] = {
      status: 403,
      body: { detail: 'Only the household owner can change the timezone. Nothing was changed.' },
    }
    renderApp('/settings/household', engine, 'family')
    await screen.findByLabelText('Household timezone')

    press('Save timezone')
    expect(
      await screen.findByText('Only the household owner can change the timezone. Nothing was changed.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/now keeps time in/)).not.toBeInTheDocument()
    expect(engine.householdTimezone).toBeNull()
  })

  test('a member sees the zone and who can change it, and no control', async () => {
    renderApp('/settings/household', engineWithZone('member', 'America/Chicago'), 'family')

    expect(await screen.findByText('America/Chicago')).toBeInTheDocument()
    expect(screen.getByText('Only the owner can change the household timezone.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Household timezone')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save timezone' })).not.toBeInTheDocument()
  })

  test('a member of a household with no zone is told it is not set, in words', async () => {
    renderApp('/settings/household', engineWithZone('member', null), 'workbench')
    expect(
      await screen.findByText(/Not set\. Until the owner sets one, each phone and browser says what day it is\./),
    ).toBeInTheDocument()
  })
})
