---
map: google-account-integration
ticket: 06
title: "Where does Google auth live in the UI, and what happens when it lapses?"
type: grilling
status: resolved
status-detail: ""
blockers: ["03", "11"]
blocked-by: ["[[03-gmail-scope-floor]]", "[[11-publish-the-consent-screen-now]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Where does Google auth live in the UI, and what happens when it lapses?

## Question

Today there is exactly one Google grant (`drive.appdata`) and one way in, added 2026-08-03 after the
first device run failed with no way to tell a `DEVELOPER_ERROR` from Kevin tapping cancel
(`ui/sync/DriveConnectResolver.diagnose`). This map adds two more scopes, one of them restricted.

1. **One consent or three?** Ask for Drive + Calendar + Gmail in a single up-front authorization, or
   incrementally at the moment a feature is first used? Incremental is the documented Google
   preference and means a Kevin who never uses Gmail never grants a restricted scope. One consent is
   one screen to get past.
2. **Where does it live?** The Setup surface has three sub-screens that were re-skinned to the deck
   on 2026-08-12. Is this one row, one screen, or per-feature affordances on the surfaces themselves?
3. **What does it say?** Plain words, including what the restricted Gmail scope actually grants.
**Handed on by [ticket 01](01-testing-status-token-lifetime.md), resolved 2026-08-13, and it is a
live defect rather than a design question.** A grant DOES expire - 7 days, while the client is in
Testing status - and the expected failure surface is `authorize` returning `Outcome.NeedsConsent`
with no exception. `DriveAuth.accessTokenOrNull()` collapses exactly that to `null`, and
`SyncEngine` swallows a null by design as "cannot sync right now". **A lapsed grant is therefore
indistinguishable from never having connected, and Drive has shipped that way since 2026-08-03.**
It is the same failure shape `DriveConnectResolver` exists to prevent, surviving in the one path
that still discards its reason. Whatever this ticket decides must fix that path, not just describe
it. Ticket 11 may remove the *cause* by moving the client to Production; it does not remove the
silent-failure *path*, which any revocation still walks.

4. **Lapse and revocation.** Ticket 11 settles whether a grant still expires on a timer. Independently,
   Kevin can revoke in his Google account at any time. Decide what each looks like: what the deck
   shows, what Alfred says mid-session when a tool call comes back unauthorized, and whether the app
   ever nags. (No compulsion mechanics - CLAUDE.md §7.)
5. **The diagnostic path.** `DriveConnectResolver` was written because a swallowed failure cost a
   day. Does it generalise to three scopes, or does each get its own? Firebase is still not wired,
   so a swallowed exception is invisible: whatever this decides has to be visible **in the UI**.
6. **Clone-and-run, stated in words.** A stranger's build has a different signing cert and gets none
   of this. Decide what the app says to that person rather than letting them hit a bare failure.

## Answer

**Incremental consent, one GOOGLE row in Setup showing three independent states, and the silent
failure path gets fixed whatever ticket 11 concludes.**

Resolved 2026-08-13 on the orchestrator's recommendation, delegated by Kevin. **Resolved ahead of
its blocker [ticket 11](11-publish-the-consent-screen-now.md)**, which is Kevin's console work.
Point 4 below is the only part contingent on it, and it is written so that either outcome lands.

1. **Incremental, at first use.** Three separate grants, never bundled: `drive.appdata` (exists),
   `READ_CALENDAR`/`WRITE_CALENDAR` (an Android runtime permission, **not** OAuth - ticket 02), and
   `gmail.readonly` (OAuth, restricted). A Kevin who never asks about mail never grants a restricted
   scope, and the up-front-everything alternative asks for the most alarming permission at the moment
   he has the least reason to trust it. Google's own guidance agrees, but the real argument is that
   these three are genuinely independent features with independent failure modes.
2. **One GOOGLE row in Setup, opening to a screen with three lines**, each showing granted / not
   granted / needs re-authorising, each with its own action. Not three scattered affordances: the
   question "what does this app have access to in my Google account" deserves exactly one place to
   read the answer. Feature surfaces may *offer* a grant in context (the agenda offering
   `READ_CALENDAR`, per ticket 08), but the authoritative status lives here.
3. **The words say what is granted, not what it is for.** "Read your Gmail. LEGION can read mail and
   search it. It cannot send, reply, or delete." "Read and add events in your Google Calendar."
   "A private folder in your Drive that only LEGION can see - not your real files."
4. **Lapse and revocation: the app must be able to say "Google needs re-authorising", and today it
   cannot.** This is the defect ticket 01 surfaced, and it is live in shipped code:
   `DriveAuth.accessTokenOrNull()` collapses a `NeedsConsent` outcome to `null`, and `SyncEngine`
   swallows a null by design as "cannot sync right now" - so a lapsed or revoked grant is
   indistinguishable from never having connected.
   - **Fix: `SyncEngine` records the reason for its last failure** where the Setup screen and the deck
     can read it. `accessTokenOrNull()` keeps its nullable shape and its graceful path; what changes
     is that the reason stops being discarded on the way past.
   - **Contingency, stated both ways.** If ticket 11 moves the client to Production, the 7-day
     expiry stops being a cause. **It does not remove the need for this fix** - Kevin can revoke in
     his Google account at any time, and that walks the same silent path. If Publish turns out to be
     gated, this fix is the difference between a weekly mystery and a weekly message.
   - **No nagging.** A status Kevin can see when he looks, and a plain sentence from Alfred if a tool
     call fails mid-conversation (ticket 10). Never a notification, never a badge, never a
     re-engagement ping - CLAUDE.md §7.
5. **Generalise the resolver.** `ui/sync/DriveConnectResolver` becomes a `GoogleGrantResolver` taking
   a grant identity plus a status code and returning the specific message, keeping its existing shape
   as a plain JVM unit with no GMS or Android types so it stays unit-testable. One resolver, three
   grants; not three resolvers drifting apart.
6. **Clone-and-run is said out loud, on the Setup screen.** A stranger's build has a different
   signing certificate and no registered OAuth client, so Drive and Gmail return
   `DEVELOPER_ERROR` (status 10) forever. The resolver already knows that code - it says so in
   words: *"This build is not registered with Google. Drive sync and Gmail will not work in a copy of
   this app built from source; you would need to register your own OAuth client."* **Calendar is
   unaffected and keeps working**, because ticket 02 chose a route with no OAuth in it at all - which
   is worth stating, because it is the only part of this map a stranger can actually run.
