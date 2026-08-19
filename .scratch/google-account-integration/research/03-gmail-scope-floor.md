# Research: the narrowest Gmail scope that does briefing and search

Ticket: `.scratch/google-account-integration/issues/03-gmail-scope-floor.md`
Researched: 2026-08-13
Sources: Gmail API v1 REST reference + discovery document, Google OAuth scope list, Google API
Services User Data Policy, Gmail usage-limits page. All primary, all cited inline.

---

## Headline

| Question | Answer |
|---|---|
| Narrowest scope for list **and** `q` full-text search | **`gmail.readonly`**. Nothing narrower supports `q`. |
| Can `gmail.metadata` do it? | **No.** `q` is refused outright under `gmail.metadata`. Documented, not a quirk. |
| Is any of it merely *sensitive*? | **No.** Every Gmail scope that reads a real mailbox is **restricted**. There is no sensitive-tier path. Settled decision 5 is not just accepted, it is forced. |
| Useful briefing on metadata alone? | **Yes, a good one** - sender, subject, date, labels. But no search, and no date/sender filtering at all, because those operators live in `q`. |
| One briefing's quota cost | **405 units** for 20 messages (list 5 + 20x get 20). Ceiling is 6,000 units/min/user. ~14 briefings per minute. Not a constraint. |

---

## 1. Scope floor

### `gmail.metadata` forbids `q`. Verbatim from the discovery document

`users.messages.list`, parameter `q`:

> "Only return messages matching the specified query. Supports the same query format as the Gmail
> search box. For example, `\"from:someuser@example.com rfc822msgid: is:unread\"`. **Parameter cannot
> be used when accessing the api using the gmail.metadata scope.**"

- Source: <https://gmail.googleapis.com/$discovery/rest?version=v1>
- Also stated in the REST reference:
  <https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list>
  ("q ... unavailable with gmail.metadata scope")

That is dispositive. Ticket question 1's "does it permit `users.messages.list` with a `q`" is a
flat **no**.

### `gmail.metadata` also forbids bodies at the format level

`users.messages.get`, `format` enum, verbatim from the discovery document:

| Value | Description |
|---|---|
| `MINIMAL` | "Returns only email message ID and labels; does not return the email headers, body, or payload." |
| `FULL` | "Returns the full email message data with body content parsed in the `payload` field; the `raw` field is not used. **Format cannot be used when accessing the api using the gmail.metadata scope.**" |
| `RAW` | "Returns the full email message data with body content in the `raw` field as a base64url encoded string; the `payload` field is not used. **Format cannot be used when accessing the api using the gmail.metadata scope.**" |
| `METADATA` | "Returns only email message ID, labels, and email headers." |

`metadataHeaders[]`: "When given and format is `METADATA`, only include headers specified."

So under `gmail.metadata` the only usable formats are `MINIMAL` and `METADATA`. The enforcement is
**server-side at Google**, not a client convention.

### Answer to "does `gmail.readonly` become mandatory the moment a snippet or body is wanted"

Yes for a body: `FULL`/`RAW` are blocked by scope. For a **snippet** the docs are silent - `snippet`
("A short part of the message text") is a top-level `Message` field, not a `payload` field, and no
page states whether it is populated under `format=METADATA` or suppressed under the metadata scope.
Reference: <https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages>.
**This is the one open item and it needs a spike** - it decides whether a metadata-only briefing can
show a one-line preview. Assume suppressed until proven otherwise; a snippet is body text.

### Sensitivity tiers

From <https://developers.google.com/workspace/gmail/api/auth/scopes>:

**Restricted** (all of these):

| Scope | Google's description |
|---|---|
| `https://mail.google.com/` | "Read, compose, send, and permanently delete all your email from Gmail." |
| `.../auth/gmail.readonly` | "View your email messages and settings." |
| `.../auth/gmail.metadata` | "View your email message metadata such as labels and headers, but not the email body." |
| `.../auth/gmail.modify` | "Read, compose, and send emails from your Gmail account..." |
| `.../auth/gmail.compose`, `.insert`, `.settings.basic`, `.settings.sharing` | (write / settings, not wanted) |

**Sensitive**: `gmail.send`, `gmail.addons.current.message.metadata`,
`gmail.addons.current.message.readonly`.
**Not sensitive**: `gmail.labels`, `gmail.addons.current.action.compose`,
`gmail.addons.current.message.action`.

**The sensitive-tier read scopes are useless here.** The `gmail.addons.*` scopes are scoped to "when
the add-on is running" / "when you interact with the add-on" - they authorize a Google Workspace
Add-on to see the message the user currently has open inside Gmail's own UI. They are not a mailbox
API for a standalone Android app. That leaves **no sensitive-tier route to reading a mailbox**.

`gmail.metadata` being restricted is the load-bearing surprise: **choosing metadata-only buys zero
tier relief.** It buys privacy, not paperwork.

### What restricted costs, verbatim

<https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification>:

> "Every app that requests access to Google users' restricted data and has the ability to access data
> from or through a third-party server must go through a security assessment from Google-empanelled
> security assessors."
> "if your app is in the development, testing, or staging phases, verification isn't required."
> "if you are the only user of your app or if your app is used by only a few users, all of whom are
> known personally to you" - verification isn't required.

LEGION is two phones, both Kevin's. It sits squarely in that exception **as long as it never
publishes**, which map decision 5 already accepts. Ticket 01 owns the Testing-status and token-
lifetime consequences; nothing here contradicts it.

User Data Policy, restricted-scope Limited Use
(<https://developers.google.com/terms/api-services-user-data-policy>):

- Use limited to "providing or improving user-facing features that are prominent in the requesting
  application's user interface."
- "Transferring or selling user data to third parties like advertising platforms, data brokers, or
  any information resellers" is prohibited.
- "Don't allow humans to read the data, unless" user agreement / security / legal / aggregated.

**The policy text does not address sending user data to an LLM provider.** That silence is the
tie-point for ticket 07: sending mail content to Gemini on Kevin's own key is a transfer to a third
party under a plain reading, and the personal-use exception covers verification, not Limited Use.
Ticket 07 should decide this deliberately rather than inherit it.

---

## 2. What a metadata-only briefing can actually do

Available under `gmail.metadata` with `format=METADATA` and
`metadataHeaders=From,To,Subject,Date,List-Id`:

- Sender name and address, recipients, subject line, RFC date header
- `labelIds` - `INBOX`, `UNREAD`, `STARRED`, `IMPORTANT`, `CATEGORY_PERSONAL`,
  `CATEGORY_PROMOTIONS`, `CATEGORY_UPDATES`, `CATEGORY_SOCIAL`, plus user labels
- `internalDate` (epoch ms), `threadId`, `sizeEstimate`, `historyId`

That is a genuinely serviceable briefing: "four unread in Primary, one from your landlord, one from
DBS." Google's own categories do most of ticket 05's "worth reading" work for free.

**What it costs you, and this is sharper than it looks:** without `q`, `messages.list` can filter
**only** by `labelIds`. Every other selector - `newer_than:1d`, `from:`, `after:`, `is:unread`
combined with anything else, and all free-text search - is a `q` operator. So "what arrived today"
becomes: list the INBOX/UNREAD label page, `get` each one, and filter on `internalDate`
client-side. It works, it just pays quota to discard rows.

`labelIds` filtering plus client-side `internalDate` is documented behaviour, not a workaround.
Reference: users.messages.list parameters, same URL as above.

---

## 3. Quota

<https://developers.google.com/workspace/gmail/api/reference/quota>:

| Method | Units |
|---|---|
| `messages.list` | 5 |
| `messages.get` | 20 |
| `threads.list` | 10 |
| `threads.get` | 40 |
| `history.list` | 2 |
| `labels.list` | 1 |
| `getProfile` | 1 |

| Limit | Value |
|---|---|
| Per minute per project | 1,200,000 units |
| Per minute per user per project | **6,000 units** |
| Per day per project | 80,000,000 units |

**Cost is per method, not per format.** `format=METADATA` and `format=FULL` both cost 20. So the
metadata-vs-readonly choice has **zero quota consequence** - it is purely a privacy/capability
trade.

### One briefing

| Operation | Calls | Units |
|---|---|---|
| `messages.list` (label `UNREAD` + `INBOX`, maxResults 20) | 1 | 5 |
| `messages.get` x20 | 20 | 400 |
| **Total** | | **405** |

- Against 6,000/min/user: **~14 briefings per minute**, sustained. Kevin cannot hit this by hand.
- A 10-message briefing is 205 units; a 5-message one is 105.
- A search is the same shape: `list` with `q` (5) + `get` per hit (20 each).
- **Threads are the expensive path.** `threads.list` + `threads.get` is 10 + 40n. If a briefing wants
  thread context, cost roughly doubles. Prefer messages.

**Batching does not help quota.** <https://developers.google.com/workspace/gmail/api/guides/batch>:
"A set of n requests batched together counts toward your usage limit as n requests, not as one
request." Limit 100 per batch, "Sending batches larger than 50 requests is not recommended."
Batching is an HTTP round-trip optimisation only - worth using for the 20 `get` calls, but do not
count it as quota relief.

**Free-tier ceiling: none that one user can plausibly hit.** The quota page states "all standard use
of the Gmail API is available at no additional cost," and flags that charges are planned once usage
exceeds established limits later in 2026. Worth a re-check before shipping, but at 405 units a
briefing this is not a design constraint.

---

## 4. Play Services Authorization API and Gmail

<https://developer.android.com/identity/authorization> (the `developers.google.com/identity/
authorization/android` URL 301s here).

The API is scope-generic. `AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(...)))`
takes arbitrary scope strings; the doc's `DriveScopes.DRIVE_FILE` is just a string constant. Nothing
in the page limits it to Drive, and nothing carves out Gmail. Same `Identity.getAuthorizationClient
(activity).authorize(...)` flow, same `AuthorizationResult.accessToken`, same
`hasResolution()`/`pendingIntent` consent branch LEGION already runs for `drive.appdata`.

Client registration, verbatim:

> "In the Clients page, create an Android client ID for your app if you don't already have one. You
> will need to specify your app's package name and SHA-1 signature."

Web client is required only for `requestOfflineAccess(serverClientId)` - a refresh token exchanged
on a server. **LEGION has no server and must not acquire one** (CLAUDE.md §7). Stay on the
in-app access-token path; it is the same one Drive sync uses today.

Practical consequence: **no new client type, no new flow.** What is needed is adding the Gmail scope
to the existing OAuth consent screen's scope list, which is ticket 09's job. Whether the incremental
grant prompts again and how a lapse behaves is ticket 06/10.

---

## Recommendation

**Request `gmail.readonly`.** Map decision 4 settles that Alfred does briefing *and* search on
demand; search is `q`; `q` requires `gmail.readonly` or wider. `gmail.metadata` cannot deliver the
settled scope of work, and it does not even reduce the tier, so the usual "narrower is cheaper"
argument does not apply.

**The one real argument for `gmail.metadata` is ticket 07's.** Under it, message bodies are
*technically incapable* of reaching Gemini - Google refuses `FULL`/`RAW` server-side. Under
`gmail.readonly` that same guarantee becomes a rule the app must enforce on itself, and the User
Data Policy's silence on LLM transfer means nobody else is enforcing it. If Kevin wants the hard
guarantee more than he wants search, `gmail.metadata` + label filtering + client-side
`internalDate` is a coherent product, just a smaller one.

A hybrid - `gmail.metadata` now, widen later - is possible but buys nothing except the ability to
defer, since both scopes cost the same consent screen and the same restricted-tier posture.

---

## Assumptions ledger

| Claim | Tag |
|---|---|
| `q` cannot be used under `gmail.metadata` | **documented** - discovery doc + REST reference, verbatim |
| `format=FULL` and `format=RAW` are blocked under `gmail.metadata` | **documented** - discovery doc enumDescriptions, verbatim |
| `format=METADATA` returns "only email message ID, labels, and email headers" | **documented** - discovery doc, verbatim |
| Whether `snippet` is populated under `format=METADATA` / the metadata scope | **needs-a-spike** - no doc states either way; assume suppressed |
| `gmail.readonly`, `gmail.metadata`, `gmail.modify`, `mail.google.com` are all restricted tier | **documented** - Gmail auth/scopes page |
| No sensitive-tier scope can read a standalone app's mailbox (the `gmail.addons.*` ones are add-on-runtime only) | **inferred** from their descriptions ("when the add-on is running"); Google does not state the negative outright |
| Restricted scopes need a CASA security assessment, with a development/testing and a personal-use exception | **documented** - restricted-scope-verification page, verbatim |
| The Limited Use policy does not address LLM/AI transfer | **documented** (as an absence) - read the whole "Additional Requirements" section, no such clause |
| Sending mail content to Gemini is a "transfer to a third party" under a plain reading | **inferred** - a judgement about policy text, not a Google statement. Ticket 07 must rule. |
| Quota units: list 5, get 20, threads.list 10, threads.get 40 | **documented** - usage-limits page |
| 6,000 units/min/user, 1.2M/min/project, 80M/day/project | **documented** - usage-limits page |
| One 20-message briefing = 405 units; ~14/min sustained | **inferred** - arithmetic on documented per-method costs. Assumes one `get` per message and no thread expansion. |
| Quota cost is identical for `format=METADATA` and `format=FULL` | **inferred** - the table prices `messages.get` once with no format breakdown |
| Batching does not reduce quota | **documented** - batch guide, verbatim |
| Play Services `AuthorizationRequest` accepts arbitrary scope strings including Gmail | **inferred** - the API takes generic `Scope` objects and the docs carve out nothing; only Drive is shown by example |
| No Web client needed without `requestOfflineAccess` | **documented** - Android authorization page ties the web client to offline access |
| Adding Gmail needs only a consent-screen scope addition, no new client | **inferred** - follows from the two above; not verified against Kevin's actual Cloud project. Ticket 09 verifies. |
| Charges planned for Gmail API usage "later in 2026" | **documented** - usage-limits page note. Re-check before ship. |
