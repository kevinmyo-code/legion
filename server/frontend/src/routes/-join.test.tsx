import { fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { deadKind } from '@/screens/join'
import { codeFrom } from '@/screens/signup-code'
import { createEngine, seedHousehold } from '@/test/engine'
import { livePreview } from '@/test/engine-settings'
import { renderApp } from '@/test/render-app'

/**
 * Join and signup (ticket 05): what an invite says before it asks for anything,
 * the sentence each way a code can fail, and what a refused signup says. The
 * fake engine answers with the real server's own wording
 * (`src/test/engine-settings.ts`), so a sentence asserted here is one the
 * engine actually says.
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

function signedOutEngine(previews: Record<string, ReturnType<typeof livePreview>>) {
  return createEngine({ ...seedHousehold(), signedIn: false, settings: { previews } })
}

function fill(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

describe('an invite that works', () => {
  test('says what it is for first, then signs up, signs in and lands on Home', async () => {
    const engine = signedOutEngine({ abc123: livePreview('abc123') })
    const { router } = renderApp('/join/abc123', engine)

    // The invitation's target comes BEFORE any field: trust, then identity.
    expect(await screen.findByText('You have been invited to join')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'The Test House' })).toBeInTheDocument()

    fill('Your name', 'Dad')
    fill('Email', 'dad@example.test')
    fill('Password', 'a-long-enough-password')
    fireEvent.click(screen.getByRole('button', { name: 'Join the household' }))

    // Home, behind `_authed`, which needs a session the signup flow just made.
    expect(await screen.findByText('Soccer pickup')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
    expect(engine.signedIn).toBe(true)

    const calls = engine.writes.map((write) => `${write.method} ${write.pathname}`)
    expect(calls).toEqual([
      'POST /api/auth/signup',
      // The device token signup mints is revoked straight away: a browser lives
      // on a session, and the leftover would sit in Devices as a phone that is not.
      'POST /api/auth/logout',
      'POST /api/auth/session/login',
    ])
    expect(engine.writes[0].body).toMatchObject({
      invite_code: 'abc123',
      name: 'Dad',
      email: 'dad@example.test',
      password: 'a-long-enough-password',
      device_name: 'Web sign-up',
    })
    // The code was previewed, not spent, until the form was sent.
    expect(engine.calls['GET /api/auth/invite/abc123']).toBe(1)
  })

  test('names the expiry in words', async () => {
    renderApp('/join/abc123', signedOutEngine({ abc123: livePreview('abc123', { expires_at: '2030-03-15T12:00:00Z' }) }))
    expect(await screen.findByText(/This invite is good until March 15, 2030\./)).toBeInTheDocument()
  })

  test('a code that founds a household asks for its name and sends it', async () => {
    const engine = signedOutEngine({
      founder: livePreview('founder', { creates_household: true, household_name: null }),
    })
    renderApp('/join/founder', engine)

    expect(await screen.findByText('You have been invited to start a household')).toBeInTheDocument()
    fill('Name your household', 'The Myos')
    fill('Your name', 'Mom')
    fill('Email', 'mom@example.test')
    fill('Password', 'a-long-enough-password')
    fireEvent.click(screen.getByRole('button', { name: 'Create the household' }))

    await waitFor(() => expect(engine.writes[0]?.body).toMatchObject({ household_name: 'The Myos' }))
  })
})

describe('a code that cannot be used says which way, in its own sentence', () => {
  test('expired', async () => {
    renderApp(
      '/join/old',
      signedOutEngine({
        old: livePreview('old', {
          live: false,
          expires_at: '2020-01-01T00:00:00Z',
          reason: 'That invite code has expired. Ask for a new one.',
        }),
      }),
    )
    expect(await screen.findByText('This invite has expired.')).toBeInTheDocument()
    expect(screen.getByText('Ask The Test House to send a new one.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Have an account? Sign in' })).toBeInTheDocument()
  })

  test('used up', async () => {
    renderApp(
      '/join/spent',
      signedOutEngine({
        spent: livePreview('spent', { live: false, uses_left: 0, reason: 'already used' }),
      }),
    )
    expect(
      await screen.findByText('This invite has already been used by everyone it was for.'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()
  })

  test('revoked', async () => {
    renderApp(
      '/join/pulled',
      signedOutEngine({
        pulled: livePreview('pulled', { live: false, reason: 'That invite code was revoked by the person who made it.' }),
      }),
    )
    expect(
      await screen.findByText('This invite was cancelled by the person who sent it.'),
    ).toBeInTheDocument()
  })

  test('unknown', async () => {
    renderApp('/join/nope', signedOutEngine({}))
    expect(await screen.findByText('There is no invite with that code.')).toBeInTheDocument()
    expect(screen.getByText(/Nothing was spent\./)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Type a code instead' })).toBeInTheDocument()
  })

  test('could not be checked is not the same sentence as bad', async () => {
    const engine = signedOutEngine({ abc123: livePreview('abc123') })
    engine.down = true
    renderApp('/join/abc123', engine)

    expect(
      await screen.findByText(
        'Could not reach the engine, so this invite could not be checked. Nothing was spent.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText('There is no invite with that code.')).not.toBeInTheDocument()

    // And it can be tried again once the engine is back.
    engine.down = false
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('You have been invited to join')).toBeInTheDocument()
  })

  test('deadKind reads the structured fields, not the reason text', () => {
    const base = livePreview('x', { live: false, reason: 'whatever the server words it as' })
    expect(deadKind({ ...base, expires_at: '2020-01-01T00:00:00Z', uses_left: 0 })).toBe('expired')
    expect(deadKind({ ...base, uses_left: 0 })).toBe('used-up')
    expect(deadKind({ ...base, uses_left: 1 })).toBe('revoked')
  })
})

describe('a signup the engine refuses', () => {
  async function ready() {
    const engine = signedOutEngine({ abc123: livePreview('abc123') })
    renderApp('/join/abc123', engine)
    await screen.findByText('You have been invited to join')
    fill('Your name', 'Dad')
    return engine
  }

  test('an email already registered says so, verbatim, and nothing is signed in', async () => {
    const engine = await ready()
    fill('Email', 'taken@example.test')
    fill('Password', 'a-long-enough-password')
    fireEvent.click(screen.getByRole('button', { name: 'Join the household' }))

    expect(
      await screen.findByText(
        'No account was created. An account with that email already exists - sign in instead, or use a different address.',
      ),
    ).toBeInTheDocument()
    expect(engine.signedIn).toBe(false)
    expect(engine.writes.map((write) => write.pathname)).toEqual(['/api/auth/signup'])
  })

  test('a rejected password is put beside the password box, in the engine words', async () => {
    await ready()
    fill('Email', 'dad@example.test')
    fill('Password', 'short')
    fireEvent.click(screen.getByRole('button', { name: 'Join the household' }))

    expect(
      await screen.findByText('This password is too short. It must contain at least 8 characters.'),
    ).toBeInTheDocument()
    expect(screen.getByText('No account was created. Check the fields above.')).toBeInTheDocument()
  })

  test('an engine that answers 500 without a reason still says nothing was created', async () => {
    const engine = await ready()
    fill('Email', 'dad@example.test')
    fill('Password', 'a-long-enough-password')
    engine.refusals['POST /api/auth/signup'] = { status: 500 }
    fireEvent.click(screen.getByRole('button', { name: 'Join the household' }))
    expect(
      await screen.findByText(/Nothing was created\. The engine answered 500 without saying why\./),
    ).toBeInTheDocument()
  })

  test('an account that was made but could not be signed in is not called a failed signup', async () => {
    const engine = await ready()
    fill('Email', 'dad@example.test')
    fill('Password', 'a-long-enough-password')
    engine.refusals['POST /api/auth/session/login'] = {
      status: 429,
      body: { detail: 'Request was throttled.' },
    }
    fireEvent.click(screen.getByRole('button', { name: 'Join the household' }))

    expect(await screen.findByText('Your account is created')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument()
  })
})

describe('/signup, the code box', () => {
  test('takes a pasted link, hands its code to /join and asks the engine about it', async () => {
    const engine = signedOutEngine({ abc123: livePreview('abc123') })
    const { router } = renderApp('/signup', engine)

    await screen.findByLabelText('Invite code or link')
    fill('Invite code or link', 'http://localhost:3000/join/abc123')
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByText('You have been invited to join')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/join/abc123')
  })

  test('says nothing about a code before the engine has been asked', async () => {
    const engine = signedOutEngine({})
    renderApp('/signup', engine)
    await screen.findByLabelText('Invite code or link')
    fill('Invite code or link', 'whatever')
    expect(screen.queryByText(/no invite/i)).not.toBeInTheDocument()
    expect(engine.calls['GET /api/auth/invite/whatever']).toBeUndefined()
  })

  test('the sign-in page points at it', async () => {
    renderApp('/login', signedOutEngine({}))
    fireEvent.click(await screen.findByRole('link', { name: 'enter its code' }))
    expect(await screen.findByText('Join with an invite code')).toBeInTheDocument()
  })

  test('codeFrom takes the last segment of a link and leaves a bare code alone', () => {
    expect(codeFrom('  Zk3-aB9_xQ2w ')).toBe('Zk3-aB9_xQ2w')
    expect(codeFrom('https://home.example/join/Zk3-aB9_xQ2w')).toBe('Zk3-aB9_xQ2w')
    expect(codeFrom('https://home.example/join/Zk3-aB9_xQ2w?utm=x#top')).toBe('Zk3-aB9_xQ2w')
    expect(codeFrom('')).toBe('')
  })
})
