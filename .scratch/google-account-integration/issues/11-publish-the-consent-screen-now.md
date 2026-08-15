# Press Publish now, while only Drive is at stake

Type: task
Status: resolved
Blocked by: -

## Question

Nothing to decide. Graduated 2026-08-13 out of
[ticket 01](01-testing-status-token-lifetime.md), which found this is the cheapest probe available
and that it fixes a live problem regardless of what the map decides next.

**Why now, and not as part of ticket 09.** One unknown could still kill Gmail: whether the Auth
Platform console gates the **Publish** button behind a verification submission once restricted
scopes are configured. Google's docs say only that an app "may be subject to verification". Pressing
Publish **while the client has only `drive.appdata` on it** answers that with no restricted scope in
play - and if it turns out the button is gated later, the map learns it before ticket 02 commits
anything to Gmail. Doing it inside ticket 09, after the Gmail scope is added, throws that
information away.

It also fixes something already broken: a Testing-status grant expires 7 days after consent, and
`SyncEngine` would swallow the lapse silently. **The consent date is unknown** - Kevin reported on
2026-08-13 that Drive had connected, without saying when. Get that date first; the deadline is seven
days after it, not seven days after the 13th.

**Ticket 02's agent disputed the premise**, arguing the expiry cannot bite an app that stores no
refresh token. Ticket 01's agent found the rule stated at the **grant** layer, where that is
irrelevant. Neither is verified. **This ticket is how the argument gets settled - by observation.**

**Kevin does the console half.**

1. In the Google Auth Platform console for the `com.kevin.legion` client, record the **current**
   publishing status and user type before touching anything.
2. Press **Publish** / move to In production. Record whether it succeeds immediately, demands a
   verification submission, or warns about anything.
3. Record the resulting status, and whether the 100-user cap and unverified-app interstitial are
   stated in the console the way the docs describe.
4. **While you are in there, five minutes of free information for ticket 02:** read off whether the
   Google **Calendar** scopes are labelled sensitive or restricted. Ticket 01 could only infer it -
   Google's Calendar auth page lists the scopes without printing the tier.
5. Re-authorize Drive on the phone and confirm sync still works after the status change. Note the
   interstitial if one appears, and what it says - ticket 06 needs the exact wording.

## Verification

**This is the one that actually settles ticket 01's weakest claim, and it costs nothing but time.**
Ticket 01's core finding - that the 7-day rule reaches an app authorizing through Play Services - is
tagged `inferred`, because Google's docs never mention Android in connection with it.

- Drive consent was given **2026-08-13**. If the app is opened on **2026-08-21 or later** without
  publishing and without touching consent, and sync still works silently, the rule does not reach
  GMS. If `authorize` comes back `NeedsConsent`, it does.
- Publishing first **destroys that experiment**. If Kevin wants the answer, note the date and check
  before pressing Publish. If he does not care, publish now - the fix is the same either way and the
  answer is only of academic interest once the client is in Production.
- Record which of those two he chose, and the outcome.

## Answer

**Published. Not gated. The one unknown that could have killed Gmail is cleared.**

Done by Kevin, 2026-08-13, reported in-session.

1. **Publishing status went `Testing` -> `In production`**, user type `External` both before and after.
   **No verification submission was demanded**, with `drive.appdata` the only configured scope. That
   was ticket 01's `needs-a-spike` item and the last thing that could have blocked the map.
2. **The 7-day grant expiry is no longer a live threat**, because the client is out of Testing. The
   silent-failure path it exposed was fixed anyway in
   [ticket 12](12-google-grant-plumbing.md) - a revoked grant walks the same path and always could.
3. **Calendar scope tiers: not read, and no longer needed.** Kevin pasted the scope picker's
   descriptions rather than the tier column. Moot - [ticket 02](02-calendar-api-choice.md) chose
   `CalendarContract`, so LEGION requests **no Calendar OAuth scope at all**. **Nothing from that
   picker should be added to Data Access.** The Calendar API was enabled in the Cloud project during
   this session; it is inert and harmless, and stands as a free fallback if ticket 14's spike fails.
4. **The consent screen is branded "Midnight AI", and the Drive grant is dated 15 July** - a month
   before this repo existed. Checked with Kevin: the Google Auth Platform **Branding** page says
   `Midnight AI`. **So it is one client, reused from the archived project, showing under the old
   name.** The granted scope text ("see, create, and delete its own configuration data in your Google
   Drive") is `drive.appdata`, which is what LEGION wants. **Cosmetic; Kevin has deliberately left
   it.** Worth renaming eventually so the consent screen stops naming a dead product, but it is not a
   blocker and nothing depends on it.
   Written down because `memory/library/decisions.md` records a prior incident of an OAuth client
   registered in the wrong Cloud project, and a future session finding "Midnight AI" here should not
   re-open that hunt.

### An unplanned data point, recorded as evidence and NOT as proof

That Drive grant was given **15 July** and was still listed as active on **13 August** - 29 days -
while the client was in `Testing`. If the 7-day expiry reached a Play Services grant, it should not
have been. **This supports ticket 02's agent over ticket 01's**, on the one point where they
contradicted each other.

It is not conclusive: an entry in the account's third-party list proves consent was given, not that
the grant still mints tokens, and nobody exercised a sync against it on day 8. **The experiment is
now unrepeatable** - publishing removed the condition. Left as an open, unresolved disagreement with
evidence leaning one way, because the map no longer depends on which agent was right.
