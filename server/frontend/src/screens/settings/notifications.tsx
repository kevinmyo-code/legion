import { Share } from 'lucide-react'
import { useState } from 'react'

import {
  DENIED_SENTENCE,
  useBrowserState,
  usePreferences,
  useSavePreferences,
  useSubscribeBrowser,
  useUnsubscribeBrowser,
  useVapid,
  type Preference,
  type PreferenceKind,
} from '@/api/push'
import { OkSentence, SettingsPage } from '@/components/settings/settings-page'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { ErrorSentence, Panel } from '@/components/workbench/page'
import { readSupport } from '@/lib/push-client'

/**
 * The words of this page. Written once, here, so the compulsion test (CLAUDE.md
 * section 7) can be run over every one of them (`-notifications.test.tsx` does):
 * each says what a notification IS, when it is sent and what it is anchored to, and
 * none mentions a person's absence, a streak, or how they use the app. Every
 * notification the engine sends carries "Turn these off" (`server/push/copy.py`),
 * and this page is the other half of that promise: a master off, in one tap.
 */
export const NOTIFICATION_COPY = {
  subtitle: 'Which messages are sent to your devices, and whether this device gets them.',
  notSetUp: 'Notifications are not set up on this server.',
  unsupported:
    'This browser cannot receive notifications, so nothing here can be turned on. A current Chrome, Edge, Firefox or Safari can.',
  iosTitle: 'Add LEGION to your Home Screen first',
  iosLead:
    'On iPhone and iPad, notifications work only for the installed app, and need iOS 16.4 or later. It takes three steps.',
  iosSteps: [
    'Tap the Share button in Safari’s toolbar.',
    'Choose Add to Home Screen, then tap Add.',
    'Open LEGION from its new icon, then go to Settings, then Notifications.',
  ],
  deviceOn: 'Notifications are on for this device.',
  deviceOff: 'Notifications are off for this device.',
  kinds: {
    list_changes: {
      label: 'List changes',
      blurb:
        'When someone adds to a shared list. Items added together arrive as one message, a couple of minutes after the first.',
    },
    event_reminders: {
      label: 'Event reminders',
      blurb: 'Before an event starts, at the reminder set on that event.',
    },
    task_due_morning: {
      label: 'Due today',
      blurb:
        'One message each morning listing the tasks due that day. If nothing is due, nothing is sent.',
    },
  },
  morningHelp: 'The time the “Due today” message is sent, in this browser’s time zone.',
  allOff: 'Everything is off. Turn one on above to get it.',
} as const

const KIND_ORDER: PreferenceKind[] = ['list_changes', 'event_reminders', 'task_due_morning']

/**
 * `/settings/notifications`: subscribe this browser to push, and choose what is
 * sent. Both surfaces (spec D7, D12, story 66).
 *
 * Five situations, each its own sentence and never folded into another:
 * the engine could not be reached; push is not set up on this engine; this is an
 * iPhone outside the installed app (the install card replaces the button, because
 * the button cannot work there); this browser has no push at all; or it can, and
 * the page shows the device and the three kinds.
 */
export function NotificationsScreen() {
  const vapid = useVapid()
  const support = readSupport()

  return (
    <SettingsPage title="Notifications" subtitle={NOTIFICATION_COPY.subtitle}>
      {vapid.isPending && <Skeleton className="h-32 rounded-sheet" />}
      {vapid.isError && (
        <ErrorSentence>
          Could not reach the engine, so this page cannot say whether notifications are set up.
          Nothing was changed.
        </ErrorSentence>
      )}
      {vapid.data && !vapid.data.enabled && (
        <Panel title="Notifications">
          <p className="text-[0.9375rem]">{vapid.data.detail ?? NOTIFICATION_COPY.notSetUp}</p>
        </Panel>
      )}
      {vapid.data?.enabled && support.kind === 'ios-install' && <InstallCard />}
      {vapid.data?.enabled && support.kind === 'unsupported' && (
        <Panel title="This device">
          <p className="text-[0.9375rem]">{NOTIFICATION_COPY.unsupported}</p>
        </Panel>
      )}
      {vapid.data?.enabled && support.kind === 'ready' && (
        <>
          <DevicePanel publicKey={vapid.data.public_key} />
          <KindsPanel />
        </>
      )}
    </SettingsPage>
  )
}

function InstallCard() {
  return (
    <Panel title={NOTIFICATION_COPY.iosTitle} description={NOTIFICATION_COPY.iosLead}>
      <ol className="flex flex-col gap-3" aria-label="How to add LEGION to the Home Screen">
        {NOTIFICATION_COPY.iosSteps.map((step, index) => (
          <li key={step} className="flex items-start gap-4 rounded-control bg-surface-2 px-4 py-3">
            <span
              aria-hidden="true"
              className="grid size-8 shrink-0 place-items-center rounded-full bg-primary-container text-[0.9375rem] font-medium text-primary-container-foreground"
            >
              {index + 1}
            </span>
            <span className="pt-1 text-[0.9375rem]">
              {step}
              {index === 0 && <Share aria-hidden="true" className="ml-2 inline size-4 align-text-bottom" />}
            </span>
          </li>
        ))}
      </ol>
    </Panel>
  )
}

function DevicePanel({ publicKey }: { publicKey: string | null }) {
  const browser = useBrowserState(true)
  const subscribe = useSubscribeBrowser(publicKey)
  const unsubscribe = useUnsubscribeBrowser(publicKey)
  const busy = subscribe.isPending || unsubscribe.isPending

  return (
    <Panel title="This device">
      {browser.isPending && <Skeleton className="h-12 rounded-control" />}
      {browser.isError && (
        <ErrorSentence>
          Could not read this browser&apos;s notification state, so this page cannot say whether they
          are on.
        </ErrorSentence>
      )}
      {browser.data?.permission === 'denied' && <ErrorSentence role="status">{DENIED_SENTENCE}</ErrorSentence>}
      {browser.data && browser.data.permission !== 'denied' && (
        <div className="flex flex-col gap-3">
          <p className="text-[0.9375rem]">
            {browser.data.subscribed ? NOTIFICATION_COPY.deviceOn : NOTIFICATION_COPY.deviceOff}
          </p>
          <div>
            {browser.data.subscribed ? (
              <Button variant="outline" disabled={busy} onClick={() => unsubscribe.mutate()}>
                {unsubscribe.isPending ? 'Turning off' : 'Turn off on this device'}
              </Button>
            ) : (
              <Button disabled={busy} onClick={() => subscribe.mutate()}>
                {subscribe.isPending ? 'Turning on' : 'Turn on notifications on this device'}
              </Button>
            )}
          </div>
        </div>
      )}
      {subscribe.error && (
        <div className="mt-3">
          <ErrorSentence>{subscribe.error.message}</ErrorSentence>
        </div>
      )}
      {unsubscribe.error && (
        <div className="mt-3">
          <ErrorSentence>{unsubscribe.error.message}</ErrorSentence>
        </div>
      )}
    </Panel>
  )
}

function KindsPanel() {
  const preferences = usePreferences(true)
  const save = useSavePreferences()
  const [time, setTime] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)

  if (preferences.isPending) return <Skeleton className="h-64 rounded-sheet" />
  if (preferences.isError) {
    return (
      <Panel title="What to send">
        <ErrorSentence>
          Could not reach the engine, so this is not what is really turned on. Nothing was changed.
        </ErrorSentence>
      </Panel>
    )
  }

  const current = preferences.data
  const anyOn = KIND_ORDER.some((kind) => current[kind])
  const shownTime = time ?? current.morning_time

  function change(next: Preference, sentence: string) {
    setSaved(null)
    save.mutate(next, { onSuccess: () => setSaved(sentence) })
  }

  return (
    <Panel
      title="What to send"
      description="The same on every device you turn notifications on for."
    >
      <ul className="flex flex-col">
        {KIND_ORDER.map((kind) => {
          const copy = NOTIFICATION_COPY.kinds[kind]
          const id = `push-${kind}`
          return (
            <li
              key={kind}
              className="flex items-start justify-between gap-4 border-b border-outline-variant py-4 first:pt-0 last:border-b-0"
            >
              <div className="min-w-0">
                <Label htmlFor={id} className="text-[1rem] leading-snug text-foreground">
                  {copy.label}
                </Label>
                <p className="mt-1 text-[0.8125rem] text-muted-foreground">{copy.blurb}</p>
              </div>
              <Switch
                id={id}
                checked={current[kind]}
                disabled={save.isPending}
                onCheckedChange={(checked) =>
                  change(
                    { ...current, [kind]: checked },
                    `${copy.label} ${checked ? 'turned on' : 'turned off'}.`,
                  )
                }
              />
            </li>
          )
        })}
      </ul>

      <form
        className="mt-2 flex flex-wrap items-end gap-3 border-t border-outline-variant pt-4"
        onSubmit={(event) => {
          event.preventDefault()
          change(
            { ...current, morning_time: shownTime },
            `The “Due today” message is now sent at ${shownTime}.`,
          )
        }}
      >
        <div className="flex w-40 flex-col gap-2">
          <Label htmlFor="push-morning-time">Morning time</Label>
          <Input
            id="push-morning-time"
            type="time"
            required
            value={shownTime}
            onChange={(event) => {
              setSaved(null)
              setTime(event.target.value)
            }}
          />
        </div>
        <Button
          type="submit"
          variant="secondary"
          disabled={save.isPending || shownTime === '' || shownTime === current.morning_time}
        >
          Save time
        </Button>
        <p className="basis-full text-[0.8125rem] text-muted-foreground">{NOTIFICATION_COPY.morningHelp}</p>
      </form>

      {save.error && (
        <div className="mt-4">
          <ErrorSentence>{save.error.message}</ErrorSentence>
        </div>
      )}
      {saved && !save.error && (
        <div className="mt-4">
          <OkSentence>{saved}</OkSentence>
        </div>
      )}

      <div className="mt-5 border-t border-outline-variant pt-4">
        {anyOn ? (
          <Button
            variant="outline"
            disabled={save.isPending}
            onClick={() =>
              change(
                {
                  ...current,
                  list_changes: false,
                  event_reminders: false,
                  task_due_morning: false,
                },
                'All notifications are turned off.',
              )
            }
          >
            Turn everything off
          </Button>
        ) : (
          <p className="text-[0.9375rem] text-muted-foreground">{NOTIFICATION_COPY.allOff}</p>
        )}
      </div>
    </Panel>
  )
}
