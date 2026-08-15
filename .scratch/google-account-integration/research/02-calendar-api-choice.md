# Research: `CalendarContract` or the Google Calendar REST API?

Ticket: `.scratch/google-account-integration/issues/02-calendar-api-choice.md`
Researched: 2026-08-13
Sources: Android Calendar Provider guide, AOSP `CalendarContract.java` and `CalendarProvider2.java`,
Google Calendar API v3 reference (events.list, freebusy.query, recurring events, push, quota),
Google OAuth 2.0 and sensitive-scope-verification docs. All primary, all cited inline.

---

## Headline

| Question | Answer |
|---|---|
| **Q2, the make-or-break.** Can `CalendarContract` write to a **Google** calendar and have it reach Google's servers? | **Yes, and it is the ONLY way an app is supposed to do it locally.** The provider stamps `DIRTY=1` + `MUTATORS` on every non-sync-adapter write and calls `notifyChange(..., syncToNetwork=true)`, which is exactly what hands the row to the account's sync adapter. Nothing in the provider restricts a plain app to local-only calendars - `ACCOUNT_TYPE_LOCAL` is opt-in, not a fallback. **Documented to the provider boundary; the last hop (Google's closed-source adapter uploading it) is inferred and needs a 20-minute spike.** |
| Q1, read into Today | Provider wins outright. `Instances` query over a time-range URI, local SQLite, zero network, zero quota, works offline. REST costs a round trip per panel render. |
| Q3, two-way visible | Provider wins. `ContentObserver` fires on any change, including edits pushed down from the Google Calendar app. REST's only push mechanism **requires an HTTPS server** - dead under CLAUDE.md §7. REST's fallback is polling with `syncToken`. |
| Q4, conflict awareness | Provider wins. "Busy at T" is one local `Instances` query over `[T, T+d]`. REST needs `freebusy.query`, a network call, on the live-session path. |
| Recurrence fidelity | **Tie.** Provider exposes `RRULE`/`RDATE`/`EXRULE`/`EXDATE` and real exception rows; `Instances` expands the series for you. REST has the same model. Neither forces a translation layer, so settled decision 2 holds either way. |
| Permission cost | Provider: one runtime permission pair, no consent screen, no console, no verification. REST: a **sensitive** OAuth scope, Google review before Production, unverified-app warning + user cap until then. |

**Recommendation: `CalendarContract`. No Calendar OAuth scope at all.** Section 8.

---

## 1. Q2 - the make-or-break, mechanically

### 1.1 What the provider does with an app's insert

`CalendarProvider2.insertInTransactionInner`, AOSP `main`:

```java
if (!callerIsSyncAdapter) {
    values.put(Events.DIRTY, 1);
    addMutator(values, Events.MUTATORS);
}
```

and on the way out:

```java
sendUpdateNotification(id, callerIsSyncAdapter);
// -> mContentResolver.notifyChange(CalendarContract.CONTENT_URI, null, syncToNetwork);
```

`syncToNetwork` is true precisely when the caller is **not** a sync adapter. That boolean is the
sync framework's "there are local changes, go upload them" signal.

Source: <https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/refs/heads/main/src/com/android/providers/calendar/CalendarProvider2.java>

`DIRTY`'s own javadoc in `CalendarContract.java`:

> "Used to indicate that local, unsynced, changes are present."

`MUTATORS`:

> "Used in conjunction with DIRTY to indicate what packages wrote local changes."

Source: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/CalendarContract.java>

So the provider's contract is explicit: an ordinary app writes, the provider marks the row unsynced
and names the writer, and the account's sync adapter is told to run. **This is the same code path
the AOSP Calendar app itself uses** - it is not a sync adapter either.

### 1.2 Nothing confines an app to a local-only calendar

The Android guide is explicit that local-only is a deliberate special case an app must *ask* for:

> "There is also a special type of account called `ACCOUNT_TYPE_LOCAL` for calendars not associated
> with a device account. `ACCOUNT_TYPE_LOCAL` accounts do not get synced."

> "If an application needs to create a local calendar, it can do this by performing the calendar
> insertion as a sync adapter, using an `ACCOUNT_TYPE` of `ACCOUNT_TYPE_LOCAL`."

Source: <https://developer.android.com/identity/providers/calendar-provider>

The restriction that does exist is on **`Calendars` rows**, not `Events` rows:

> "The following Calendar columns are writable by both an app and a sync adapter: `NAME`,
> `CALENDAR_DISPLAY_NAME`, `VISIBLE`, `SYNC_EVENTS`"

That is the actual shape of the app/sync-adapter split, and it is where the "apps can only write
local calendars" folklore comes from. An app cannot *create* or re-configure a Google calendar. It
can freely insert `Events` **into** one. Different table, different rule.

### 1.3 What LEGION must actually do

1. Query `Calendars` for the target row:
   - `ACCOUNT_TYPE = "com.google"` **and** `ACCOUNT_NAME = <the signed-in address>`. The guide is
     emphatic that `ACCOUNT_NAME` alone is not a key: *"a given account is only considered unique
     given both its `ACCOUNT_NAME` and its `ACCOUNT_TYPE`."*
   - `CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR (500)`. Below that the calendar is not
     writable: `CAL_ACCESS_READ = 200` is *"Can read all event details"*, `CAL_ACCESS_CONTRIBUTOR
     = 500` is *"Full access to modify the calendar"*. Values from `CalendarContract.java`.
   - Prefer `IS_PRIMARY = 1` / `OWNER_ACCOUNT == ACCOUNT_NAME` for "Kevin's own calendar".
2. Insert into `Events.CONTENT_URI` **as a plain app**, with `CALENDAR_ID`, `DTSTART`,
   `EVENT_TIMEZONE`, plus `DTEND` (non-recurring) or `DURATION` + `RRULE`/`RDATE` (recurring). All
   four requirements are stated verbatim in the guide and enforced in `validateEventData`
   (`"DTSTART field missing from event"`, `"New events must specify a calendar id"`).
3. **Do NOT append `CALLER_IS_SYNCADAPTER=true`.** There is no signature check stopping LEGION from
   doing so, and doing so is the one way to genuinely break sync: the `if (!callerIsSyncAdapter)`
   branch above is skipped, the row is never marked dirty, and it sits on the device forever
   looking correct and never uploading. This is the actual trap behind Q2, and it is avoidable by
   simply not opting in.

### 1.4 The residual risk, named

The hop the docs cannot close is Google's own sync adapter (`com.google.android.syncadapters.calendar`,
closed source). Everything up to the provider boundary is documented. That it *then* uploads a
foreign app's dirty row is inferred from: `MUTATORS` existing at all (the provider bothers to record
which package wrote a change, which only matters if foreign writes are expected to travel), the
`ACCOUNT_TYPE_LOCAL` carve-out being framed as the non-syncing exception, and every third-party
reminder app on Android relying on this path.

**Spike, ~20 minutes, on-device, do it before ticket 02 is treated as closed:**
insert one event dated tomorrow into the primary `com.google` calendar via `ContentResolver.insert`,
then open calendar.google.com in a browser. If it appears within a minute, Q2 is settled `on-device`
and this whole recommendation is safe. The same spike simultaneously answers the two device
questions below.

### 1.5 Two device preconditions the spike also checks

- **Is there a Google calendar on the provider at all?** The Google calendar sync adapter is a GMS
  component, not part of AOSP. On a stripped OEM build with no Google Calendar app installed, the
  `Calendars` query may return nothing with `ACCOUNT_TYPE = "com.google"`. The Oppo A17K is a known
  aggressive OEM build (see auto-memory: it filters LEGION's own logcat), so assume nothing.
- **`SYNC_EVENTS`.** The provider only holds calendars the user has chosen to sync. A calendar
  unchecked in the Google Calendar app is not on the device at all. Secondary/subscribed calendars
  are the likely casualty; the primary calendar is not.

Both are `needs-a-spike`. Neither is fatal to the recommendation - they change which calendars are
reachable, not whether writing works.

---

## 2. Q1 - read into Today, and what a read costs

### Provider

`Instances` is a query-only view that expands recurrence for you:

> "The `Instances` table holds the start and end time for each occurrence of an event... For
> recurring events, multiple rows are automatically generated that correspond to multiple
> occurrences of that event."

> "The instances table is not writable and only provides a way to query event occurrences."

The time range goes in the URI (`ContentUris.appendId(builder, startMillis)` then `endMillis`), and
the columns come back pre-computed: `BEGIN`, `END`, `START_DAY`/`END_DAY` (Julian, in the calendar's
timezone), `START_MINUTE`/`END_MINUTE`, `EVENT_ID`. There is also `CONTENT_BY_DAY_URI` (*"querying
an instance range by Julian Day"*) and `CONTENT_SEARCH_URI`.

The one documented cost:

> "This will cause an expansion of recurring events to fill this time range if they are not already
> expanded and will slow down for larger time ranges with many recurring events."

A deck panel window is a day or a week. Expansion is cached after the first pass. **Cost per read:
one local SQLite query, no network, no quota, no token, works in airplane mode.**

Note the shape match: `.scratch/notes-lists-calendar/issues/08-calendar-view-shape.md` already chose
an agenda over a window with series expanded into the visible range only. `Instances` *is* that
query, already implemented in the platform.

### REST

`events.list` with `timeMin`/`timeMax`, `singleEvents=true` (*"Whether to expand recurring events
into instances and only return single one-off events and instances of recurring events, but not the
underlying recurring events themselves"*), `orderBy=startTime` (which *requires* `singleEvents`),
`maxResults` default 250 / max 2500.

Cost per read: one HTTPS round trip, one access token, one quota unit against **1,000,000
requests/day per project, 10,000/min per project, 600/min per user** - so quota is not a constraint,
but latency and offline behaviour are. A panel that renders on wake would either block on the
network or need LEGION to build its own cache, which is re-implementing the calendar provider.

Source: <https://developers.google.com/workspace/calendar/api/v3/reference/events/list>,
<https://developers.google.com/workspace/calendar/api/guides/quota>

**Q1 verdict: provider, decisively.** The provider *is* a local cache of Google Calendar,
maintained by Google, that LEGION would otherwise have to write.

---

## 3. Q3 - two-way visible, and the change-notification mechanism

### Provider

Edits made in the Google Calendar app land in the same provider tables. LEGION registers a
`ContentObserver` on `CalendarContract.CONTENT_URI` (or `Instances.CONTENT_URI`) and re-queries.

Two honest caveats:
- The notification carries **no delta**. `notifyChange(CalendarContract.CONTENT_URI, null, ...)` -
  a null URI, i.e. "something changed somewhere". Every fire means a full re-query of the visible
  window. For an agenda panel that is cheap; for anything row-diffing it is not.
- **Latency is Google's sync adapter's, not LEGION's.** Google pushes a tickle to the device and the
  adapter pulls; in practice seconds, but nothing in the docs promises a bound. `inferred`.

### REST

Two options, and one of them is dead:
- **Push (`watch`)**: *"This is your webhook callback URL, and it must use HTTPS"*, and Google
  *"is able to send notifications to this HTTPS address only if there's a valid SSL certificate
  installed on your web server"*. Self-signed certificates are explicitly rejected. Channels also
  expire and must be renewed. **A phone app with no backend cannot receive push.** This is not a
  preference, it is CLAUDE.md §7's "no Kevin-hosted anything" hitting a hard requirement.
  Source: <https://developers.google.com/workspace/calendar/api/guides/push>
- **Polling with `syncToken`**: *"Token obtained from the `nextSyncToken` field returned on the last
  page of results from the previous list request. It makes the result of this list request contain
  only entries that have changed since then."* Cheap per poll, but it is still polling, on a phone,
  which is a background job LEGION would own, on a battery.

**Q3 verdict: provider.** REST's good answer needs a server LEGION is forbidden to have; its
available answer is a poll loop that duplicates work Play Services already does.

---

## 4. Q4 - cheapest "is Kevin busy at T"

| Route | Mechanism | Cost |
|---|---|---|
| Provider | `Instances` query over `[T, T+duration]`, projection `BEGIN`/`END`/`TITLE`, non-empty = busy. Filter to the calendars that count with a `CALENDAR_ID IN (...)` selection. | One local query. Offline. Synchronous, so it can sit on a live-session path without an await on the network. |
| REST | `freebusy.query` with `timeMin`/`timeMax` and `items[]`; returns `calendars[].busy[]` start/end pairs. `calendarExpansionMax` up to 50 calendars. | One network round trip, plus token. |

`freebusy` is genuinely the *right-shaped* API - it answers busy/free without handing over event
details, and there is a narrow `calendar.freebusy` scope (*"View your availability in your
calendars"*). If conflict awareness were the only want, a `calendar.freebusy`-only integration would
be a defensible, unusually narrow OAuth ask. It is not the only want: settled decision 6 asks for
read, write, and two-way as well, and `freebusy` gives none of those.

Sources: <https://developers.google.com/workspace/calendar/api/v3/reference/freebusy/query>,
<https://developers.google.com/workspace/calendar/api/auth>

**Q4 verdict: provider**, on latency and offline, not on API elegance.

---

## 5. Recurrence fidelity, and single-occurrence edits

This is what settled decision 2 ("Google owns a timed event... there is only one recurrence model -
Google's") actually hangs on. **Both routes carry the full RFC 5545 model. Neither forces a
translation layer.**

### Provider

`Events` columns, javadoc verbatim from `CalendarContract.java`:

| Column | Javadoc |
|---|---|
| `RRULE` | "The recurrence rule for the event." |
| `RDATE` | "The recurrence dates for the event." |
| `EXRULE` | "The recurrence exception rule for the event." |
| `EXDATE` | "The recurrence exception dates for the event." |
| `ORIGINAL_ID` | "The `Events._ID` of the original recurring event for which this event is an exception." |
| `ORIGINAL_SYNC_ID` | "The `_sync_id` of the original recurring event for which this event is an exception." |
| `ORIGINAL_INSTANCE_TIME` | "The original instance time of the recurring event for which this event is an exception." |
| `ORIGINAL_ALL_DAY` | "The allDay status (true or false) of the original recurring event for which this event is an exception." |

All four recurrence columns are app-writable. The one stated rule:

> "Exceptions are not allowed to recur. If `rrule` or `rdate` is not empty, `original_id` and
> `original_sync_id` must be empty."

Editing or deleting **one occurrence** has a dedicated URI: `Events.CONTENT_EXCEPTION_URI` -
*"Insertions require an appended event ID. Deletion of exceptions requires both the original event
ID and the exception event ID."* Insert an exception row (with `ORIGINAL_INSTANCE_TIME` naming the
occurrence, and `STATUS_CANCELED` to delete it rather than move it) and the provider handles the
series bookkeeping. `Instances` re-expands accordingly.

### REST

Same model, different spelling: a `recurrence` field carrying RRULE strings; `events.instances()`
to list occurrences; each instance carries `recurringEventId` (*"the ID of the parent recurring
event this instance belongs to"*) and `originalStartTime` (*"uniquely identifies the instance within
the recurring event series even if the instance was moved"*). Modify one instance with a PUT to its
edit URL, which creates an exception; cancel one by setting its `status` to `"cancelled"`. The docs
warn: *"Do not modify instances individually when you want to modify the entire recurring event"*
because it *"creates lots of exceptions that clutter the calendar."*

Source: <https://developers.google.com/workspace/calendar/api/guides/recurringevents>

### What this means for ticket 04's successor

The existing local model (`ListItem.repeat*`, `RepeatKind` = `DAILY`/`WEEKLY`/`MONTHLY_ON_DATE`/
`YEARLY`, `RepeatEndKind` = `NEVER`/`ON_DATE`/`AFTER_COUNT`) is a **strict subset** of RRULE.
Local -> RRULE is total and mechanical. **RRULE -> local is lossy** and must never be attempted: read
occurrences from `Instances`, which has already done the expansion, rather than parsing a rule back
into the local vocabulary. That preserves decision 2's "no translation layer" in the direction that
matters. `reasoned`.

Note also the collision decision 2 creates and does not yet resolve: the local model's
`.scratch/notes-lists-calendar/issues/04-*` ruling that *"a recurring item cannot be ticked"* and
*"skip a single occurrence, never move one"* was a simplification bought by owning the model. Google
does allow moving a single occurrence, and the Google Calendar app will let Kevin do it. Whatever
LEGION shows must survive an occurrence that has been moved. That is ticket 04's problem, not this
ticket's, but it is created by choosing either Google route.

---

## 6. Permission and consent cost, side by side

| | `CalendarContract` | Calendar REST |
|---|---|---|
| User-facing ask | One runtime permission dialog (`READ_CALENDAR` + `WRITE_CALENDAR`, one CALENDAR group prompt). *"`READ_CALENDAR` is required to read calendar data; `WRITE_CALENDAR` is needed to delete, insert, or update calendar data."* | OAuth consent screen, account picker, scope grant |
| Cloud console work | **None.** No client, no scope declaration, no consent screen. | Scope added to the existing Android OAuth client + consent screen |
| Verification | **None.** | **Sensitive scope.** *"reading events stored in Google Calendar"* is Google's own worked example of a sensitive scope. Requires *"Google review before any account can grant access"*, brand verification + data access verification (up to 10 days), justification, demo video. |
| Until verified | n/a | *"A user cap restricts the number of Google Accounts able to grant access to your unverified app"*, plus an *"unverified app"* warning screen, plus *"limited"* refresh token lifetime |
| Offline | Works | Fails |
| Signing-cert / clone-and-run | Unaffected. Works on a stranger's build. | Inherits the known `DEVELOPER_ERROR` (10) failure documented in `sync/DriveAuth.kt` |

Two notes on the OAuth side that cut against the ticket's framing:

- **Calendar scopes are *sensitive*, not *restricted*.** Restricted (Gmail's tier) is what triggers
  the annual third-party security assessment. Calendar does not. So adding Calendar REST would not
  make the map's settled decision 5 any worse than Gmail already makes it - decision 5 already
  concedes the security assessment. Calendar REST's marginal cost is a review, not an assessment.
  This weakens, but does not overturn, the OAuth-cost argument.
  Sources: <https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification>,
  <https://developers.google.com/workspace/calendar/api/auth>
- **The 7-day refresh-token fear that `notes-lists-calendar`'s map flagged as possibly killing this
  whole effort probably does not apply to LEGION.** The rule is real - *"A Google Cloud Platform
  project with an OAuth consent screen configured for an external user type and a publishing status
  of 'Testing' is issued a refresh token expiring in 7 days"* - but it binds refresh tokens issued
  *to the app*. `sync/DriveAuth.kt` uses the GMS Authorization API
  (`com.google.android.gms.auth.api.identity`), which by its own doc comment *"mints and refreshes a
  Drive access token client-side... stores no token; it asks Google for a fresh one when SyncEngine
  needs it."* LEGION never holds a refresh token. **Free evidence, already available: Drive first
  connected on-device 2026-08-03, which is 10 days ago. If it still authorizes silently today, the
  7-day rule is empirically not biting.** Worth confirming, because if it *is* biting, every OAuth
  route on this map is in trouble and the provider route becomes the only one standing.
  Source: <https://developers.google.com/identity/protocols/oauth2>

---

## 7. Where the provider genuinely cannot go

Stated so this is chosen with eyes open, not discovered later:

- **Calendars the device does not sync.** Unchecked-in-the-app or heavily-shared secondary
  calendars may simply be absent. REST sees everything the account can see. `inferred`.
- **A bounded history window.** The Google sync adapter keeps a limited past range on device.
  Irrelevant to a Today panel; fatal to "what did I do last April". `inferred`, `needs-a-spike` if
  it ever matters.
- **Attendees and invitations.** `Attendees` rows are writable, but whether Google's adapter turns
  them into real invitation emails is undocumented and unproven. `needs-a-spike`. None of settled
  decision 6's four jobs needs it.
- **Conference/Meet links, event colours, extended properties, ACLs.** REST-only territory.
- **No GMS at all.** A de-Googled device has no Google calendar to reach. Out of scope for a
  two-phone personal app.

None of these is one of Kevin's four wants.

---

## 8. Recommendation

**Use `CalendarContract`. Do not add a Calendar OAuth scope.**

The decisive argument is not the permission cost. It is that **three of the four wants are
latency- and offline-shaped, and REST's answer to the two-way one requires a server LEGION is
forbidden to have.** Google Play Services already runs a battery-optimised, push-driven, bidirectional
Google Calendar sync on the device, and it exposes the result through a documented local API with a
`ContentObserver`. Choosing REST means writing a second, worse copy of that sync - a poll loop, a
cache, a token refresh, an offline story - to reach the same rows that are already sitting in a
local database the app can read in a millisecond.

Concretely:

1. **Read** (`Today` panel, agenda): `Instances` over a time-range URI. Local, offline, free.
2. **Write** (Alfred creates an event): plain `ContentResolver.insert` into `Events.CONTENT_URI`
   with a `com.google` `CALENDAR_ID` at `CAL_ACCESS_CONTRIBUTOR` or better. Never with
   `CALLER_IS_SYNCADAPTER`.
3. **Two-way**: `ContentObserver` on `CalendarContract.CONTENT_URI`, re-query the window on fire.
4. **Conflict**: `Instances` over `[T, T+d]`, non-empty means busy. Synchronous, so it is usable as
   live-session context without an await.
5. **Recurrence**: write RRULE strings; read expanded occurrences from `Instances`; single-occurrence
   edit/delete through `Events.CONTENT_EXCEPTION_URI` with `ORIGINAL_INSTANCE_TIME`. Never parse an
   RRULE back into `RepeatKind`.

**This recommendation is conditional on one spike, and should not be built on until it passes**
(§1.4): insert an event into the primary `com.google` calendar on the actual device and confirm it
appears at calendar.google.com. That single test settles Q2 `on-device`, plus both device
preconditions in §1.5.

**Fallback if the spike fails**, in preference order: (a) Calendar REST with `calendar.events`, on
the same GMS Authorization client Drive already uses, accepting sensitive-scope verification, a poll
loop with `syncToken`, and no offline read; (b) hybrid - provider for read and conflict, REST for
write only - which is worse than either, because it pays the full OAuth cost for one capability and
still owns two code paths. Do not reach for the hybrid first.

**Tool-budget note** (map standing preference: 69 tools, adding domains is a real cost): the
provider route needs no new auth surface, no new settings screen, and no connection state to render
or diagnose. `sync/DriveConnectResolver.diagnose` exists because OAuth needed a way to explain
itself. `CalendarContract` needs one permission-denied string.

---

## Assumptions ledger

**documented** (quoted from a primary source above)

- Provider sets `Events.DIRTY = 1` and `MUTATORS` on every non-sync-adapter insert/update, and calls
  `notifyChange` with `syncToNetwork` true. AOSP `CalendarProvider2.java`.
- `DIRTY` means "local, unsynced, changes are present"; `MUTATORS` records which package wrote them.
- `ACCOUNT_TYPE_LOCAL` calendars "do not get synced", and creating one requires opting in as a sync
  adapter. It is an explicit special case, not the default an app is confined to.
- App-writable `Calendars` columns are exactly `NAME`, `CALENDAR_DISPLAY_NAME`, `VISIBLE`,
  `SYNC_EVENTS`. The app/sync-adapter split constrains the `Calendars` table, not `Events` rows.
- `Events` insert requires `CALENDAR_ID`, `DTSTART`, `EVENT_TIMEZONE`, plus `DTEND` (non-recurring)
  or `DURATION` + `RRULE`/`RDATE` (recurring). Enforced in `validateEventData`.
- `ACCOUNT_NAME` must always be paired with `ACCOUNT_TYPE`; an account is unique only on both.
- `CAL_ACCESS_*` integer values and meanings, including `CAL_ACCESS_CONTRIBUTOR = 500`.
- `Instances` is read-only, auto-expands recurring events, requires a time range in the URI, and
  slows on large ranges with many recurring events.
- `RRULE`/`RDATE`/`EXRULE`/`EXDATE`/`ORIGINAL_ID`/`ORIGINAL_SYNC_ID`/`ORIGINAL_INSTANCE_TIME`/
  `ORIGINAL_ALL_DAY` exist with the quoted semantics; exceptions may not themselves recur;
  `Events.CONTENT_EXCEPTION_URI` handles per-occurrence insert and delete.
- `READ_CALENDAR`/`WRITE_CALENDAR` are the required permissions and what each covers.
- REST: `events.list` `timeMin`/`timeMax`/`singleEvents`/`orderBy`/`syncToken` semantics;
  `maxResults` default 250, max 2500.
- REST quota: 1,000,000/day/project, 10,000/min/project, 600/min/user/project.
- REST push (`watch`) requires an HTTPS webhook with a valid, non-self-signed SSL certificate, and
  channels expire and need renewal.
- REST recurrence: `recurrence` field, `events.instances()`, `recurringEventId`, `originalStartTime`,
  cancel one occurrence via `status: "cancelled"`.
- `freebusy.query` shape and limits (`calendarExpansionMax` max 50, `groupExpansionMax` max 100);
  a narrow `calendar.freebusy` scope exists.
- Calendar scopes are **sensitive**: Google's own example of a sensitive scope is "reading events
  stored in Google Calendar". Sensitive means Google review, a user cap while unverified, an
  unverified-app screen, and limited refresh-token lifetime.
- Testing-status external OAuth consent screens issue refresh tokens expiring in 7 days.

**inferred** (reasoned from the documented facts, not separately verified)

- Google's closed-source calendar sync adapter uploads dirty rows written by a foreign app. Reasoned
  from `MUTATORS` existing, the `ACCOUNT_TYPE_LOCAL` carve-out, and the AOSP Calendar app using the
  same non-sync-adapter path. **This is the load-bearing inference of the whole recommendation.**
- Writing with `CALLER_IS_SYNCADAPTER=true` to a Google calendar would skip the dirty flag and
  silently never upload. Follows directly from the quoted `if (!callerIsSyncAdapter)` branch.
- Google-app edits become visible through the provider within seconds via a push tickle. No
  documented bound.
- The provider only holds calendars with `SYNC_EVENTS = 1`, so unchecked calendars are invisible.
- The Google sync adapter keeps a bounded history window on device.
- The 7-day Testing refresh-token rule does not bite LEGION, because the GMS Authorization API mints
  access tokens client-side and LEGION stores no refresh token (per `sync/DriveAuth.kt`'s own doc
  comment).
- `ListItem.repeat*` maps totally into RRULE, and RRULE does not map back without loss.

**needs-a-spike**

- **The Q2 spike (§1.4): insert into the primary `com.google` calendar on the device, confirm it
  reaches calendar.google.com.** Blocking. Nothing should be built on this recommendation first.
- Whether the Oppo A17K's `Calendars` table contains any `com.google` rows at all, and which of them
  clear `CAL_ACCESS_CONTRIBUTOR`.
- Round-trip latency for an edit made in the Google Calendar app to reach a `ContentObserver`.
- Whether Drive still authorizes silently today (free, answers the 7-day question empirically).
- Whether provider-written `Attendees` rows produce real invitations. Only if invites are ever wanted.
