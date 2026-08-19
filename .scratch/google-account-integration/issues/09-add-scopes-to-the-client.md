---
map: google-account-integration
ticket: 09
title: Add the new scopes to the registered OAuth client and prove consent on the device
type: task
status: resolved
status-detail: ""
blockers: ["02", "11"]
blocked-by: ["[[02-calendar-api-choice]]", "[[11-publish-the-consent-screen-now]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Add the new scopes to the registered OAuth client and prove consent on the device

## Answer

**`gmail.readonly` is added to the client. The console flagged "Verification required" and now shows
the scope under "Your restricted scopes - Approval required" with a warning triangle. Whether it
actually WORKS for Kevin's own account without approval is now the open question, and it is answered
by trying it, not by reading more docs.**

Done 2026-08-13 by the orchestrator driving Kevin's browser at his explicit request. Nothing was
submitted to Google.

### What was observed, first-hand

1. **Project is `midnight-ai-c7421`** and the app is named `MIDNIGHT AI`. Confirms ticket 11's
   finding: one client, reused from the archived project.
2. **Before the change**, Data Access held exactly one scope, `drive.appdata`, under
   **non-sensitive**. Sensitive: empty. **No Calendar scope had been added** despite the Calendar API
   being enabled in the project - correct, and it must stay that way (ticket 02 uses no Calendar
   OAuth).
3. ~~**The Gmail API was already enabled** in the project, so `gmail.readonly` appeared in the
   picker without any Library step.~~ **WRONG. Corrected the same day** - see
   [ticket 20](20-gmail-says-granted-but-cannot-read.md). The scope did appear in the picker without
   a Library step, and the orchestrator inferred from that the API was enabled. **A scope appearing
   in the picker does not mean its API is on.** It was disabled; every Gmail call returned 403
   `SERVICE_DISABLED` until `gmail.googleapis.com` was enabled. An inference stated as a finding.
4. **`gmail.readonly` carries the closed padlock = RESTRICTED**, seen directly in the console's own
   table. **This confirms ticket 03's central finding from the primary source.**
5. On Update, a modal: *"Verification required. A restricted scope was added. To verify your app, it
   will need to go through the verification process."* Dismissed with Continue.
6. **The scope saved.** Data Access now shows a new section, **"Your restricted scopes - Approval
   required"**, containing `.../auth/gmail.readonly` with a warning triangle, followed by an unfilled
   verification submission form ("What features will you use?", "How will the scopes be used?",
   1000-character justification).

### Deliberately NOT done

**The verification submission form was left empty and unsubmitted.** Submitting is an outward-facing
action to Google, it is not what "add the Gmail scope" asked for, and ticket 09's own text says to
stop and report if the console demands a submission. Kevin's call, not the orchestrator's.

### The open question, and how it gets answered

Ticket 01 found Google documents an exemption: verification is not required *"if you are the only
user of your app or if your app is used by only a few users, all of whom are known personally to
you"* - restricted scopes included. The console's "Approval required" label points the other way.

**These may not actually conflict**: the console describes what verification would need, while the
policy describes when verification is not needed at all. But the console is the thing that enforces.

**Do not resolve this by argument. It is one cheap empirical test:** build ticket 15, request
`gmail.readonly` on the device as the project owner, and see whether Google grants it. If it does,
the exemption holds and the map is complete. If it refuses, Gmail needs either a verification
submission (Kevin's decision) or the map drops Gmail and keeps Calendar. **Calendar is unaffected
either way** - it uses no OAuth at all.

### ANSWERED 2026-08-13: Google granted it. "Approval required" did not block consent.

Kevin ran the test on the device after ticket 15 was installed: the app requested `gmail.readonly`,
**Google presented a consent screen, he tapped Allow, and it was granted.** The Setup -> GOOGLE
screen's live probe now reports Gmail as `Granted`.

**So the console's "Approval required" banner and the docs do not conflict after all**, exactly as
the earlier reading guessed: the console describes what verification *would* require, while the
policy exempts an app whose users are all known personally to the developer. **Verification is a
gate on distributing to strangers, not on the owner using their own app.** No submission was made
and none was needed.

**Precisely what is proven, and what is not.** Proven: the **grant**. Google issued consent for a
restricted scope on an unverified, published client. **Not yet proven: that a Gmail API call
actually returns data** - a granted scope and a 200 response are different claims, and a restricted
scope on an unverified app is exactly the place a 403 could still appear at call time. **One voice
command settles it** ("what's in my inbox") and it belongs to ticket 15's deferred list, not here.

## Question

Nothing to decide. Console work plus one device run, and the map's build tickets are blocked until it
is done and seen to work.

Deliverable, once tickets 01/02/03 have fixed exactly which scopes are wanted:

1. Add the Calendar scope (if ticket 02 chooses REST over `CalendarContract`) and the Gmail scope
   from ticket 03 to the OAuth consent screen for the existing `com.kevin.legion` client.
   The Gmail scope is settled: **`gmail.readonly`** (ticket 03).
2. Confirm the consent screen's user type and publishing status. **Ticket 11 already moved this to
   Production, or found out why it could not** - read its answer first and do not redo it. Record
   what the status actually is, not what it was assumed to be.
3. Adding a restricted scope to an already-published client is the moment ticket 11's probe could
   still be contradicted. If the console now demands a verification submission, **stop and report** -
   that is a map-level problem, not a step to push through.
4. Run the app on the phone, authorize, and **confirm the consent screen lists the new scopes by
   name**. Screenshot it.
5. Make one real read through each new scope - one calendar event, one mail subject - and record
   that it returned real data. Not a compile, not a unit test. `memory/MEMORY.md` is emphatic that
   three real bugs were found by looking at the phone and none by the suite.

Record in the answer: the exact scope strings granted, the client's publishing status and user type,
the date, and anything that failed on the way. Later tickets depend on these facts.

**Kevin has to do the console half.** The agent writes the checklist and drives the device half.
