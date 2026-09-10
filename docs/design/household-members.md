# Household members — research notes

Status: research only, ticket 05 blocked on 03/04. No screen built yet.

## What I looked at

1. **Apple Family Sharing**, support.apple.com (public, no login):
   - `https://support.apple.com/en-us/108380` — setup and invite. Screenshot:
     `docs/design/refs/apple-family-sharing-setup.jpg`.
   - `https://support.apple.com/en-us/102652` — "How to leave or remove a member from a Family
     Sharing group." Screenshot: `docs/design/refs/apple-family-sharing-remove-member.jpg`.
2. **Life360**, support.life360.com (public, no login):
   - `https://support.life360.com/hc/en-us/articles/23053409850647-Add-a-New-Member-to-My-Circle` —
     adding a member. Screenshot: `docs/design/refs/life360-add-member.jpg`.
   - I searched for Life360's "remove a member" article
     (`support.life360.com/hc/en-us/search?query=remove a member`) and the search result link did
     not resolve to a working article in my session (the direct search-result click did not
     navigate, and I did not spend further budget retrying). **No Life360 evidence for the removal
     flow specifically** — only for adding a member.

## What I could not reach

- Life360's own removal screen (search result failed to resolve; not retried further).
- Spotify Family Plan member management, Google Family Link member list — not attempted, time
  budget went to Apple + Life360 + the other two screens. Named gap.
- Neither Apple nor Life360 page had an embedded product screenshot on the page I could load — both
  are numbered-step text documentation. The decisions below are read from that text, not from
  pixels, which is consistent with this seat's "copy the decision, never the pixels" rule regardless.

## Decisions observed

**Apple Family Sharing** — two roles only, ever:
- **"Family organizer"**: the adult who created the group. Can invite, can remove anyone 13+, can
  disband the whole group.
- **Everyone else**: a member. Can only remove *themselves*.
- No further roles, no approval workflow for removal — the organizer acts unilaterally on other
  members, which matches LEGION's own ruling exactly (one `owner`, no approval workflows, ever).

**Removal is named plainly, requires a second confirming tap, and is reached through the person's
own row — not a separate "danger zone":**
> Tap the name of the family member that you want to remove. Tap **Remove [name] from Sharing
> Group**. Click **Remove [name]** to confirm.

That's the whole pattern: open the person, the removal action is right there labelled with their
actual name (not a generic "Remove member" that makes you second-guess who you're about to lose),
one more tap on a restated "Remove [name]" to confirm. No hidden menu, no "advanced settings," no
typing the person's name to confirm (that pattern exists for genuinely catastrophic actions — a full
account deletion — and Apple doesn't reach for it here, which is a useful calibration point: not
every destructive action deserves the heaviest possible confirmation).

**Consequences are stated up front, before the action, not discovered after:** the same support
page spells out exactly what breaks when someone leaves or is removed (loses shared purchases,
location sharing stops, Apple Cash balance transfers back) — this is documentation, not in-app
copy, but it tells us Apple treats "what happens next" as something worth explaining rather than
letting the user find out.

**Life360 (add-member side only):**
- Invites are minted from inside the member list itself ("scroll to the bottom of your Circle
  member list, tap Add a person") — the member list is the one place membership is managed, adding
  and (presumably, unverified) removing both live there.
- A numeric cap is disclosed as guidance, not a hard wall dressed up as one: "system limitation of
  99 members... we recommend limiting to 10 for optimal performance." Honest about the difference
  between a hard limit and a soft recommendation.

## What's worth stealing, and why

- **Removal lives on the member's own row, labelled with their name, not behind a settings icon.**
  "Remove Grandma Shirley from the household" is more honest and less alarming than a bare trash
  icon on a row, because the parent reading it knows exactly what they're about to do without
  having to infer it from an icon.
- **One extra confirming tap, restating the name — nothing heavier.** No "type the household name
  to confirm," no multi-screen wizard. This calibrates well against LEGION's own destructive-action
  posture (ADR 0035 hands-path, the general project tone of plain and direct) and against the
  actual blast radius: removing a member from a LEGION household revokes their access, it doesn't
  delete their data or anyone else's (ticket 05: `revoke` for invites and devices, `remove member`
  for people — all reversible by re-inviting).
- **Owner-only controls are simply absent for non-owners, and the API still refuses (ticket 05's
  own line)** — this matches Apple's model exactly (only the organizer sees remove-others; a
  member only ever sees remove-self, i.e. "leave"). Keep both halves: hide the control in the UI
  *and* refuse it server-side, because a hidden-only control is a UI bug waiting to be a security
  bug the day someone inspects the network tab.
- **State what happens next, in one line, next to the button** — not a full Apple-style legal
  breakdown (LEGION's blast radius is much smaller: no purchases, no subscriptions to untangle),
  but a single sentence answering "what happens to them and their stuff" is worth keeping, e.g.
  "They'll lose access to this household's calendar and lists. Nothing they added is deleted."

## What would be wrong here, and why

- **Apple's age-tiered removal rules** (can't remove a under-13 without deleting their Apple
  Account first, Screen Time must be off for 13-17 before they can leave) answer a child-account
  system LEGION does not have. Do not import any age logic — CLAUDE.md is explicit that there are
  no roles beyond one `owner`, and every adult member is symmetric except for that one flag.
- **A "type to confirm" pattern** (seen elsewhere for irreversible deletions, not actually used by
  Apple here) would be the wrong weight for a reversible remove-from-household action. Save that
  register, if it's ever needed, for something LEGION doesn't have yet (there is no "delete this
  household and all its data" flow in this ticket).
- **Burying membership management under a generic gear icon with no preview of who's in the
  household** would fail the brief's own question about not hiding a destructive action — the
  member list itself, visible up front on `/settings/household`, is the antidote; removal should
  never require more than one navigation step to reach.

## Recommended layout for LEGION

`/settings/household`, owner viewing it:

```
+----------------------------------------------------+
|  The Myo household                    [Rename]       |
+----------------------------------------------------+
|  Members                                              |
|                                                        |
|   Kevin Myo (you)                          Owner      |
|   Dad                                     [Remove]     |
|   Mom                                     [Remove]     |
|                                                        |
+----------------------------------------------------+
|  Invite someone                                       |
|   [ Create invite link ]                               |
+----------------------------------------------------+
```

Tapping [Remove] on a row expands or opens a small inline confirm, not a full-screen interstitial:

```
   Dad                                     [Remove]
   -> Remove Dad from this household?
      They'll lose access to this household's calendar
      and lists. Nothing they added is deleted.
      [ Cancel ]   [ Remove Dad ]
```

A member (non-owner) viewing the same route sees the list but no `[Remove]` on anyone else's row —
only a `[Leave household]` action on their own row, same confirm pattern, same one-sentence
consequence line.

Invite-minting shows the code/link **once**, per the ticket's own note ("shows the code and the
share URL once"), directly below the member list — not on a separate route — echoing Life360's
"invites live where members live" placement.

## Assumptions ledger

- Apple Family Sharing roles, removal flow wording, and stated consequences: **in-browser** —
  loaded `support.apple.com/en-us/108380` and `.../102652` directly, read the rendered text,
  screenshots saved to `docs/design/refs/`.
- Life360 add-member flow and member-list placement: **in-browser** — loaded
  `support.life360.com/hc/en-us/articles/23053409850647-...` directly, screenshot saved.
- Life360's removal flow specifically: **not observed** — the search result did not resolve in my
  session; named as a gap rather than guessed from the add-member pattern.
- The recommended inline-confirm layout and one-sentence consequence copy: **reasoned**, built from
  Apple's pattern plus ticket 05's own data contract (owner-only controls, hidden and refused for
  members; revoke/remove as the two mutation verbs already named in the ticket).
