---
map: dev-aspect
kind: research
title: "The Azure DevOps REST API: auth, WIQL, scopes, limits"
for-ticket: "03"
researched: 2026-09-01
api-version: "7.1"
tags: [research]
---
# The Azure DevOps REST API - verified against learn.microsoft.com

Answers ticket `03-azure-devops-api-research.md`. Every claim below links the Microsoft page it came
from. Anything not confirmed from a primary Microsoft page is tagged `reasoned` or `unverified` and
listed in section 4. Docs read at api-version **7.1** unless stated.

---

## 1. THE FIELDS QUESTION

### Answer: YES - with three caveats that are load-bearing, and one of them is a trap.

The batch call takes a `fields` allowlist and the response contains **only** the fields named. This
is documented, and there is an official sample request AND response proving it.

**Exact request shape (POST, preferred - no URL length ceiling on the id list):**

```http
POST https://dev.azure.com/{organization}/{project}/_apis/wit/workitemsbatch?api-version=7.1
Authorization: Basic {base64(":" + PAT)}
Content-Type: application/json

{
  "ids": [297, 299, 300],
  "fields": [
    "System.Id",
    "System.Title",
    "System.State",
    "System.AssignedTo",
    "System.WorkItemType",
    "System.TeamProject",
    "System.CreatedDate",
    "System.ChangedDate"
  ],
  "errorPolicy": "omit"
}
```

The `WorkItemBatchGetRequest` body schema is documented verbatim as:

> | `$expand` | WorkItemExpand | The expand parameters for work item attributes. Possible options are { None, Relations, Fields, Links, All } |
> | `asOf` | string (date-time) | AsOf UTC date time string |
> | `errorPolicy` | WorkItemErrorPolicy | The flag to control error policy in a bulk get work items request. Possible options are {Fail, Omit}. |
> | **`fields` | string[] | The requested fields |**
> | `ids` | integer[] (int32) | The requested work item ids |

Source: [Work Items - Get Work Items Batch (7.1)](https://learn.microsoft.com/en-us/rest/api/azure/devops/wit/work-items/get-work-items-batch?view=azure-devops-rest-7.1)

**The proof is in the doc's own paired sample.** Microsoft's example "Get list of work items for
specific fields" sends `"fields": ["System.Id","System.Title","System.WorkItemType","Microsoft.VSTS.Scheduling.RemainingWork"]`
and the documented 200 response is:

```json
{
  "count": 3,
  "value": [
    {
      "id": 297, "rev": 1,
      "fields": {
        "System.Id": 297,
        "System.WorkItemType": "Product Backlog Item",
        "System.Title": "Customer can sign in using their Microsoft Account"
      },
      "url": "https://dev.azure.com/fabrikam/_apis/wit/workItems/297"
    }, ...
  ]
}
```

No `System.Description`. Compare the SAME doc's default sample (`GET .../workitems?ids=297,299,300`
with no `fields`), whose response DOES include
`"System.Description": "Our authorization logic needs to allow for users with Microsoft accounts..."`.
Source: [Work Items - List (7.1)](https://learn.microsoft.com/en-us/rest/api/azure/devops/wit/work-items/list?view=azure-devops-rest-7.1)

So: **omitting `fields` returns descriptions. Supplying `fields` does not.** The GET form is
identical: `fields | query | | string (array (string)) | Comma-separated list of requested fields`.

### CAVEAT 1 - THE TRAP. `System.History` IS the comment thread, and it is a field.

Say it loudly: **there is no separate "Discussion" or "Comments" field in the field model.** Every
comment anyone types in the Discussion box is appended to `System.History`, and `System.History` is
a perfectly ordinary field name you could put in a `fields` array and get colleagues' prose back.

> "**Note:** There is no separate **Discussion** field. To find comments added in the Discussion
> area, filter on the **History** field—all text entered into the Discussion box is appended to
> History."

> "History — Record of changes appended after creation. ... **History queries return items whose
> Discussion or Description fields contain the search terms.**"
> `Reference name=System.History, Data type=History`

Source: [Query work items by history](https://learn.microsoft.com/en-us/azure/devops/boards/queries/history-and-auditing?view=azure-devops)

Implication for ticket 02's titles-only option: the option EXISTS, but its enforcement is a
**hardcoded allowlist constant** that must never contain `System.History`, `System.Description`,
`Microsoft.VSTS.TCM.ReproSteps`, `Microsoft.VSTS.Common.AcceptanceCriteria`, or any custom
long-text field. A denylist would be wrong - a custom process can define 1024 fields per work item
type ([object limits](https://learn.microsoft.com/en-us/azure/devops/organizations/settings/work/object-limits?view=azure-devops)),
and nobody in this repo can enumerate the employer's custom fields.

### CAVEAT 2. `$expand` overrides the intent, and its interaction with `fields` is undocumented.

`$expand=all` is a documented sample (`GET .../workitems?ids=297,299,300&$expand=all&api-version=7.1`)
and returns everything. **The docs nowhere state what happens when `fields` and `$expand` are both
supplied.** `unverified`. The build must simply never send `$expand` (default is `none`, documented
as "Default behavior").

### CAVEAT 3. `System.AssignedTo` is not a string. It is a full identity object with an email.

The documented response shape for identity fields is an `IdentityRef` containing `displayName`,
`uniqueName` (`"fabrikamfiber4@hotmail.com"` in the sample), `id`, `descriptor`, `imageUrl`, and an
avatar `_links` href. Asking for `System.AssignedTo` on items NOT assigned to Kevin therefore
persists colleagues' names and work email addresses. That is a ticket-02 decision (its Decide item
4), not an API limitation - flagging it because "titles and status only" sounds cheaper than it is.

### And the good news the ticket did not ask for

**Comments never come back from the work-items endpoints at all.** They live on a wholly separate
route that must be called deliberately:
`GET https://dev.azure.com/{organization}/{project}/_apis/wit/workItems/{workItemId}/comments?api-version=7.1-preview.4`
Source: [Comments - Get Comments](https://learn.microsoft.com/en-us/rest/api/azure/devops/wit/comments/get-comments?view=azure-devops-rest-7.1).
Not calling it is sufficient. (Still preview-only even at 7.1.)

**And the WIQL SELECT list leaks nothing**, which is worth knowing because it looks like it should:

> "**Important:** WIQL syntax is used to execute the Query By Wiql REST API. **The API only returns
> work item IDs, regardless of which fields you include in the `SELECT` statement.**"

Source: [WIQL syntax reference](https://learn.microsoft.com/en-us/azure/devops/boards/queries/wiql-syntax?view=azure-devops)

**Verdict for ticket 02: the titles-only option exists and is buildable. It is not free.** It is an
allowlist constant plus a never-send-`$expand` rule plus a ruling on whether other people's
identities ride along in `System.AssignedTo`.

---

## 2. Confirmed claims table

Every row was read in a Microsoft doc (`traced`) unless the Verdict column says otherwise.

| # | Claim from ticket 03 | Verdict | Evidence |
|---|---|---|---|
| 1 | Base is `https://dev.azure.com/{organization}` | **Confirmed** | Every 7.1 reference page uses this host form. [Projects - List](https://learn.microsoft.com/en-us/rest/api/azure/devops/core/projects/list?view=azure-devops-rest-7.1) |
| 2 | `api-version=7.1` is a REQUIRED query parameter | **Confirmed** | `api-version \| query \| True \| string \| Version of the API to use. This should be set to '7.1' to use this version of the api.` - on every 7.1 operation page |
| 3 | Auth is PAT over Basic with an EMPTY username | **Confirmed** | `curl -u :{PAT} https://dev.azure.com/{organization}/_apis/build-release/builds` and `Authorization: Basic BASE64_USERNAME_PAT_STRING`. [Use PATs](https://learn.microsoft.com/en-us/azure/devops/organizations/accounts/use-personal-access-tokens-to-authenticate?view=azure-devops) |
| 4 | WIQL is `POST {org}/{project}/_apis/wit/wiql` | **Confirmed** | `POST https://dev.azure.com/{organization}/{project}/{team}/_apis/wit/wiql?api-version=7.1`; `{project}` and `{team}` both optional. [Query By Wiql](https://learn.microsoft.com/en-us/rest/api/azure/devops/wit/wiql/query-by-wiql?view=azure-devops-rest-7.1) |
| 5 | WIQL returns only ids | **Confirmed, emphatically** | "The API only returns work item IDs, regardless of which fields you include in the `SELECT` statement." Response `workItems` is `WorkItemReference[]` = `{id, url}`. [WIQL syntax](https://learn.microsoft.com/en-us/azure/devops/boards/queries/wiql-syntax?view=azure-devops) |
| 6 | Second call `_apis/wit/workitems?ids=...&fields=...` fetches fields | **Confirmed** | See section 1. GET form documented; POST `workitemsbatch` is the same contract with a JSON body |
| 7 | `fields` can restrict the response to titles and state | **Confirmed, with the three caveats in section 1** | See section 1 |
| 8 | `vso.work` is the minimum for work items | **Confirmed** | Scopes header on both wiql and workitemsbatch pages reads `vso.work` only. UI name "Work items (read)". [OAuth scopes](https://learn.microsoft.com/en-us/azure/devops/integrate/get-started/authentication/oauth?view=azure-devops) |
| 9 | `vso.code` is the minimum for repos | **Confirmed** | Scopes header on Repositories - List reads `vso.code`. UI name "Code (read)" |
| 10 | `GET {org}/_apis/projects` lists projects | **Confirmed** | `GET https://dev.azure.com/{organization}/_apis/projects?api-version=7.1`. **Requires `vso.project` (or `vso.profile`) - NOT covered by `vso.work`.** [Projects - List](https://learn.microsoft.com/en-us/rest/api/azure/devops/core/projects/list?view=azure-devops-rest-7.1) |
| 11 | `GET {org}/_apis/git/repositories` lists repos | **Confirmed** | `GET https://dev.azure.com/{organization}/{project}/_apis/git/repositories?api-version=7.1`; `{project}` optional, and the doc's own sample is org-wide: `GET https://dev.azure.com/fabrikam/_apis/git/repositories?api-version=7.1`. [Repositories - List](https://learn.microsoft.com/en-us/rest/api/azure/devops/git/repositories/list?view=azure-devops-rest-7.1) |

**Correction to the ticket's scope claim (row 10).** `vso.work` + `vso.code` is NOT the minimum for
the shape the map wants. Listing projects needs `vso.project` ("Project and team (read)") or
`vso.profile`. Minimum PAT scope set for this feature: **Work items (read) + Code (read) + Project
and team (read)**. None of the three is marked "High privilege" in the scopes table.

---

## 3. The six "Also find out" answers

### 3.1 Rate limits, and what a throttled response looks like

Source for all of this: [Rate and usage limits](https://learn.microsoft.com/en-us/azure/devops/integrate/concepts/rate-limits?view=azure-devops)

- **The unit.** "Azure DevOps expresses resource consumption in **Azure DevOps throughput units
  (TSTUs)**." "One TSTU represents the average load generated by a typical Azure DevOps user over
  five minutes." "The global limit is **200 TSTUs within any sliding five-minute window**."
- **You cannot compute it.** "You can't calculate usage in TSTUs for an action with a formula, but
  you can see how many TSTUs an operation consumes on the usage monitoring page." And: "**Some
  operations, like work item queries, vary in consumption as your organization grows and changes**,
  so you might need to benchmark periodically." A WIQL query's cost is a function of the employer's
  org size, not of our client.
- **Two distinct failure modes, and only the second is an error.**
  - *Delayed*: "Delays range from a few milliseconds per request up to 30 seconds." "**The response
    still returns HTTP 200, so retry logic isn't required.**" A slow sync is not a broken sync.
  - *Blocked*: "the user receives responses with **HTTP code 429 (too many requests)**" with body
    `TF400733: The request has been canceled: Request was blocked due to exceeding usage of resource <resource name> in namespace <namespace ID>.`
- **The headers**, verbatim from the doc's table:

| Header | Meaning |
|---|---|
| `Retry-After` | RFC 6585 header, how long to wait before the next request. **Units: seconds.** |
| `X-RateLimit-Resource` | Service + threshold type reached. "display this string to a human, but not rely on it for computation" |
| `X-RateLimit-Delay` | How long this request was delayed. Seconds, up to 3 decimal places |
| `X-RateLimit-Limit` | Total TSTUs allowed before delays |
| `X-RateLimit-Remaining` | TSTUs remaining before delays start. **0 if already delayed or blocked** |
| `X-RateLimit-Reset` | Unix epoch time when tracked usage returns to 0 |
| `X-RateLimit-Cost` | If present, TSTUs consumed by THIS request (5 dp). "Use this value to monitor and optimize high-cost calls" |

  "Except for `X-RateLimit-Delay`, all these headers are sent before requests start getting
  delayed" - so a client can back off proactively.
- **Also:** "personal usage exceeds 200 times the consumption of a typical user within a sliding
  five-minute window" triggers delay, and "**When a user request is delayed by a significant
  amount, the user receives an email and a warning banner in the web.**" A badly-tuned poll loop
  emails Kevin (and, if he had no email, the Project Collection Administrators). That is the sync
  announcing itself to the employer. See 3.6.

### 3.2 Maximum ids per `workitems` batch call

**200.** Stated three ways in the primary docs:

- `workitemsbatch` page description: "Gets work items for a list of work item ids **(Maximum 200)**"
- `workitems` GET page description: "Returns a list of work items **(Maximum 200)**"
- and in the GET parameter table itself: `ids | query | True | string (array (int32)) | The comma-separated list of requested work item ids. **(Maximum 200 ids allowed)**.`

Sources: [Get Work Items Batch](https://learn.microsoft.com/en-us/rest/api/azure/devops/wit/work-items/get-work-items-batch?view=azure-devops-rest-7.1),
[Work Items - List](https://learn.microsoft.com/en-us/rest/api/azure/devops/wit/work-items/list?view=azure-devops-rest-7.1)

**The upstream limit that actually bites first: a WIQL query returns at most 20,000 items, and it
truncates silently.**

> | Query results | 20,000 items |
> "**Query results:** Results are truncated at 20,000 items - **no error is shown.** Refine your
> filter conditions or split the query into multiple saved queries."

Also `Query execution time | 30 seconds` (returns a timeout error), and `Query length | 32,000
characters`. Source: [Work tracking, process, and project limits](https://learn.microsoft.com/en-us/azure/devops/organizations/settings/work/object-limits?view=azure-devops)

Design note: a silent truncation at 20,000 is a §4-rule-6-shaped failure. If the sync ever counts
"open items on project X" from a query that could hit 20,000, the count is wrong and nothing says
so. Bound the WIQL (assigned-to-me, or a date window) rather than relying on it being small.

Use `errorPolicy: "omit"` on the batch: `Fail` is the other option, and a single deleted or
permission-denied id would fail the whole batch of 200.

### 3.3 PAT maximum lifetime, and can it be made non-expiring

**Short answer: no, it cannot be made non-expiring, and 90 days is a real ceiling for a
Entra-backed org even if the expiry is set longer.**

- The creation flow is explicitly time-boxed: "set your token to **automatically expire after a set
  number of days**." There is no non-expiring option described anywhere on the page.
- **The 90-day killer is not the expiry - it is sign-in recency.** "For organizations backed by
  Microsoft Entra ID, **sign in with your new PAT within 90 days or it becomes inactive.**" And in
  the FAQ: "**PAT authentication requires you to regularly sign in to Azure DevOps by using the
  full authentication flow.** Signing in once every 30 days is sufficient for many users, but you
  might need to sign in more frequently depending on your Microsoft Entra configuration."
- **A tenant admin can cap it below whatever Kevin picks.** "*Enforce maximum personal access token
  lifespan* ... Enter the number of maximum days." Recommended admin guidance on the same page:
  "enforce 30–90 day maximum lifespans."
- **The failure is total and immediate, not graceful.** The doc's own impact table: REST API calls →
  "All API requests fail; integration halts" → `401 Unauthorized` or `TF400813: Resource not
  available for anonymous access` → Timeline: "Immediate."
- Microsoft's own recommendation: rotate personal PATs every 90 days; "Create a new PAT **at least 7
  days before expiration**."
- PAT format, if the app validates before storing: "Tokens are **84 characters long**" with "a fixed
  `AZDO` signature at positions 76-80."
- Note also: "When a user is removed from Azure DevOps, the PAT is **invalidated within one hour**."
  That is the leaving-the-employer case in ticket 02's Decide item 6 - the credential dies on its
  own; the already-synced rows do not.

Source: [Use personal access tokens](https://learn.microsoft.com/en-us/azure/devops/organizations/accounts/use-personal-access-tokens-to-authenticate?view=azure-devops),
[Manage PATs with policies](https://learn.microsoft.com/en-us/azure/devops/organizations/accounts/manage-pats-with-policies-for-administrators?view=azure-devops)

**So yes: the sync silently dies, on a clock somewhere between 30 and ~90 days, and the doc says the
symptom is a 401.** Build for it: the staleness contract (ticket 07) must be able to say "the Azure
half has been dead since Tuesday" rather than reporting an empty result as an empty backlog. That is
CLAUDE.md §1's unreadable-vs-empty rule, in a new place.

**Not confirmed:** the maximum number of days the creation UI accepts (commonly reported as 1 year).
No Microsoft page read here states a numeric product maximum. `unverified` - see section 4.

### 3.4 Can WIQL be filtered to items changed since a timestamp (incremental sync)?

**Yes. `System.ChangedDate` is a DateTime field, supports `>=`, and takes an explicit UTC literal.**

Verbatim from [WIQL syntax](https://learn.microsoft.com/en-us/azure/devops/boards/queries/wiql-syntax?view=azure-devops):

> "The `=`, `<>`, `>`, `<`, `>=`, and `<=` operators work as expected. ... `System.ChangedDate >
> '01-01-25 12:00:00'` queries for all work items changed after noon of January 1, 2025."

Two literal forms, and the second is the one to use:

> "Quote (single or double quotes are supported) `DateTime` literals ... **Unless a time zone is
> specified, `DateTime` literals are in the time zone of the local computer.**"
> "Or, you can specify **ISO 8601 format, which is valid no matter the locale**":
> `AND [System.ChangedDate] >= '2025-01-18T00:00:00.0000000'`
> and, under "Date-time pattern": "The pattern specified by UTC, which follows this pattern (with Z
> appended to the date-time). `AND [System.ChangedDate] >= '1/1/2025 00:00:00Z'`"

**Always send the ISO-8601/`Z` form.** A locale-dependent literal evaluated in the server's or
client's local zone is exactly the class of bug CLAUDE.md §1 rules on for timezones.

**Do not use `@Today` or `@Me` for this.** Two documented reasons:
- "A query that uses the `@today` macro can return different result sets depending on the time zone
  in which it runs."
- "Macros like `@CurrentIteration`, `@Me`, `@Follows`, and `@TeamAreas` **work only in the web
  portal. For REST APIs, CLI, and Power BI, use direct dates and user IDs instead.**"
  ([query operators and variables](https://learn.microsoft.com/en-us/azure/devops/boards/queries/query-operators-variables?view=azure-devops))
  `@Me` is listed as web-portal-only there; the WIQL-syntax page still shows `[System.AssignedTo] =
  @Me` as valid syntax. **Contradiction between two Microsoft pages - resolve by testing, and in the
  meantime send the literal `'Name <email>'` identity string, which both pages document.**

Incremental query shape:

```sql
SELECT [System.Id] FROM WorkItems
WHERE [System.TeamProject] = 'ProjectName'
  AND [System.ChangedDate] >= '2026-09-01T00:00:00.0000000Z'
ORDER BY [System.ChangedDate] ASC
```

with `$top` on the URL (`POST .../_apis/wit/wiql?$top={$top}&api-version=7.1`, "The max number of
results to return").

**One thing incremental WIQL cannot do: report deletions.** An item deleted or moved out of scope
simply stops appearing; there is no tombstone in the result. `reasoned` from the response schema
(`workItems` is a flat `WorkItemReference[]` with no state marker). If the sync is incremental-only,
closed-and-purged items linger in Postgres forever and the assistant reports them as pending. Either
periodically re-pull the full open set and diff, or accept and label the staleness.

`ASOF` exists ("filter for work items that satisfy the specified filter conditions **as they were
defined on a specific date and time**") but is the wrong tool - it is a historical snapshot, not a
changed-since filter.

### 3.5 Can an organization disable PAT creation by policy?

**Yes. Outright, at organization level, for Entra-backed orgs. This can kill the approach.**

From [Manage PATs with policies for administrators](https://learn.microsoft.com/en-us/azure/devops/organizations/accounts/manage-pats-with-policies-for-administrators?view=azure-devops):

| Policy | Level | Default | Effect |
|---|---|---|---|
| **Restrict personal access token (PAT) creation** | Organization (PCA) | *off* | "Project Collection Administrators can **control who creates and regenerates PATs** in the organizations they manage." "**This policy is only available for Microsoft Entra-backed organizations.**" |
| Restrict global personal access token creation | Tenant (Azure DevOps Admin) | *off* | New PATs must be tied to a specific org |
| Restrict full-scoped personal access token creation | Tenant | *off* | New PATs must use a custom scope set |
| Enforce maximum personal access token lifespan | Tenant | *off* | Admin enters max days |
| Automatically revoke leaked personal access tokens | Tenant | **on** | Auto-revokes PATs found in public GitHub repos |

Details that matter:

- **Existing tokens survive the switch.** "Existing PATs continue working until the PAT's
  expiration date." So the policy would not break a running sync - it would break the next rotation,
  silently, weeks later.
- **There is an allowlist, and it is a request Kevin would have to make of an admin.** "If any
  Microsoft Entra users or groups require continued access to PATs, add them to the allowlist."
  From the PAT page: "Reach out to your **project collection administrator** to be included in an
  allow list for continued PAT creation permissions in that organization." Being on that allowlist
  is a visible, named, per-person exemption.
- **A softer variant exists that would also kill this**: "*Allow creation of PAT with packaging
  scope only*" - "users who aren't on the allowlist have access only to packaging scopes." No
  `vso.work`, no sync.
- **Even where PATs are permitted, Microsoft's posture is against them**: "**Avoid using PATs when a
  more secure authentication method is available.**" And the auth-guidance comparison table rates
  PAT as "**Highest risk of the common choices**", to be avoided when "The integration is a
  production service, shared automation".
  ([Authentication guidance](https://learn.microsoft.com/en-us/azure/devops/integrate/get-started/authentication/authentication-guidance?view=azure-devops))
  Notably, that same page's scenario table lists "Personal/ad hoc scripts → **Personal access
  tokens**", and the OAuth page says outright: "**Alternative for mobile apps**: Use personal access
  tokens for mobile application authentication" - because "Azure DevOps Services supports only the
  web server flow ... Mobile applications can't securely store secrets."

**So for an Android app, PAT is not a shortcut - it is the only documented option.** Entra OAuth is
not available to a mobile client per Microsoft's own FAQ. That removes the "just use OAuth instead"
escape hatch from ticket 02's Decide item 5.

### 3.6 Would an employer admin see this traffic, and as what?

**Yes, in one place, in detail, and it is not the audit log.**

**(a) The Usage page - this is the one.** A Project Collection Administrator can see it, and it is
explicitly for looking at other people:

> "Use this page to investigate **the usage of other users**. Usage can occur from regular web
> portal operations or **the use of command line or REST API tools**."

Filterable columns, verbatim: "**User - User agent - IP address - Time range - Service -
Application - Referrer - Command - UriStem - Status - Count - Usage (TSTUs) - Delay (s)**", with
statuses "All statuses - Normal - Delayed - Blocked".

And the doc says out loud what an admin is expected to conclude: "The **User Agent and IP address**
columns help identify the source of these commands. **Custom tools** or build service accounts might
be making numerous calls in a short time window."

Retention: "Azure DevOps displays the **last hour** of requests by default. You can select from
other increments of time." (The page does not state the maximum window. `unverified`.)
Source: [Monitor usage](https://learn.microsoft.com/en-us/azure/devops/organizations/accounts/usage-monitoring?view=azure-devops)

**So an admin who looks will see: Kevin's identity, a phone's IP, a distinctive User-Agent, the
UriStem `_apis/wit/wiql` / `_apis/wit/workitemsbatch`, on a repeating cadence.** Setting an honest
User-Agent is the right call and makes it more visible, not less. That is a fact for ticket 02, not
an argument.

**(b) The audit log will NOT show the reads.** The audit log's own definition is state-change-only:

> "An audit event is recorded whenever a user or service identity within the organization
> **changes the state of an artifact**."

The Areas table has no work-item-read area at all (the closest, "Process events", covers *changing*
work item type definitions). No action in the entire Actions list corresponds to reading or
querying a work item. And on tokens: "**Token access events aren't currently logged.**" Sign-ins
are not captured either: "Azure DevOps doesn't track sign-in events."
Sources: [Auditing](https://learn.microsoft.com/en-us/azure/devops/organizations/audit/azure-devops-auditing?view=azure-devops),
[Auditing events list](https://learn.microsoft.com/en-us/azure/devops/organizations/audit/auditing-events?view=azure-devops)

What the audit log DOES show, and it is enough to notice: `Token.PatCreateEvent`
("Personal Access Token \"{DisplayName}\" was created"), plus `Token.PatUpdateEvent`,
`Token.PatExpiredEvent`, `Token.PatRevokeEvent`. Each entry carries actor, timestamp, IP address,
"the authentication mechanism", a correlation ID and user agent. Retention 90 days; requires
auditing to be enabled (off by default) and an Entra-backed org.

Related, from the PAT page's admin walkthrough: an admin can list **All PATs** across all users and
is told to look for "Long-lived tokens", "Full-scoped tokens", and "**Tokens created outside
business hours**". A PAT named for a personal phone app, created at 11pm, is exactly what that
walkthrough tells an admin to flag.

**(c) The accidental broadcast.** Per 3.1, a delayed request emails the user and shows a banner. A
poorly-throttled sync announces itself.

**Nothing here is a policy opinion.** Ticket 02 owns whether any of it is acceptable.

---

## 4. Unconfirmed, unanswered, and contradicted

Nothing from the brief is quietly dropped. This is the full list.

| # | Item | Status |
|---|---|---|
| U1 | What happens when `fields` AND `$expand` are BOTH sent - does `$expand=all` override the allowlist? | `unverified`. No Microsoft page read states the interaction. **Mitigation is trivial and should be treated as a hard rule: never send `$expand`.** Default is `none`. |
| U2 | Maximum number of days the PAT creation UI accepts (is it 1 year?) | `unverified`. No page read states a numeric product maximum. What IS confirmed: not non-expiring, and Entra sign-in recency caps effective life near 90 days (3.3). |
| U3 | `@Me` in a WIQL query sent over REST | **Two Microsoft pages contradict each other.** [query-operators-variables](https://learn.microsoft.com/en-us/azure/devops/boards/queries/query-operators-variables?view=azure-devops): "Macros like `@CurrentIteration`, `@Me` ... work only in the web portal. For REST APIs ... use direct dates and user IDs instead." [wiql-syntax](https://learn.microsoft.com/en-us/azure/devops/boards/queries/wiql-syntax?view=azure-devops) shows `[System.AssignedTo] = @Me` with no such caveat. **Resolve by testing; until then use the literal identity string.** `@Project` is separately documented as a context-substituted macro, so it is likely fine - `reasoned`. |
| U4 | Whether a WIQL result and a `workitemsbatch` call each cost 1 TSTU-ish, i.e. how many syncs/day fit in 200 TSTU/5min | **Unanswerable from docs, by Microsoft's own statement.** "You can't calculate usage in TSTUs for an action with a formula" and work item queries "vary in consumption as your organization grows." Only `X-RateLimit-Cost` on a live response can answer it. |
| U5 | Maximum time window on the Usage page (90 days? longer?) | `unverified`. Doc states only the 1-hour default and "other increments of time". |
| U6 | Whether the employer's org has auditing enabled, PAT policies on, or an Entra backing | **Out of scope by instruction.** Not researched, not speculated. Ticket 02. |
| U7 | Whether deleted/purged work items can be detected incrementally | `reasoned` (no tombstone in `WorkItemReference[]`), not stated either way in the docs. See 3.4. |
| U8 | 7.2-preview differences | **Not materially different for anything here.** `workitemsbatch`, `wiql`, `projects/list`, `git/repositories/list` all list a 7.2 variant with the same shape; the comments endpoint is `7.1-preview.4` and preview at 7.2 as well. Not diffed field-by-field - `unverified` at that granularity. **Recommend pinning 7.1**, which is GA for every endpoint this feature needs. |

---

## 5. Things the ticket did not ask, that change the design

1. **`System.History` is the comment thread wearing a field's clothes** (section 1, caveat 1). This
   is the single finding most likely to have produced a quiet §7 violation. The exclusion belongs in
   a named constant at the fetch site, the way `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` is - an
   allowlist of field reference names, not a habit.

2. **`vso.project` is required and the ticket's scope list omits it** (section 2, row 10). Minimum
   set is Work items (read) + Code (read) + Project and team (read).

3. **A WIQL query truncates at 20,000 rows with no error** (3.2). Any count the assistant speaks
   that derives from an unbounded query can be silently wrong. Bound the query.

4. **A throttled request returns HTTP 200 with `Retry-After`, not an error** (3.1). A client that
   only branches on status code will hammer through its own throttling and escalate itself from
   delayed to 429-blocked, and email Kevin on the way.

5. **`X-RateLimit-Cost` is the only way to know what this costs the employer's org** (U4). Log it
   from the first real call; it is the empirical answer to a question the docs refuse to answer.

6. **The Usage page shows an admin the user, IP, User-Agent and UriStem of every REST call** (3.6a),
   while the audit log shows none of the reads (3.6b). The traffic is visible; it is just visible
   somewhere other than where one would look. Ticket 02 should be decided knowing that, and knowing
   that PAT creation itself IS audited by name (`Token.PatCreateEvent`).

7. **Entra OAuth is not an option for a phone app** (3.5). Microsoft's own FAQ rules out OAuth for
   mobile because it cannot hold a secret, and names PATs as the alternative. So ticket 02's Decide
   item 5 ("where does the PAT live") cannot be dodged by choosing a better auth method - there
   isn't one for this client shape.

8. **The credential dies on a 30-90 day clock and the symptom is a 401** (3.3). Ticket 07's
   staleness contract needs a distinct "cannot see" state, separate from "nothing pending" -
   CLAUDE.md §1's unreadable-vs-empty rule. Also: the PAT self-invalidates within an hour of Kevin
   being removed from the org, which partially answers ticket 02's revocation question - the
   *access* ends by itself, the *stored rows* do not.

9. **`errorPolicy` defaults matter.** Send `omit`; `fail` means one deleted or permission-denied id
   fails a batch of 200.

---

## Assumptions ledger

| Claim | Tag |
|---|---|
| `fields` on `workitemsbatch`/`workitems` restricts the response; documented sample request + response prove it | `traced` |
| Omitting `fields` returns `System.Description` | `traced` (Microsoft's own default sample) |
| All Discussion comments are appended to `System.History`, and there is no separate Discussion field | `traced` (verbatim Note) |
| Comments are a separate endpoint (`/comments`, 7.1-preview.4) and are never returned by the work-item endpoints | `traced` |
| WIQL returns only ids regardless of SELECT | `traced` (verbatim Important box) |
| `$expand=all` returns everything | `traced` (documented sample) |
| `fields` + `$expand` interaction | `unverified` - not documented; mitigate by never sending `$expand` |
| `System.AssignedTo` returns an IdentityRef containing `uniqueName` (an email address) | `traced` (documented response sample) |
| Basic auth with empty username, `curl -u :{PAT}` | `traced` |
| `api-version=7.1` required query parameter | `traced` |
| 200 ids max per batch | `traced` (stated 3x) |
| WIQL 20,000-result silent truncation; 30s execution limit; 32K query length | `traced` |
| TSTU model, 200 TSTU / 5 min, delay returns HTTP 200, block returns 429 + TF400733 | `traced` |
| Full `X-RateLimit-*` and `Retry-After` header table | `traced` |
| TSTU cost per call is not computable from docs | `traced` (Microsoft states it) |
| PAT cannot be made non-expiring; Entra 90-day sign-in recency; tenant max-lifespan policy; 401 on expiry | `traced` |
| PAT max days accepted by the UI | `unverified` |
| PAT invalidated within one hour when a user is removed | `traced` |
| Org policy can restrict PAT creation entirely (Entra-backed orgs), with allowlist and packaging-only variants | `traced` |
| OAuth unsuitable for mobile; PAT named as the alternative | `traced` |
| `System.ChangedDate >=` with an ISO-8601/`Z` literal supports incremental sync | `traced` |
| `@Me` over REST | **contradicted between two Microsoft pages** - `unverified`, resolve by testing |
| Incremental WIQL cannot report deletions | `reasoned` from the response schema; not stated in docs |
| Audit log records state changes only; no work-item-read action exists in the Actions list; "Token access events aren't currently logged" | `traced` |
| Usage page exposes User / User agent / IP / Command / UriStem / TSTU to a PCA, explicitly for investigating other users | `traced` |
| Usage page maximum time window | `unverified` |
| `vso.work` + `vso.code` + `vso.project` is the minimum scope set | `traced` (per-endpoint Scopes headers) |
| 7.1 is GA for every endpoint needed; 7.2 not diffed field-by-field | `traced` for GA status, `unverified` for the 7.2 diff |
| Employer's actual policy configuration | **not researched, out of scope** |

Researched 2026-09-01. No code written, no build run.
