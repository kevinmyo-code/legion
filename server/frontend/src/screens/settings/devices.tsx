import { useState } from 'react'

import type { components } from '@/api/schema'
import { useDevices, useRevokeDevice } from '@/api/settings'
import { InlineConfirm } from '@/components/settings/inline-confirm'
import { SettingsPage } from '@/components/settings/settings-page'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptySentence, ErrorSentence, Panel } from '@/components/workbench/page'

type Device = components['schemas']['DeviceToken']

function stamp(iso: string): string {
  const date = new Date(iso)
  return `${date.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}, ${date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}`
}

/**
 * `/settings/devices`: every phone or app signed in to YOUR account, and a button
 * to cut one off. Both surfaces (spec D12, story 64).
 *
 * Own devices only, by the engine's design: another member's phone is not listed
 * here, and an owner who wants a person gone removes the person (Household).
 *
 * This browser is not on the list and the screen says why, because a person who
 * is looking for "this laptop" and finds nothing would otherwise wonder whether
 * it is signed in at all: a browser holds a session, not a device token.
 *
 * "Last seen", not "last used": any request counts, the phone's background sync
 * included, so the time can be later than the last time anyone touched the phone
 * (the Google Account help says the same of its own list). It is a security fact
 * (is that old phone still talking to the engine?) and not an engagement figure,
 * and it is a date, never "N days ago".
 */
export function DevicesScreen() {
  const devices = useDevices()

  return (
    <SettingsPage
      title="Devices"
      subtitle="Phones and apps signed in to your account. This browser signs in a different way and is not listed."
    >
      <Panel title="Signed in">
        {devices.isError && (
          <ErrorSentence>
            Could not reach the engine, so this is not the real list of devices. Nothing was
            revoked.
          </ErrorSentence>
        )}
        {devices.isPending && <Skeleton className="h-20 rounded-card" />}
        {devices.data?.length === 0 && (
          <EmptySentence>No phones or apps are signed in to your account.</EmptySentence>
        )}
        {devices.data && devices.data.length > 0 && (
          <ul className="flex flex-col">
            {devices.data.map((device) => (
              <DeviceRow key={device.id} device={device} />
            ))}
          </ul>
        )}
      </Panel>
    </SettingsPage>
  )
}

function DeviceRow({ device }: { device: Device }) {
  const revoke = useRevokeDevice()
  const [confirming, setConfirming] = useState(false)
  const name = device.name.trim() === '' ? 'Unnamed device' : device.name

  return (
    <li className="border-b border-outline-variant py-3 last:border-b-0">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-[0.9375rem] font-medium">
            {name}
            {device.current && (
              <span className="font-normal text-muted-foreground"> (the device you are using now)</span>
            )}
          </p>
          <p className="text-[0.8125rem] text-muted-foreground">
            Signed in {stamp(device.created_at)}
            {' · '}
            {device.last_seen_at ? `Last seen ${stamp(device.last_seen_at)}` : 'Not seen yet'}
          </p>
        </div>
        {!confirming && (
          <Button variant="outline" size="sm" onClick={() => setConfirming(true)}>
            Revoke {name}
          </Button>
        )}
      </div>
      {confirming && (
        <InlineConfirm
          question={`Revoke ${name}?`}
          consequence={
            device.current
              ? 'This is the device making this request. It stops working at once and has to sign in again.'
              : 'It stops working at once and has to sign in again. Nothing on your account is deleted.'
          }
          confirmLabel={`Revoke ${name}`}
          busyLabel="Revoking"
          onConfirm={() => revoke.mutateAsync(device.id)}
          onClose={() => setConfirming(false)}
        />
      )}
    </li>
  )
}
