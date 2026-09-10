# Join by invite / sign up — research notes

Status: research only, ticket 05 blocked on 03/04. No screen built yet.

## What I looked at

1. **Apple Family Sharing**, Apple's own support docs (public, no login needed, text + structure,
   no embedded product screenshots on the current support.apple.com template):
   - `https://support.apple.com/en-us/108380` — "How to set up Family Sharing." Screenshot:
     `docs/design/refs/apple-family-sharing-setup.jpg`.
2. **Life360**, support.life360.com (public help center, text-only articles, no embedded product
   screenshots found):
   - `https://support.life360.com/hc/en-us/articles/23053525850519-Create-a-Life360-Account` —
     account creation, code entry happens near the end of the flow. Screenshot:
     `docs/design/refs/life360-create-account.jpg`.
   - `https://support.life360.com/hc/en-us/articles/23053409850647-Add-a-New-Member-to-My-Circle` —
     how an existing member invites someone. Screenshot: `docs/design/refs/life360-add-member.jpg`.
3. **Cozi** sign-up is behind a form on cozi.com (`Sign up for Cozi` / `Log in to Cozi`) — I did not
   go further than the marketing header because completing that form is account creation, which is
   off-limits for me. No evidence taken from Cozi for this screen.

## What I could not reach

- No live product screenshots of either Apple's or Life360's actual invite-code entry screen — both
  official sources describe the flow in numbered steps without embedding UI screenshots on the
  page I could load. I'm reporting the *flow decisions*, not the pixel layout, which is consistent
  with "copy the decision, never the pixels" regardless.
- Cozi's actual signup form (behind an account-creation action I'm not permitted to take).
- Spotify Family, Google Family Link, and Splitwise invite flows were not attempted — time went to
  the two sources above plus Today and household-members. Named gap.

## Decisions observed

**Apple Family Sharing** (`support.apple.com/en-us/108380`, `.../102652`):
- **One role distinction, and it's binary: "family organizer" (adult who set the group up) vs
  everyone else.** No further roles. Matches LEGION's own ruling (one `owner`, no roles beyond
  that) closely — this is corroborating evidence the ruling is a sane one for a household-of-a-few
  product, not just internally consistent.
- **The organizer must be confirmed as an adult** before they can create a group — a deliberate
  gate, not left implicit.
- **Invites go out through the user's own channels** (Messages or email that the OS hands off to),
  not an in-app "we sent an email for you" black box. The organizer picks who to invite from their
  own contacts and the invite text is composed for them but sent through something the user already
  trusts.
- **Invitation status is visibly checkable and resendable** ("tap the person's name" shows pending/
  accepted; "Resend Invitation" is one tap) — an invite is not fire-and-forget.

**Life360** (`support.life360.com`, two articles above):
- **The signup flow forks on one question, asked once, near the end of identity creation:**
  "If you have an invite code to join someone else's Circle, enter the code and tap Submit. If not,
  tap Get Started [to start your own]." This is the single decision LEGION's `/signup` also has to
  make — join vs create — and Life360 resolves it as **one code field with a binary outcome**, not
  two separate top-level screens the user has to choose between before they even know if they have
  a code.
- Order in Life360: phone number -> SMS verification -> name -> email -> birthday -> **then** the
  code-or-create fork. Identity is built first, household membership decided last. For LEGION this
  ordering is probably wrong for the audience: Kevin's parents receiving a text with a link should
  land on a page that already shows "You've been invited to join the Myo household" (or whatever
  the household is named) **before** asking them to make an account, because it answers "what is
  this and can I trust it" up front — see Life360's Add Member flow below for why.
- **Invite codes are short-lived (72 hours) and sent through the inviter's own messaging app**
  (Life360 hands off to the phone's native share sheet — text, email, WhatsApp — rather than
  Life360 sending mail itself). This is a strong match for LEGION's own open state: ticket 07
  (email delivery) is undecided and the map's own text says "until decided, invites are links Kevin
  shares." Life360's pattern — the inviter shares a link/code through whatever channel they
  already use — is not a stopgap for LEGION, it may be the right permanent shape even after email
  delivery is decided, because it puts the trust signal on "this came from someone I know" rather
  than "this came from an unfamiliar sender address."
- **The invite is generated from inside the "member list" screen** ("scroll to bottom of your
  Circle member list, tap Add a person"), i.e. invites live next to the people they're managing, not
  in a separate settings menu.

## What's worth stealing, and why

- **One code field, one fork, asked plainly.** LEGION's `/join/<code>` already carries the code in
  the URL (a click from the shared link, not manual entry) — better than Life360's manual-entry
  requirement, since the ticket's own contract has the invite validity check run against the code
  in the URL. Keep the manual-entry code field only as a fallback for someone who was read the code
  aloud rather than clicked a link.
- **Show what the invite is for before asking for anything.** `GET /api/auth/invite/<code>` (ticket
  05's own data contract) already returns "join-or-create and the household name, without spending
  it" — so the landing screen should say **"You've been invited to join the [Household name]
  household"** as the first line the parent reads, before the account form. This is exactly what
  Apple's organizer-adult confirmation and Life360's late-code both fail to do (both ask for
  identity first, trust second) — LEGION's own contract already supports doing it in the better
  order, so the screen should use it.
- **Compose the invite from wherever the code lives**, i.e. the settings/household screen shows the
  code and a share URL "once" (ticket 05's own wording) — matches Life360's "invite lives next to
  the member list" placement. Don't bury invite-minting in a separate admin area.
- **A code that expires.** LEGION doesn't currently specify TTL on invite codes in the ticket text;
  Life360's 72-hour window is a reasonable precedent to carry into ticket 03's build (a code with no
  expiry sitting in an old text thread is a standing risk with no offsetting benefit).

## What would be wrong here, and why

- **Life360's phone-number-first, SMS-verification identity flow** is the wrong shape for LEGION:
  ticket 03 is email + password, and adding SMS verification is out of scope and a dependency
  (telephony provider) nothing in this map asks for. Don't import the mechanism, only the
  *ordering* lesson (show the invitation's target before asking for identity).
  parents did not ask for two-factor SMS and it adds a support burden neither of them can self-
  serve if a number changes.
- **Apple's "you might be asked to confirm you're an adult"** is answering a legal/regional
  requirement (COPPA-adjacent, Apple has minors on the platform) that doesn't apply here — LEGION's
  household has no child-account distinction (CLAUDE.md: no roles beyond one `owner`). Don't import
  an age gate; there is nothing here for it to protect.
- **Neither source shows an error state for a bad/expired code.** LEGION has to design its own:
  in words, on the `/join/<code>` landing screen itself ("This invite has expired or was already
  used. Ask [household] to send a new one.") — not a generic 404, because a parent clicking an old
  text link is exactly the user this screen exists for, and a bare 404 reads as "the internet is
  broken," not "ask for a new link."

## Recommended layout for LEGION

`/join/<code>` lands on an explanation before a form:

```
+----------------------------------------------------+
|                                                       |
|   You've been invited to join                        |
|   the Myo household                                  |
|                                                       |
|   [Household name] uses this to keep everyone's       |
|   calendar and lists in one place.                    |
|                                                       |
|   Your name:      [____________]                      |
|   Email:          [____________]                      |
|   Password:       [____________]                      |
|                                                        |
|              [ Join the household ]                   |
|                                                        |
|   Already have an account?  Sign in instead.          |
+----------------------------------------------------+
```

If the code is the household-creating kind (per the ticket's "join-or-create" contract), the same
shell swaps the static household-name line for a field: "Name your household: [____________]" —
one screen, one fork, decided by what the code itself resolves to, not by a separate route the user
has to pick blind.

Expired/invalid code, same route, no form shown:

```
+----------------------------------------------------+
|   This invite link has expired or was already used.  |
|   Ask whoever invited you to send a new one.          |
|                                                        |
|              [ Have an account? Sign in ]              |
+----------------------------------------------------+
```

`/login` stays a second, separate, plain route (per ticket 05's table) with a one-line link:
"Have an invite? Use the link you were sent, or ask your household for one."

## Assumptions ledger

- Apple Family Sharing setup/removal text (organizer role, adult confirmation, invite-status
  checking, invite via native share): **in-browser** — loaded `support.apple.com/en-us/108380` and
  `.../102652` directly, read the rendered text, screenshots saved.
- Life360 account-creation ordering and invite-code TTL/channel: **in-browser** — loaded the two
  `support.life360.com` articles directly, read the rendered text, screenshots saved. No product
  UI screenshots were embedded on either page, so the *pixel* layout is unknown to me — only the
  documented flow order and copy are cited.
- Cozi signup: **not observed**, explicitly — stopped at the marketing header rather than initiating
  account creation.
- The recommended landing-before-form ordering and expired-code copy: **reasoned**, built from
  ticket 05's own data contract (`GET /api/auth/invite/<code>` returns join-or-create + household
  name before spending the code) plus the gap both sources leave around error states.
