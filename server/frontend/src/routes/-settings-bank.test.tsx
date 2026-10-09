import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { isUnverified } from '@/lib/ledger'
import type { PlaidCreateOptions } from '@/lib/plaid-link'
import { ME, createEngine, seedHousehold, type Member } from '@/test/engine'
import type { SettingsState } from '@/test/engine-settings'
import { renderApp } from '@/test/render-app'

/**
 * The bank connection screen (Plaid, ADR 0057). Plaid Link is faked by setting
 * `window.Plaid` before the button is pressed, so no script is fetched.
 */

afterEach(() => {
  vi.unstubAllGlobals()
  delete window.Plaid
})

const CONNECTED = {
  connected: true,
  institution_name: 'Bank of America',
  accounts: [{ name: 'Checking', mask: '1234', type: 'depository', subtype: 'checking' }],
  last_synced_at: new Date(Date.now() - 3 * 3600_000).toISOString(),
  consent_expires_at: '2027-03-01T00:00:00Z',
  sentence: 'Last synced fine.',
}

function engineFor(role: 'owner' | 'member', bank: Partial<SettingsState['bank']> = {}) {
  const members: Member[] = [
    { role, user_id: ME.user_id, email: ME.email, name: 'Mia', joined_at: '2026-09-01T12:00:00Z' },
  ]
  const engine = createEngine({ ...seedHousehold(), members })
  Object.assign(engine.settings.bank, bank)
  return engine
}

function fakePlaid() {
  const state: { options?: PlaidCreateOptions } = {}
  window.Plaid = {
    create: (options) => {
      state.options = options
      return { open: () => undefined }
    },
  }
  return state
}

const FORBIDDEN = /delete|disconnect|remove|relink|unlink/i

describe('bank connection', () => {
  test('always says which Plaid environment this is', async () => {
    renderApp('/settings/bank', engineFor('owner'))
    expect(await screen.findByTestId('bank-environment')).toHaveTextContent(
      'Test mode (Plaid sandbox): no real bank data.',
    )
  })

  test('owner, not connected: Connect bank and the slot warning, nothing that removes', async () => {
    renderApp('/settings/bank', engineFor('owner'))
    expect(await screen.findByRole('button', { name: 'Connect bank' })).toBeInTheDocument()
    expect(screen.getByText(/10 connections Plaid allows/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sync now' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: FORBIDDEN })).not.toBeInTheDocument()
  })

  test('not configured says why', async () => {
    renderApp(
      '/settings/bank',
      engineFor('owner', { configured: false, configuration_problem: 'No Plaid keys are set.' }),
    )
    expect(await screen.findByText(/No Plaid keys are set\./)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connect bank' })).not.toBeInTheDocument()
  })

  test('connected: accounts, Sign in again, Sync now, no Connect bank', async () => {
    renderApp('/settings/bank', engineFor('owner', CONNECTED))
    expect(await screen.findByText('Bank of America')).toBeInTheDocument()
    expect(screen.getByText(/Checking ••1234 \(depository, checking\)/)).toBeInTheDocument()
    expect(screen.getByText(/3 hours ago/)).toBeInTheDocument()
    expect(screen.getByText(/Last synced fine\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign in again' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sync now' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connect bank' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: FORBIDDEN })).not.toBeInTheDocument()
  })

  test('needs sign in shows the sentence', async () => {
    renderApp(
      '/settings/bank',
      engineFor('owner', {
        ...CONNECTED,
        needs_sign_in: true,
        sentence: 'Bank connection needs you to sign in again.',
      }),
    )
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Bank connection needs you to sign in again.',
    )
    expect(screen.getByRole('button', { name: 'Sign in again' })).toBeInTheDocument()
  })

  test('a member cannot connect or sign in, and is told why', async () => {
    renderApp('/settings/bank', engineFor('member', CONNECTED))
    expect(await screen.findByRole('button', { name: 'Sync now' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sign in again' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connect bank' })).not.toBeInTheDocument()
    expect(screen.getByText(/Only a household owner can connect/)).toBeInTheDocument()
  })

  test('create flow posts the exchange with the public token, then shows connected', async () => {
    const engine = engineFor('owner')
    const plaid = fakePlaid()
    renderApp('/settings/bank', engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Connect bank' }))
    await waitFor(() => expect(plaid.options).toBeDefined())
    expect(plaid.options?.token).toBe('link-sandbox-create')
    await act(async () => plaid.options?.onSuccess('public-sandbox-abc'))
    await waitFor(() =>
      expect(engine.writes.find((w) => w.pathname === '/api/ingest/plaid/exchange')?.body).toEqual({
        public_token: 'public-sandbox-abc',
      }),
    )
    expect(await screen.findByText('Bank of America')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connect bank' })).not.toBeInTheDocument()
  })

  test('update flow syncs and never exchanges', async () => {
    const engine = engineFor('owner', CONNECTED)
    const plaid = fakePlaid()
    renderApp('/settings/bank', engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Sign in again' }))
    await waitFor(() => expect(plaid.options).toBeDefined())
    expect(plaid.options?.token).toBe('link-sandbox-update')
    await act(async () => plaid.options?.onSuccess('public-ignored'))
    expect(await screen.findByText('Synced: 2 new transactions.')).toBeInTheDocument()
    const paths = engine.writes.map((w) => w.pathname)
    expect(paths).toContain('/api/ingest/plaid/sync')
    expect(paths).not.toContain('/api/ingest/plaid/exchange')
  })

  test('exiting Link with an error says it and changes nothing', async () => {
    const engine = engineFor('owner')
    const plaid = fakePlaid()
    renderApp('/settings/bank', engine)
    fireEvent.click(await screen.findByRole('button', { name: 'Connect bank' }))
    await waitFor(() => expect(plaid.options).toBeDefined())
    act(() => plaid.options?.onExit({ display_message: 'The bank is down.' }))
    expect(
      await screen.findByText(/Nothing was changed\. Plaid said: The bank is down\./),
    ).toBeInTheDocument()
    expect(engine.writes.map((w) => w.pathname)).not.toContain('/api/ingest/plaid/exchange')
  })

  test('Sync now shows the result sentence and the notes', async () => {
    renderApp('/settings/bank', engineFor('member', CONNECTED))
    fireEvent.click(await screen.findByRole('button', { name: 'Sync now' }))
    expect(await screen.findByText('Synced: 2 new transactions.')).toBeInTheDocument()
    expect(screen.getByText('One older file row was kept.')).toBeInTheDocument()
  })
})

describe('provenance', () => {
  test('BANK_API rows are not unverified', () => {
    expect(isUnverified({ provenance: 'BANK_API' } as never)).toBe(false)
    expect(isUnverified({ provenance: 'UNRECONCILED' } as never)).toBe(true)
  })
})
