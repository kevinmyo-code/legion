# Testing status and the 7-day grant

Research for ticket 01 (google-account-integration). 2026-08-13.

## Headline

**Yes, it bites. Holding no refresh token does not exempt LEGION.**

Google's wording is about the *authorization*, not about a token the app stores:

> "Authorizations by a test user will expire seven days from the time of consent."
> - [Manage App Audience](https://support.google.com/cloud/answer/15549945)

The grant lives on Google's servers, keyed to (account, OAuth client, scopes). Play Services mints
access tokens *against that grant*. `DriveAuth` storing nothing changes where a token lives; it does
not change whether the grant behind it still exists. **documented** that the authorization expires;
**inferred** that GMS's silent path therefore stops working (see Q2).

The narrower refresh-token sentence says the same thing from the token side:

> "A Google Cloud Platform project with an OAuth consent screen configured for an external user type
> and a publishing status of 'Testing' is issued a refresh token expiring in 7 days, unless the only
> OAuth scopes requested are a subset of name, email address, and user profile."
> - [Using OAuth 2.0 to Access Google APIs](https://developers.google.com/identity/protocols/oauth2)

Note the exception clause: only `name`/`email`/`profile`. `drive.appdata`, Calendar, and Gmail are
all outside it. **documented**

**Google's docs nowhere mention Android, Play Services, or the Authorization API in connection with
this rule.** There is no documented carve-out for client-side-only apps, and there is no documented
statement that the rule applies to them either. The docs are silent. Do not read that silence as an
exemption. **documented (that it is silent)**

**The exit exists and is cheap: publish the app (publishing status In production, unverified).** See
Q3/Q4. That removes the 7-day expiry without any verification submission or security assessment.

---

## Q1. Does it apply to the Play Services Authorization API?

What the Android Authorization API actually does
([developer.android.com/identity/authorization](https://developer.android.com/identity/authorization)):

- `AuthorizationClient.authorize()` returns an `AuthorizationResult` carrying a short-lived access
  token; "Access tokens... have a 1-hour lifespan." **documented**
- "Refresh tokens are not stored on the device" - they only exist if you call
  `requestOfflineAccess(serverClientId)` and exchange `serverAuthCode` on **your own backend**.
  LEGION does neither (`DriveAuth.request()` sets scopes only). **documented + verified in repo**
- Tokens are cached locally and "automatically deleted when expired"; `clearToken()` drops a bad one.
  **documented**
- Once granted, "in subsequent sessions, call it again to obtain a fresh access token without user
  interaction (if permissions haven't been revoked)". `hasResolution() == false` on that path.
  **documented**

So the app-side picture matches `DriveAuth` exactly: no refresh token anywhere in LEGION's process.

**Why that does not save it.** The 7-day rule is a property of the *consent grant on Google's side*,
not of a token artifact the app happens to hold. Play Services can only mint an access token silently
because a live grant exists for that (account, client, scope set); that is precisely what
`hasResolution()` is reporting on. When Google expires the grant, GMS has nothing left to mint
against. The Manage-App-Audience sentence ("Authorizations... will expire") is written at that layer.
**inferred, and the inference is the load-bearing claim in this whole document.**

**The decisive cheap test already exists in the repo.** Drive sync connected on the device
**2026-08-13** (`memory/MEMORY.md`, Blocking) on scope `drive.appdata` - outside the name/email/profile
exception, so subject to the rule if the rule applies. Open LEGION on **2026-08-21 or later**,
without touching the consent screen in between, and run a sync:

- Sync works silently -> the rule does not reach the GMS Authorization API. Ticket answered empirically.
- Sync returns `NeedsConsent` -> confirmed, and Gmail/Calendar inherit it.

Eight days of waiting, zero code. **needs-a-spike** - and it is the only way to settle Q1 as fact
rather than inference, because Google does not document the interaction.

---

## Q2. What does the failure look like at the API surface?

**Google documents none of this.** No page ties grant expiry to a specific Android status code.
What follows is reasoned from the documented mechanics above:

| Layer | Expected behaviour |
|---|---|
| `Identity...authorize()` | `AuthorizationResult.hasResolution() == true` with a `PendingIntent` -> `DriveAuth.Outcome.NeedsConsent`. **inferred** |
| `accessTokenOrNull()` | Returns `null`. `SyncEngine` reads that as "can't sync right now" and degrades silently. **traced in repo** |
| A cached token minted just before expiry | Keeps working until its own 1-hour lifetime runs out, then the REST call returns HTTP 401. **inferred** from the documented 1-hour lifespan + local caching |
| `ApiException` | Not expected. This is a resolvable state, not an error. Nothing here would look like `DEVELOPER_ERROR` (10). **inferred** |

**The user-visible failure mode is the dangerous one for LEGION.** `NeedsConsent` on a background
sync path is indistinguishable from "never connected", and `accessTokenOrNull()` collapses it to a
`null` that `SyncEngine` swallows by design. Weekly, silently, with no surface saying why. If the
map proceeds while Testing status is still in force, whatever surface Gmail/Calendar get must
distinguish *never authorized* from *authorization lapsed* - the same lesson `DriveAuth`'s own doc
comment records from 2026-08-03. **reasoned**

---

## Q3. What moves the client out of Testing?

### Internal user type: not available to Kevin

> "Projects associated with a Google Cloud Organization can configure Internal users to limit
> authorization requests to members of the organization."
> - [Manage App Audience](https://support.google.com/cloud/answer/15549945)

Internal requires a Google Cloud **Organization**, which requires Workspace or Cloud Identity. A
personal `@gmail.com` account has no organization, so the Internal radio is unavailable. **documented**
(that Internal requires an org) / **inferred** (that a personal Gmail account therefore cannot select
it - Google states the requirement, not the negation).

Worth noting because Internal is the only status that dodges *everything*: no 7-day expiry, no
100-user cap, and no unverified-app screen. It is simply not reachable here without paying for a
Workspace or Cloud Identity tenancy. **inferred**

### Production is the exit, and publishing is not the same as verifying

Three documented app states
([OAuth app state overview](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview)):

| State | Who can use it | 7-day expiry |
|---|---|---|
| Testing | Only listed test users, hard cap 100 | Yes |
| Published / unverified | "Any Google user can access... a hard cap of 100 total users applies" | No |
| Published / verified | Any Google user, no cap | No |

**Published/unverified is a real, documented, supported state.** **documented** The Audience page
describes publishing as pressing a button - "after selecting the Publish app button" - and hedges
only with "Your project's configuration may be subject to verification." **documented**

Cost of the unverified published state:
- Users see the **unverified app screen** before consent: "an app... that requests a sensitive or
  restricted OAuth scope, but hasn't gone through the Google verification process"
  ([Unverified apps](https://support.google.com/cloud/answer/7454865)). **documented**
- A **lifetime** cap of 100 new users, which "applies over the entire lifetime of the project, and
  it cannot be reset or changed" ([FAQ](https://support.google.com/cloud/answer/13463817)).
  **documented** For a two-phone personal app this is irrelevant.
- The app name/logo will not be shown on the consent screen without brand verification. **documented**

### Verification cost if it were ever pursued (it should not be)

| Scope class | LEGION scopes | Requirement |
|---|---|---|
| Restricted | `gmail.readonly`, `gmail.metadata` (both listed restricted, [Gmail API scopes](https://developers.google.com/gmail/api/auth/scopes)) **documented** | ~6 weeks review **plus** an annual third-party security assessment (CASA, App Defense Alliance), priced between developer and assessor with "no involvement from Google" **documented** |
| Sensitive | Calendar (`calendar`, `calendar.events`, `calendar.readonly`) | ~10 business days review, no security assessment **documented** for the sensitive tier generally; **inferred** that these specific Calendar scopes are sensitive rather than restricted - Google's Calendar auth page lists the scopes but not their classification. Confirm by reading the label the Cloud console prints next to each scope. **needs-a-spike** (5 minutes in the console) |
| Non-sensitive | `drive.appdata` | not classified sensitive on the Drive scope list; the current Drive connection is unaffected either way. **inferred** |

That table is the price of *verification*, not of *publishing*. Q4 is why LEGION never pays it.

---

## Q4. Is there a supported "personal use, one user" posture?

**Yes, and Google names it explicitly.**

> "If the app is for your personal use (fewer than 100 users)... users will be allowed to click
> through 'unverified app' warning screens during sign-in."
> - [When is verification not needed](https://support.google.com/cloud/answer/13464323) **documented**

And on the restricted tier specifically, where the security assessment lives:

> "One use case is if you are the only user of your app or if your app is used by only a few users,
> all of whom are known personally to you."
> - [Restricted scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification) **documented**

**The supported posture is: External user type, publishing status In production, unverified,
personal-use exemption.** It gives:

- No 7-day expiry (that is a Testing-status property). **documented**
- No verification submission, no CASA, no cost, even with the restricted Gmail scope. **documented**
- An unverified-app interstitial on the *first* consent per account, clicked through once. **documented**
- A 100-new-user lifetime cap, never approached by two phones. **documented**

This is coherent with settled decision 5 on the map ("the app can never be published without a Google
security assessment") - "published" in that sentence means *verified and distributed*, which is dead
anyway. Flipping the console's publishing status to In production is a different act and is not
blocked by decision 5. **reasoned** Worth saying out loud on the map so the two senses of "published"
do not get conflated later.

**The one unknown.** The new Google Auth Platform console may gate the Publish button behind starting
a verification request when sensitive or restricted scopes are already configured - the Audience page
says only "may be subject to verification" and does not describe the button's preconditions.
**needs-a-spike:** open the Auth Platform > Audience page for this project, press Publish app with the
current `drive.appdata` scope, and see whether the status flips immediately or opens a verification
form. Do this **before** ticket 02 commits the map to Gmail. If publishing turns out to require a
verification submission for restricted scopes, the whole calculus changes and Gmail is back in doubt.

---

## Recommended order of operations

1. Flip publishing status to **In production** now, while only `drive.appdata` is configured. Cheapest
   possible test of the Publish button, and it fixes Drive's own exposure at the same time.
2. If it flips clean, the 7-day question is closed for the whole map and Gmail/Calendar proceed.
3. If it does not, run the 2026-08-21 Drive spike to learn whether the expiry reaches GMS at all
   before deciding Gmail is dead.
4. Either way, do **not** rely on Testing status plus "we hold no refresh token" as a defence.

---

## Assumptions ledger

| Claim | Tag |
|---|---|
| Testing + External issues a 7-day refresh token unless scopes are a subset of name/email/profile | documented |
| "Authorizations by a test user will expire seven days from the time of consent" | documented |
| LEGION holds no refresh token; `DriveAuth` requests scopes only, no `requestOfflineAccess` | verified in repo |
| Android access tokens live 1 hour, are cached locally, deleted on expiry | documented |
| Refresh tokens are never stored on-device by the Authorization API | documented |
| Google's docs never mention Android/Play Services in connection with the 7-day rule | documented (silence, verified across the OAuth2, Audience, and app-state pages) |
| **The 7-day expiry reaches a GMS-only app because it expires the server-side grant, not a client artifact** | **inferred** - the central claim, undocumented either way |
| Failure surfaces as `hasResolution() == true` -> `Outcome.NeedsConsent`, not an `ApiException` | inferred |
| A token minted just before expiry keeps working up to 1 hour, then 401 | inferred |
| `accessTokenOrNull()` returning null is swallowed silently by `SyncEngine` | traced in repo |
| Internal user type requires a Google Cloud Organization | documented |
| A personal @gmail.com account cannot select Internal | inferred (Google states the requirement, not the exclusion) |
| Published/unverified is a supported state: any Google user, 100-user lifetime cap | documented |
| Publishing removes the 7-day expiry | documented (the rule is scoped to Testing status by its own wording) |
| Unverified-app screen shown for sensitive/restricted scopes; user clicks through | documented |
| Personal use (<100 users, known personally) is an explicit verification exemption, restricted scopes included | documented |
| `gmail.readonly` and `gmail.metadata` are restricted scopes | documented |
| Restricted scopes need an annual CASA assessment priced developer-to-assessor | documented |
| Calendar scopes are sensitive rather than restricted | inferred - Calendar's auth page does not print the classification |
| Whether the console's Publish button is gated behind a verification submission | needs-a-spike |
| Whether Drive's grant actually survives past 2026-08-21 without re-consent | needs-a-spike |

## Sources

- https://developers.google.com/identity/protocols/oauth2
- https://support.google.com/cloud/answer/15549945 (Manage App Audience)
- https://support.google.com/cloud/answer/15549049 (Manage OAuth App Branding)
- https://developers.google.com/identity/protocols/oauth2/production-readiness/overview
- https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification
- https://support.google.com/cloud/answer/13464321 (Verification requirements)
- https://support.google.com/cloud/answer/13464323 (When is verification not needed)
- https://support.google.com/cloud/answer/13463817 (OAuth verification FAQ)
- https://support.google.com/cloud/answer/7454865 (Unverified apps)
- https://developer.android.com/identity/authorization (Authorization API on Android)
- https://developers.google.com/gmail/api/auth/scopes
- https://developers.google.com/workspace/calendar/api/auth
