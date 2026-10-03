import { useState } from 'react'

import type { components } from '@/api/schema'
import { useHousehold, useMe } from '@/api/queries'
import {
  useCreateInvite,
  useInvites,
  useRemoveMember,
  useRenameHousehold,
  useRevokeInvite,
} from '@/api/settings'
import { InlineConfirm } from '@/components/settings/inline-confirm'
import { OkSentence, SettingsPage } from '@/components/settings/settings-page'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptySentence, ErrorSentence, Panel } from '@/components/workbench/page'

type Member = components['schemas']['HouseholdMember']
type Invite = components['schemas']['Invite']

const EXPIRY_CHOICES = [
  { days: 1, label: '1 day' },
  { days: 3, label: '3 days' },
  { days: 7, label: '7 days' },
  { days: 14, label: '14 days' },
  { days: 30, label: '30 days' },
] as const

function dateLabel(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  })
}

function who(member: Member): string {
  return member.name.trim() === '' ? member.email : member.name
}

/**
 * `/settings/household`: the household's name, who is in it, and invite links.
 * Both surfaces (spec D12).
 *
 * **Owner-only is absent, not disabled.** An owner sees Rename, Create invite and
 * Remove; a member sees a sentence saying who can (a greyed button reads as a
 * fault, a sentence reads as a rule). The engine refuses a member's attempt
 * either way (`IsHouseholdOwner`), so hiding is a courtesy, never the lock.
 *
 * Membership is the only authorization and `owner` is the only role (ADR 0045):
 * nothing here describes anyone as an admin, a manager, or a guest.
 */
export function HouseholdScreen() {
  const me = useMe()
  const household = useHousehold(true)

  if (household.isError || me.isError) {
    return (
      <SettingsPage title="Household">
        <ErrorSentence>
          Could not reach the engine, so this is not the real household. Nothing here has been
          changed.
        </ErrorSentence>
      </SettingsPage>
    )
  }
  if (!household.data || !me.data) {
    return (
      <SettingsPage title="Household">
        <Skeleton className="h-28 rounded-sheet" />
        <Skeleton className="h-48 rounded-sheet" />
      </SettingsPage>
    )
  }

  const myId = me.data.signedIn ? me.data.me.user_id : null
  const members = household.data.members
  const isOwner = members.some((member) => member.user_id === myId && member.role === 'owner')

  return (
    <SettingsPage title="Household">
      <NamePanel name={household.data.name} isOwner={isOwner} />
      <MembersPanel members={members} myId={myId} isOwner={isOwner} />
      <InvitesPanel isOwner={isOwner} />
    </SettingsPage>
  )
}

function NamePanel({ name, isOwner }: { name: string; isOwner: boolean }) {
  const rename = useRenameHousehold()
  const [draft, setDraft] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const value = draft ?? name

  if (!isOwner) {
    return (
      <Panel title="Name">
        <p className="text-[0.9375rem]">{name}</p>
        <p className="mt-1 text-[0.8125rem] text-muted-foreground">
          Only the owner can rename the household.
        </p>
      </Panel>
    )
  }

  return (
    <Panel title="Name">
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
          <Label htmlFor="household-name">Household name</Label>
          <Input
            id="household-name"
            value={value}
            maxLength={120}
            onChange={(event) => {
              setSaved(false)
              rename.reset()
              setDraft(event.target.value)
            }}
          />
        </div>
        {rename.error && <ErrorSentence>{rename.error.message}</ErrorSentence>}
        {saved && <OkSentence>The household is now called {name}.</OkSentence>}
        <div>
          <Button
            type="submit"
            disabled={rename.isPending || value.trim() === '' || value.trim() === name}
          >
            {rename.isPending ? 'Saving' : 'Rename'}
          </Button>
        </div>
      </form>
    </Panel>
  )
}

function MembersPanel({
  members,
  myId,
  isOwner,
}: {
  members: Member[]
  myId: string | null
  isOwner: boolean
}) {
  return (
    <Panel
      title="Members"
      description={isOwner ? undefined : 'Only the owner can remove people from the household.'}
    >
      <ul className="flex flex-col">
        {members.map((member) => (
          <MemberRow
            key={member.user_id}
            member={member}
            isYou={member.user_id === myId}
            canRemove={isOwner && member.user_id !== myId}
          />
        ))}
      </ul>
    </Panel>
  )
}

function MemberRow({
  member,
  isYou,
  canRemove,
}: {
  member: Member
  isYou: boolean
  canRemove: boolean
}) {
  const remove = useRemoveMember()
  const [confirming, setConfirming] = useState(false)
  const name = who(member)

  return (
    <li className="border-b border-outline-variant py-3 last:border-b-0">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-[0.9375rem] font-medium">
            {name}
            {isYou && <span className="font-normal text-muted-foreground"> (you)</span>}
          </p>
          <p className="truncate text-[0.8125rem] text-muted-foreground">
            {member.role === 'owner' ? 'Owner' : 'Member'}
            {' · '}Joined {dateLabel(member.joined_at)}
            {member.name.trim() !== '' && ` · ${member.email}`}
          </p>
        </div>
        {canRemove && !confirming && (
          <Button variant="outline" size="sm" onClick={() => setConfirming(true)}>
            Remove {name}
          </Button>
        )}
      </div>
      {confirming && (
        <InlineConfirm
          question={`Remove ${name} from this household?`}
          consequence="They lose access, and any phone they signed in on is signed out. Their private events and lists are deleted with them. What they shared with the household stays."
          confirmLabel={`Remove ${name}`}
          busyLabel="Removing"
          onConfirm={() => remove.mutateAsync(member.user_id)}
          onClose={() => setConfirming(false)}
        />
      )}
    </li>
  )
}

function InvitesPanel({ isOwner }: { isOwner: boolean }) {
  const invites = useInvites(isOwner)
  const create = useCreateInvite()
  const [maxUses, setMaxUses] = useState(2)
  const [days, setDays] = useState(14)
  const [created, setCreated] = useState(false)

  if (!isOwner) {
    return (
      <Panel title="Invite someone">
        <p className="text-[0.9375rem] text-muted-foreground">
          Only the owner can invite people. Ask them for a link.
        </p>
      </Panel>
    )
  }

  return (
    <Panel
      title="Invite someone"
      description="An invite is a link you send yourself, by text or however you like. Anyone who opens it can make an account in this household until it runs out or you revoke it."
    >
      <form
        className="mb-5 flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          setCreated(false)
          create.mutate({ maxUses, expiresInDays: days }, { onSuccess: () => setCreated(true) })
        }}
      >
        <div className="flex w-28 flex-col gap-2">
          <Label htmlFor="invite-uses">People</Label>
          <Input
            id="invite-uses"
            type="number"
            inputMode="numeric"
            min={1}
            max={50}
            value={maxUses}
            onChange={(event) =>
              setMaxUses(Math.min(50, Math.max(1, Number(event.target.value) || 1)))
            }
          />
        </div>
        <div className="flex w-40 flex-col gap-2">
          <Label htmlFor="invite-days">Expires after</Label>
          <select
            id="invite-days"
            value={days}
            onChange={(event) => setDays(Number(event.target.value))}
            className="h-12 w-full rounded-t-control rounded-b-md border-0 border-b-2 border-outline bg-surface-2 px-3 text-base outline-none focus-visible:border-primary focus-visible:bg-surface-3"
          >
            {EXPIRY_CHOICES.map((choice) => (
              <option key={choice.days} value={choice.days}>
                {choice.label}
              </option>
            ))}
          </select>
        </div>
        <Button type="submit" size="lg" disabled={create.isPending}>
          {create.isPending ? 'Creating' : 'Create invite link'}
        </Button>
      </form>
      {create.error && <ErrorSentence>{create.error.message}</ErrorSentence>}
      {created && (
        <OkSentence>Invite link created. It is in the list below, ready to copy and send.</OkSentence>
      )}

      <div className="mt-4 flex flex-col gap-3">
        {invites.isError && (
          <ErrorSentence>
            Could not reach the engine, so this is not the real list of invite links.
          </ErrorSentence>
        )}
        {invites.isPending && <Skeleton className="h-24 rounded-card" />}
        {invites.data?.length === 0 && (
          <EmptySentence>No invite links are open. Create one to add someone.</EmptySentence>
        )}
        {invites.data?.map((invite) => <InviteCard key={invite.id} invite={invite} />)}
      </div>
    </Panel>
  )
}

function InviteCard({ invite }: { invite: Invite }) {
  const revoke = useRevokeInvite()
  const [confirming, setConfirming] = useState(false)
  const [copy, setCopy] = useState<'idle' | 'copied' | 'failed'>('idle')
  const left = invite.max_uses - invite.used_count
  const canShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function'

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(invite.join_url)
      setCopy('copied')
    } catch {
      setCopy('failed')
    }
  }

  return (
    <div className="rounded-card bg-surface-2 p-4">
      <p className="text-[0.9375rem] font-medium">
        {left} of {invite.max_uses} {invite.max_uses === 1 ? 'use' : 'uses'} left
        <span className="font-normal text-muted-foreground">
          {' · '}expires {dateLabel(invite.expires_at)}
        </span>
      </p>
      <Input
        readOnly
        aria-label="Invite link"
        value={invite.join_url}
        onFocus={(event) => event.currentTarget.select()}
        className="mt-3 h-11 bg-surface-3 text-[0.9375rem]"
      />
      <div className="mt-3 flex flex-wrap gap-2">
        <Button variant="secondary" onClick={copyLink}>
          Copy link
        </Button>
        {canShare && (
          <Button
            variant="secondary"
            onClick={() =>
              void navigator
                .share({ title: 'Join our household', url: invite.join_url })
                .catch(() => undefined)
            }
          >
            Share
          </Button>
        )}
        {!confirming && (
          <Button variant="outline" onClick={() => setConfirming(true)}>
            Revoke
          </Button>
        )}
      </div>
      {copy === 'copied' && (
        <p role="status" className="mt-2 text-[0.8125rem] text-muted-foreground">
          Link copied.
        </p>
      )}
      {copy === 'failed' && (
        <p role="status" className="mt-2 text-[0.8125rem] text-muted-foreground">
          Could not copy. The link is in the box above: select it and copy it yourself.
        </p>
      )}
      {confirming && (
        <InlineConfirm
          question="Revoke this invite link?"
          consequence="Nobody can make an account with it afterwards. People who already joined with it stay."
          confirmLabel="Revoke link"
          busyLabel="Revoking"
          onConfirm={() => revoke.mutateAsync(invite.code)}
          onClose={() => setConfirming(false)}
        />
      )}
    </div>
  )
}
