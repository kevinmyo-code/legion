---
map: google-account-integration
ticket: 20
title: "BUILD: Gmail reads \"Granted\" and the assistant still cannot read mail"
type: task
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# BUILD: Gmail reads "Granted" and the assistant still cannot read mail

## Answer

**The Gmail API was not enabled in the Cloud project. It was none of the three suspected causes.**

The TEST panel was built, pressed on-device 2026-08-13, and returned in one shot what hours of
argument could not:

```
TokenResult.Token -> briefing call FAILED (HTTP)
networkFailure: false
HTTP status: 403
"reason": "SERVICE_DISABLED"
"message": "Gmail API has not been used in project 103196707820 before or it is disabled"
"service": "gmail.googleapis.com"
"status": "PERMISSION_DENIED"
```

Enabling `gmail.googleapis.com` in the console (project `midnight-ai-c7421`, whose project *number*
is `103196707820` - the same project, confirmed by the console redirecting the number to the id, so
this was **not** a wrong-project repeat of the Fleet Hub incident) fixed it immediately. Re-pressing
TEST:

```
TokenResult.Token -> briefing call SUCCEEDED
query: is:unread in:inbox category:primary newer_than:2d
messages returned: 0
resultSizeEstimate: 0
```

**Zero messages is a real 200, not a failure** - Kevin had no unread primary mail in the window. The
open question was whether a call would return at all, and it does.

### The wrong inference this corrects

Earlier the same day, while adding the scope in the console, the orchestrator observed
`gmail.readonly` in the scope picker and concluded **"the Gmail API is already enabled in the
project, so it appeared without any Library step."** That was an inference presented as a finding,
and it was **false**. A scope appearing in the picker does not mean its API is enabled. That wrong
belief is what made this failure surprising, and it is recorded in
[ticket 09](09-add-scopes-to-the-client.md) too.

### What it also rules out

**The predicted 403 was the wrong 403.** [Ticket 09](09-add-scopes-to-the-client.md) flagged a
call-time refusal of a restricted scope on an unverified app as the outstanding risk. A 403 did
occur - and its reason was `SERVICE_DISABLED`, nothing to do with verification. **The
personal-use exemption still stands unchallenged**, and Gmail now works end to end on an unverified,
published client with a restricted scope.

### The lasting value

The TEST panel stays. This device filters LEGION's own logcat, so an on-screen verbatim diagnostic
is the only debugging signal available - `memory/MEMORY.md`'s standing instruction, now with a
worked example. **The four friendly spoken messages would have said "Gmail returned an error" and
told nobody anything.** Both halves are needed.

## Question

Reported by Kevin 2026-08-13, on the device, immediately after ticket 15 shipped.

**The symptom.** Setup -> GOOGLE shows **Gmail: Granted**. The assistant, asked about mail, says it
cannot access Gmail. Both cannot be right.

**What has been ruled out by reading the committed code (`f56b7b0`), not by guessing:**

- **The tools are declared.** `search_mail` and `read_mail` are both in `LiveToolbox.declarations()`
  with their verbatim ticket 05 descriptions. The model is being offered them.
- **The dispatch is correct.** `searchMail` routes `TokenResult.Token` to `GmailClient.search`,
  `NeedsConsent` to `causeForNeedsConsent(isGmailEnabled)`, and `Failed` to `causeForFailure`.
  Nothing collapses.
- **The scope really was granted.** Kevin tapped Allow on a real Google consent screen
  ([ticket 09](09-add-scopes-to-the-client.md)), and the screen's live probe agrees.

**What remains, and they are genuinely different bugs:**

1. **A 403 at call time.** The scope is granted but the API refuses a restricted scope on an
   unverified app. **This is the possibility ticket 09 explicitly left open** - "a granted scope and
   a 200 response are different claims" - and it is the one that would change the map.
2. **`tokenOrReason` behaves differently in the foreground service** than on the screen. The probe
   runs from an Activity, the tool runs from `AriaForegroundService`. If Play Services returns
   `NeedsConsent` there, the tool says "you haven't given me access" while the screen says Granted.
3. **The tool is never called at all** and the model is answering from its own assumptions.

**Logcat cannot separate them.** This device filters LEGION's own logs - `memory/MEMORY.md` records
it, and a 4000-line dump on 2026-08-13 confirmed it again: not one app line.

## The fix, which is a diagnostic before it is a repair

**Put the answer on the screen, because the screen is the only place this device will show it.**
That is `memory/MEMORY.md`'s own standing instruction for this handset, not a new idea.

1. Add a **TEST** action to the Gmail row on `ui/sync/GoogleAccessScreen.kt`. It performs a real
   `GmailAuth.tokenOrReason` then a real `GmailClient` briefing call, from the screen, and renders
   **exactly what came back**: which `TokenResult` arm, and on an HTTP failure the **status code and
   the response body**, verbatim, not a friendly paraphrase.
2. It must distinguish, visibly: no token vs token-but-HTTP-error vs success-with-a-count. A
   friendly message here would destroy the only signal.
3. This is a permanent affordance, not scaffolding. The four spoken failure messages
   ([ticket 10](10-offline-and-failure.md)) are right for Alfred mid-conversation and useless for
   debugging; this is the other half.
4. **Then fix whatever it reveals**, and record which of the three causes it was.

## Verification

On the device: press TEST, read the result, write it into this ticket's Answer. If it is a 403,
capture the response body verbatim - the reason string is what distinguishes an unverified-app
refusal from a disabled API or a wrong scope.
