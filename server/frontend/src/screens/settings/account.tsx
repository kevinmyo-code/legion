import { LogOut } from 'lucide-react'
import { useState } from 'react'

import { useMe } from '@/api/queries'
import { useChangePassword, useRenameMe } from '@/api/settings'
import { OkSentence, SettingsPage } from '@/components/settings/settings-page'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { useSignOut } from '@/components/use-sign-out'
import { ErrorSentence, Panel } from '@/components/workbench/page'

/**
 * `/settings/account`: your name, your password, and signing out. Both surfaces
 * (spec D12, story 65). Sign-out lives here and nowhere in the chrome, so the
 * one irreversible-feeling button is not a thumb away from the tab bar.
 *
 * Every sentence the engine says about a name or a password is shown VERBATIM
 * (`src/api/refusal.ts`), because it is written to be read by a person ("Your
 * current password is not right. Nothing was changed.") and a paraphrase here
 * would only be a second place for the words to drift. A success is claimed only
 * after the engine answered 2xx.
 */
export function AccountScreen() {
  const me = useMe()

  if (me.isError) {
    return (
      <SettingsPage title="Account">
        <ErrorSentence>
          Could not reach the engine, so this is not your real account. Nothing was changed.
        </ErrorSentence>
      </SettingsPage>
    )
  }
  if (!me.data || !me.data.signedIn) {
    return (
      <SettingsPage title="Account">
        <Skeleton className="h-40 rounded-sheet" />
      </SettingsPage>
    )
  }

  return (
    <SettingsPage title="Account" subtitle={me.data.me.email}>
      <NamePanel name={me.data.me.name} />
      <PasswordPanel />
      <SignOutPanel />
    </SettingsPage>
  )
}

function NamePanel({ name }: { name: string }) {
  const rename = useRenameMe()
  const [draft, setDraft] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const value = draft ?? name

  return (
    <Panel
      title="Your name"
      description="What the household sees next to what you add, and in the member list."
    >
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          setSaved(false)
          rename.mutate(value.trim(), {
            onSuccess: () => {
              setDraft(null)
              setSaved(true)
            },
          })
        }}
      >
        <div className="flex flex-col gap-2">
          <Label htmlFor="account-name">Name</Label>
          <Input
            id="account-name"
            autoComplete="name"
            maxLength={150}
            value={value}
            onChange={(event) => {
              setSaved(false)
              rename.reset()
              setDraft(event.target.value)
            }}
          />
        </div>
        {rename.error && <ErrorSentence>{rename.error.message}</ErrorSentence>}
        {saved && <OkSentence>Your name is saved.</OkSentence>}
        <div>
          <Button
            type="submit"
            disabled={rename.isPending || value.trim() === '' || value.trim() === name}
          >
            {rename.isPending ? 'Saving' : 'Save name'}
          </Button>
        </div>
      </form>
    </Panel>
  )
}

function PasswordPanel() {
  const change = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [said, setSaid] = useState<string | null>(null)

  return (
    <Panel title="Password" description="You stay signed in here when it changes.">
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          setSaid(null)
          change.mutate(
            { current, next },
            {
              onSuccess: (sentence) => {
                setSaid(sentence)
                setCurrent('')
                setNext('')
              },
            },
          )
        }}
      >
        <div className="flex flex-col gap-2">
          <Label htmlFor="current-password">Current password</Label>
          <Input
            id="current-password"
            type="password"
            autoComplete="current-password"
            required
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="new-password">New password</Label>
          <Input
            id="new-password"
            type="password"
            autoComplete="new-password"
            required
            value={next}
            onChange={(event) => setNext(event.target.value)}
          />
        </div>
        {change.error && <ErrorSentence>{change.error.message}</ErrorSentence>}
        {said && <OkSentence>{said}</OkSentence>}
        <div>
          <Button type="submit" disabled={change.isPending || current === '' || next === ''}>
            {change.isPending ? 'Changing' : 'Change password'}
          </Button>
        </div>
      </form>
    </Panel>
  )
}

function SignOutPanel() {
  const signOut = useSignOut()
  return (
    <Panel
      title="Sign out"
      description="Ends this browser's session. Your phone and other devices stay signed in."
    >
      <Button variant="outline" className="gap-2" onClick={signOut}>
        <LogOut />
        Sign out
      </Button>
    </Panel>
  )
}
