---
map: google-account-integration
ticket: 03
title: "What is the narrowest Gmail scope that does briefing and search?"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What is the narrowest Gmail scope that does briefing and search?

## Question

Gmail is read-only and pull-only (settled decision 4). Establish the floor:

1. Which scope is the **minimum** for (a) listing recent messages with sender, subject and date, and
   (b) full-text search via the `q` parameter? Specifically: what does `gmail.metadata` allow and
   forbid, does it permit `users.messages.list` with a `q`, and does `gmail.readonly` become
   mandatory the moment a snippet or body is wanted?
2. Are all of those **restricted** tier, or is any of them merely **sensitive**? Settled decision 5
   accepts restricted, but if a sensitive-tier scope does the job the map should know.
3. What can be read **without** message bodies ever leaving Google - i.e. how much of a useful
   briefing is possible on metadata alone?
4. Quota and cost: Gmail API quota units per call for `list`, `get` (each format), and search. What
   does one briefing cost in units? Is there a free-tier ceiling one user could plausibly hit?
5. Does Play Services' Authorization API grant Gmail scopes the same way it grants
   `drive.appdata`, or does Gmail need a different client type / flow?

Tie point: whatever this concludes about scope must be reconciled with ticket 01's answer about
Testing status, and with ticket 07's rule about what may reach Gemini.

Findings go to `.scratch/google-account-integration/research/03-gmail-scope-floor.md`.

## Answer

**`gmail.readonly`. Nothing narrower does the job, and nothing narrower is a cheaper tier.**
Full findings and citations: [research/03-gmail-scope-floor.md](../research/03-gmail-scope-floor.md).
Resolved 2026-08-13 from a research agent's report; the tags below are the agent's, carried forward
unchanged and NOT independently re-verified by the orchestrator.

1. **The floor is `gmail.readonly`** (`documented`). The Gmail discovery doc states verbatim on
   `users.messages.list`'s `q` parameter: *"Parameter cannot be used when accessing the api using the
   gmail.metadata scope."* The same doc blocks `format=FULL` and `format=RAW` under that scope.
   Server-side enforcement, not convention. Settled decision 4 puts search in scope, search **is**
   `q`, so `q` sets the floor.
2. **`gmail.metadata` is itself RESTRICTED tier** (`documented`). The load-bearing surprise: going
   metadata-only buys **zero** tier relief, only privacy. There is no sensitive-tier read route at
   all - the sensitive Gmail scopes are `gmail.addons.*`, scoped to "when the add-on is running"
   inside Gmail's own UI and useless to a standalone Android app. **Map settled decision 5 is not
   merely accepted, it is forced.**
3. **A metadata-only briefing would have been genuinely serviceable** (`documented`): From/To/
   Subject/Date headers, `labelIds` - Google's own `CATEGORY_*` labels do much of ticket 05's
   selection work for free - and `internalDate`. Its real cost is not search alone: without `q`,
   `messages.list` filters **only by label**, and `newer_than:` / `from:` / `after:` are all `q`
   operators, so "what came in today" becomes list, get, filter client-side.
4. **Quota is a non-constraint** (`documented` numbers, `inferred` arithmetic). One briefing is
   ~405 units (list 5 + 20 x get 20) against a 6,000/min/user ceiling, so ~14 briefings a minute.
   Cost is per-method, not per-format, so metadata and readonly cost **identically**. Threads are the
   expensive path (10 + 40n). Batching does not reduce quota - it is n requests, not one.
5. **Play Services grants Gmail scopes exactly like `drive.appdata`** (`documented`): scope-generic,
   no new client type, no Web client. (A Web client is only for `requestOfflineAccess`, which needs a
   server LEGION must not have.) Adding Gmail is a consent-screen scope addition - ticket 09.

**Fallback**, stated rather than buried: if Kevin later values a hard technical guarantee that no
mail body can ever leave the device over having search at all, `gmail.metadata` delivers exactly
that at the same tier. That is a ticket 07 trade, not a scope trade.

### Two things this hands onward

- **To ticket 07, sharper than charted** (`inferred`): the Google API Services User Data Policy's
  Limited Use section prohibits transferring restricted data to third parties but **says nothing
  about LLM providers**. Under `gmail.metadata` it is technically impossible for a body to reach
  Gemini. Under `gmail.readonly` that becomes a rule **only LEGION enforces**. Ticket 07 must rule
  on it deliberately and must not inherit it as settled.
- **To ticket 05, a spike** (`needs-a-spike`): whether `snippet` is populated under
  `format=METADATA` is **undocumented** either way. It decides whether a metadata-only briefing gets
  a preview line. Assume suppressed until measured. Moot if `gmail.readonly` stands.
