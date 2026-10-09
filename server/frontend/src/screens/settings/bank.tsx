import { useState } from 'react'

import {
  useBankStatus,
  useExchangePublicToken,
  useLinkToken,
  useSyncBank,
} from '@/api/bank'
import type { components } from '@/api/schema'
import { relativeTime } from '@/components/freshness'
import { OkSentence, SettingsPage } from '@/components/settings/settings-page'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorSentence, Panel } from '@/components/workbench/page'
import { exitSentence, loadPlaid } from '@/lib/plaid-link'

type Status = components['schemas']['BankStatus']
type Account = components['schemas']['BankAccount']
type SyncResult = components['schemas']['SyncResult']

function day(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' })
}

function accountLine(account: Account): string {
  const kind = [account.type, account.subtype].filter((part) => part && part !== '').join(', ')
  const base = account.name ?? 'Account'
  const name = account.mask ? `${base} ••${account.mask}` : base
  return kind ? `${name} (${kind})` : name
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : 'Something failed.'
}

/**
 * `/settings/bank`: the bank feed the ledger reads (Plaid, ADR 0057).
 *
 * Three things are always on screen, because each prevents a specific mistake:
 * the environment sentence (a sandbox link is test data and must never be taken
 * for the real bank), the connection state in words, and the slot warning
 * (re-linking spends one of Plaid's ten lifetime connections). There is no
 * disconnect, remove or relink control anywhere on purpose; "Sign in again" uses
 * Plaid's update mode and keeps the same connection.
 */
export function BankScreen() {
  const status = useBankStatus()

  return (
    <SettingsPage
      title="Bank connection"
      subtitle="The bank feed your ledger reads, and signing in to the bank again."
    >
      {status.isPending && <Skeleton className="h-40 rounded-card" />}
      {status.isError && (
        <ErrorSentence>
          Could not reach the engine, so this is not the real state of the bank connection. Nothing
          was changed.
        </ErrorSentence>
      )}
      {status.data && <BankBody status={status.data} />}
    </SettingsPage>
  )
}

function BankBody({ status }: { status: Status }) {
  const linkToken = useLinkToken()
  const exchange = useExchangePublicToken()
  const sync = useSyncBank()
  const [working, setWorking] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [syncResult, setSyncResult] = useState<SyncResult | null>(null)

  async function runSync() {
    setProblem(null)
    setDone(null)
    try {
      setSyncResult(await sync.mutateAsync())
    } catch (error) {
      setSyncResult(null)
      setProblem(message(error))
    }
  }

  async function openLink(mode: 'create' | 'update') {
    setProblem(null)
    setDone(null)
    setSyncResult(null)
    setWorking(true)
    try {
      const token = await linkToken.mutateAsync(mode)
      const plaid = await loadPlaid()
      plaid
        .create({
          token,
          onSuccess: (publicToken) => {
            void (async () => {
              try {
                if (mode === 'create') {
                  await exchange.mutateAsync(publicToken)
                  setDone('The bank is connected.')
                } else {
                  // Update mode keeps the same connection: no exchange, only a sync.
                  setSyncResult(await sync.mutateAsync())
                }
              } catch (error) {
                setProblem(message(error))
              } finally {
                setWorking(false)
              }
            })()
          },
          onExit: (error) => {
            const said = exitSentence(error)
            if (said !== null) {
              setProblem(`Nothing was changed. Plaid said: ${said}`)
            }
            setWorking(false)
          },
        })
        .open()
    } catch (error) {
      setProblem(
        error instanceof Error && error.message.startsWith('Could not load')
          ? `${error.message} Nothing was changed. Check the connection and try again.`
          : message(error),
      )
      setWorking(false)
    }
  }

  const busy = working || sync.isPending
  const canManage = status.is_owner

  return (
    <>
      <p
        data-testid="bank-environment"
        className="rounded-control bg-surface-3 px-4 py-3 text-[1rem] font-semibold text-foreground"
      >
        {status.environment_sentence}
      </p>

      <Panel title="Status">
        {!status.configured && (
          <p className="text-[0.9375rem]">
            The engine has no bank feed set up. {status.configuration_problem ?? 'It did not say why.'}
          </p>
        )}
        {status.configured && !status.connected && (
          <p className="text-[0.9375rem]">
            No bank is connected. The ledger has no bank feed until one is.
          </p>
        )}
        {status.connected && (
          <div className="flex flex-col gap-3 text-[0.9375rem]">
            <p className="font-medium">{status.institution_name ?? 'Connected bank'}</p>
            {status.accounts.length === 0 ? (
              <p className="text-muted-foreground">No accounts were listed for this connection.</p>
            ) : (
              <ul className="flex flex-col gap-1">
                {status.accounts.map((account) => (
                  <li key={`${account.name}-${account.mask}`}>{accountLine(account)}</li>
                ))}
              </ul>
            )}
            <p>
              Last sync:{' '}
              {status.last_synced_at
                ? `${relativeTime(new Date(status.last_synced_at).getTime())}. `
                : 'never. '}
              {status.sentence}
            </p>
            {status.consent_expires_at && (
              <p>Bank consent runs out on {day(status.consent_expires_at)}.</p>
            )}
          </div>
        )}
      </Panel>

      {status.connected && status.needs_sign_in && (
        <p role="alert" className="rounded-control bg-surface-3 px-4 py-3 text-[1rem] font-semibold">
          {status.sentence}
        </p>
      )}

      <Panel title="Actions">
        <div className="flex flex-wrap gap-3">
          {status.configured && !status.connected && canManage && (
            <Button disabled={busy} onClick={() => openLink('create')}>
              Connect bank
            </Button>
          )}
          {status.connected && canManage && (
            <Button
              variant={status.needs_sign_in ? 'default' : 'outline'}
              disabled={busy}
              onClick={() => openLink('update')}
            >
              Sign in again
            </Button>
          )}
          {status.connected && (
            <Button variant="outline" disabled={busy} onClick={runSync}>
              {sync.isPending ? 'Syncing' : 'Sync now'}
            </Button>
          )}
        </div>
        {!canManage && (
          <p className="mt-3 text-[0.9375rem] text-muted-foreground">
            Only a household owner can connect the bank or sign in to it again.
          </p>
        )}
        {problem && (
          <div className="mt-3">
            <ErrorSentence>{problem}</ErrorSentence>
          </div>
        )}
        {done && (
          <div className="mt-3">
            <OkSentence>{done}</OkSentence>
          </div>
        )}
        {syncResult && (
          <div className="mt-3 flex flex-col gap-2">
            <OkSentence>{syncResult.sentence}</OkSentence>
            {syncResult.report?.notes && syncResult.report.notes.length > 0 && (
              <ul className="list-disc pl-6 text-[0.9375rem]">
                {syncResult.report.notes.map((note) => (
                  <li key={note}>{note}</li>
                ))}
              </ul>
            )}
          </div>
        )}
      </Panel>

      <p className="text-[0.9375rem] text-muted-foreground">{status.slot_warning}</p>
    </>
  )
}
