---
map: google-account-integration
ticket: 01
title: "Does the grant survive, or does it lapse every 7 days?"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Does the grant survive, or does it lapse every 7 days?

## Question

Google documents that an OAuth client whose consent screen is in **Testing** status with **External**
user type issues refresh tokens that expire after **7 days**. If that applies here, every Gmail and
Calendar call would need Kevin to re-consent by hand each week, which probably kills the whole map.

Establish, against primary sources (Google Identity docs, Cloud console docs, Play Services
`com.google.android.gms.auth.api.identity` reference):

1. Does the 7-day refresh-token expiry actually apply to an app authorizing through Play Services'
   **Authorization API** (`Identity.getAuthorizationClient(...).authorize(...)`), which is what
   `sync/DriveAuth.kt` uses, or only to a classic server-side / installed-app OAuth flow that holds
   its own refresh token? LEGION holds no refresh token - `DriveAuth` asks GMS for a fresh access
   token each time and stores nothing.
2. If it does apply, what does the failure look like at the API surface - a silent
   `Outcome.NeedsConsent`, an `ApiException`, or a 401 from the REST call?
3. What moves the client out of Testing? Does **Internal** user type exist for a personal Gmail
   account (no Workspace org), or is **Production**/publishing the only exit? What does publishing
   cost for a **sensitive** scope (Calendar) versus a **restricted** scope (Gmail) when the app has
   exactly one user?
4. Is there a supported "personal use, one user" posture that avoids both weekly re-consent and a
   security assessment?

Answer must state which of these is documented fact and which is inference. This ticket blocks the
map's premise: if the answer is "weekly re-consent, no way out", Gmail is dead and Calendar has to
be reconsidered on `CalendarContract` alone.

Findings go to `.scratch/google-account-integration/research/01-testing-status-token-lifetime.md`.

## Answer

**Yes, the 7-day expiry bites, and holding no refresh token is not a defence. The map survives,
because the exit is Production status and publishing is NOT verification.**
Full findings and citations: [research/01-testing-status-token-lifetime.md](../research/01-testing-status-token-lifetime.md).
Resolved 2026-08-13 from a research agent's report; tags below are the agent's, carried forward
unchanged and NOT independently re-verified by the orchestrator.

1. **It applies at the GRANT layer, not the token layer** (`documented` for the grant, **`inferred`
   for Play Services**). Google's wording is *"Authorizations by a test user will expire seven days
   from the time of consent"*. GMS mints access tokens against that server-side grant, so
   `DriveAuth` storing nothing changes where a token lives, not whether the grant still exists.
   **Google's docs never mention Android or Play Services in connection with the rule, either way** -
   that last step is the agent's inference and is the single weakest claim here.
   `drive.appdata` falls outside the only exception (name/email/profile), so **Drive sync is already
   exposed to this today**, not just the unbuilt features.
2. **The failure surface is undocumented** (`inferred`): `hasResolution()` goes true and `authorize`
   returns `Outcome.NeedsConsent`, with no `ApiException`. A token minted just before expiry lives
   out its hour, then 401s. **The real hazard is in shipped code**: `accessTokenOrNull()` collapses
   that to `null` and `SyncEngine` swallows it by design as "cannot sync right now", so a weekly
   lapse would look identical to never having connected. That is exactly the shape of the failure
   `DriveConnectResolver` was written for, in the one path that still discards the reason.
   **Handed to ticket 06.**
3. **The exit is Production, and Internal is not available** (`documented`). Internal user type
   requires a Cloud Organization; a personal Gmail account has none. **Published-but-unverified is a
   documented, supported state**: any Google user, 100-user lifetime cap, a one-off unverified-app
   interstitial per account, and **no 7-day expiry**.
4. **A personal-use posture is documented explicitly, restricted scopes included** (`documented`):
   verification is not required *"if you are the only user of your app or if your app is used by only
   a few users, all of whom are known personally to you."* No CASA, no cost, no submission.

### Consequences, acted on

- **Map settled decision 5 was sloppy and has been amended.** "Can never be published without a
  security assessment" is true of *verified and publicly distributed*; it is **not** true of flipping
  the console's publishing status to In production, which is the actual fix and is free.
- **One unknown could still kill Gmail** (`needs-a-spike`): whether the console gates the Publish
  button behind a verification submission once restricted scopes are configured. Docs say only "may
  be subject to verification". **Recommended probe: press Publish NOW, while only `drive.appdata` is
  configured.** Cheapest possible test, and it fixes Drive's own exposure at the same time. Do it
  before ticket 02 commits the map to anything. **Pulled forward into ticket 09.**
- **Calendar's sensitive-vs-restricted classification is `inferred`** - Google's Calendar auth page
  lists the scopes without printing the tier label. Five minutes in the console settles it; folded
  into ticket 09.
