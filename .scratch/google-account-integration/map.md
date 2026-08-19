---
map: google-account-integration
title: "Map: Gmail and Google Calendar"
charted: 2026-08-13
charted-by: ""
effort: "`.scratch/google-account-integration/`"
tickets: 23
open: 1
status: open
tags: [map]
---
# Map: Gmail and Google Calendar

## Destination

**A SHIPPED Gmail and Google Calendar integration: Alfred reads and searches mail on demand, and
Google Calendar becomes the calendar - LEGION reads it, writes to it, and uses it as context.**

Reached when: the ten decision tickets below are resolved, the fog has graduated into build tickets,
and the build tickets are executed and verified on the phone. **Destination is SHIPPED, not specced**
- same shape as `.scratch/cyberdeck-ui/`, unlike `.scratch/notes-lists-calendar/` which stopped at
decisions.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v11+), `com.kevin.legion`. Branch
`feat/cyberdeck`. Read `CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding
anything. Most of `memory/library/` is FROZEN Midnight AI history and carries a status banner.

**Why this map exists, and why it could not exist a week ago.** Kevin, 2026-08-13: the OAuth client
for `com.kevin.legion` + the debug SHA-1 is **registered and Drive sync has connected on the device**.
That blocker had been open since 2026-08-01 and `memory/MEMORY.md` listed it as holding three things
hostage - Drive sync, any Calendar mirror, and this map. Two are now free. **This map is the third
one collecting.** The `notes-lists-calendar` map split Gmail off as a future effort and stated it
"cannot start until the OAuth client is registered". It is registered. It starts.

**Skills each session should consult:** `/grilling` and `/domain-modeling` for the HITL tickets,
`/research` for research tickets, `/prototype` where a surface question needs something to react to.

**Standing preferences for this effort (Kevin, 2026-08-13):**
- Simple first, per the `legion-shape` map's standing preference. Still applies.
- Pull-based tools always (CLAUDE.md §7). Gmail is a tool Alfred calls, never a background poll.
- Every tool is prompt tokens on every live session, on Kevin's own key. Tool count is 69 and went
  DOWN last time a domain landed. Adding two domains' worth of tools is a real cost to argue for.
- Nothing that requires a Kevin-hosted backend. Nothing here needs one.

### Settled while charting (Kevin, 2026-08-13) - binding on every ticket

Constraints, not open questions. A ticket that contradicts one of these is wrong.

| # | Decision | Consequence |
|---|---|---|
| 1 | **The OAuth client is registered and Drive has connected on-device.** | The premise of the map. If it turns out not to hold, the map stops. `memory/MEMORY.md` still says otherwise and must be corrected. |
| 2 | **Google owns a timed event.** Google Calendar is THE calendar. | No recurrence translation layer, because there is only one recurrence model - Google's. This **reverses** `notes-lists-calendar`'s out-of-scope ruling on Calendar mirroring, and narrows its charting decision 6. Ticket 04 owns the consequences. |
| 3 | **The local list model keeps untimed items and place triggers.** | `ItemList`/`ListItem` do not go away. What happens to existing rows carrying a `startsAt`, and to `Recurrence`/`RepeatKind`/`ListItemSkip`, is ticket 04. |
| 4 | **Gmail is READ-ONLY, and PULL-ONLY.** Briefing and search on demand. | No send, no reply, no drafts, no background fetch, no proactive raise. See Out of scope. |
| 5 | **The restricted Gmail scope is accepted.** **AMENDED 2026-08-13** by ticket 01: the original wording said the app "can never be published without a Google security assessment". That conflated two things. **Publishing status is not verification.** Flipping the console to In production is free and unblocks the 7-day expiry; a security assessment belongs to *verified and publicly distributed*, which LEGION never wanted. Google documents an explicit exemption for an app whose only users are known personally to the developer, restricted scopes included. | Real cost, corrected: a one-off unverified-app interstitial per account, a 100-user lifetime cap, and no public distribution. Clone-and-run by a stranger was already dead for anything OAuth-shaped. |
| 6 | **Calendar wants all four jobs:** read into Today, write from Alfred, two-way visible, and conflict awareness as proactive context. | Breadth is settled; the API that delivers it is not. Ticket 02. |

## Decisions so far

<!-- one line per closed ticket -->

- [CalendarContract or the Calendar REST API?](issues/02-calendar-api-choice.md) — **`CalendarContract`,
  no Calendar OAuth scope at all.** The "apps can only write local calendars" folklore is wrong: AOSP
  `CalendarProvider2` flags a non-sync-adapter insert dirty and notifies with `syncToNetwork` true,
  which is the intended upload path; the write restriction is on the `Calendars` table, not `Events`.
  REST loses on two hard points - `events.watch` push needs an HTTPS webhook LEGION must never have,
  and every panel render becomes a round trip, where the provider's `Instances` URI is local, offline
  and free. Recurrence fidelity is a tie. **Calendar scopes are `sensitive`, not `restricted`** -
  a map framing error, corrected. One `needs-a-spike` residual carried into the build: prove on the
  device that a provider-inserted event really reaches Google.
- [Does the grant survive, or does it lapse every 7 days?](issues/01-testing-status-token-lifetime.md)
  — **it lapses, holding no refresh token is no defence, and the exit is free.** The 7-day rule is at
  the **grant** layer (documented), so GMS minting fresh tokens does not dodge it (inferred - Google
  never mentions Play Services either way). **Drive sync is already exposed today**, and would fail
  *silently*, because `accessTokenOrNull()` discards the reason. Internal user type is unavailable
  without a Cloud Organization; **Production is the exit and publishing is NOT verification**, with a
  documented exemption for an app used only by people known personally to the developer. **Amends
  settled decision 5.** One spike pulled forward into
  [Press Publish now, while only Drive is at stake](issues/11-publish-the-consent-screen-now.md).
- [What is the narrowest Gmail scope that does briefing and search?](issues/03-gmail-scope-floor.md)
  — **`gmail.readonly`, and there was never a cheaper tier to choose.** `q` search is forbidden under
  `gmail.metadata` (documented, server-enforced), and `gmail.metadata` is **itself restricted tier**,
  so metadata-only buys privacy and zero tier relief. No sensitive-tier read scope exists for a
  standalone app. **Settled decision 5 is forced, not merely accepted.** Quota is a non-constraint
  (~405 units a briefing against 6,000/min). Hands ticket 07 a sharper question - Google's Limited
  Use policy says nothing about LLM providers, so "no bodies to Gemini" becomes a rule only LEGION
  enforces.

- [Google owns events - what happens to the local timed items?](issues/04-what-happens-to-local-timed-items.md)
  — **an appointment and a reminder stop being the same thing.** `startsAt` now means "remind me at
  T", not "this is a calendar event"; appointments live in `CalendarContract` and nowhere else.
  **Nothing is ever written to both stores**, so there is no mirror, no migration, no cached event
  row and **no schema change at all**. Recurrence, alarms, MISSED, exact/downgrade and place triggers
  all survive untouched, applying to reminders only. **LEGION will not notify for a Google event** -
  Google already does. Named cost: Alfred must choose a store per utterance, and must say which.
  **Resolved on recommendation, not put to Kevin - a reopen**, and it re-reads a decision he made
  six days ago.
- [What is "the inbox that matters", and how does Kevin ask for mail?](issues/05-what-counts-as-worth-reading.md)
  — **two tools; the app owns the briefing query, the model owns the search query, Alfred says which
  query he ran.** Briefing is `is:unread in:inbox category:primary newer_than:2d`, cap 10 spoken -
  Google's own `CATEGORY_*` labels do the filtering free, and a model choosing what to omit is a
  model deciding what Kevin never hears. Search passes natural language to Gmail's `q` unchanged,
  guarded by disclosure rather than restriction (the notes domain's own rule).
- [What of Kevin's mail reaches Gemini, is stored, or is spoken?](issues/07-what-reaches-the-model.md)
  — **read-through only: read, used, dropped.** No Room table, so nothing can reach the
  whole-database Drive backup - the only defence that survives a feature added later. **Mail tool
  results must be excluded from `EpisodicTurn`/`CompanionMemory` explicitly**, which is the sharp
  part. Bodies only on request. **No durable memories from mail.** Proposes a new CLAUDE.md §7
  guardrail; wording not yet put to Kevin.
- [What do mail and calendar look like on the deck?](issues/08-deck-surface.md) — **no new module and
  no new screen.** Google events become a second source on the agenda that already exists; the
  `Instances` URI is the same query shape it already used. **Gmail gets no surface at all - it is
  voice-only**, because a panel is a worse Gmail and ambient awareness is the proactive path already
  ruled out. Source visible per row; red stays reserved for reminders that need Kevin.
- [Where does Google auth live, and what happens when it lapses?](issues/06-consent-surface-and-lapse.md)
  — **incremental consent, one GOOGLE row with three independent states, and the silent-failure path
  gets fixed either way.** `SyncEngine` starts recording *why* it failed. `DriveConnectResolver`
  generalises to one `GoogleGrantResolver`. Clone-and-run said out loud - and Calendar keeps working
  for a stranger, being the only part of this map with no OAuth in it. **Resolved ahead of its
  blocker**; only the lapse-cause half is contingent on ticket 11.
- [What does Alfred say when Gmail fails?](issues/10-offline-and-failure.md) — **calendar has no
  offline story to write; Gmail refuses in words, four distinct ways, and nothing is ever queued.**
  No caches anywhere. A voice-created appointment is a local provider insert Google uploads later, so
  **LEGION never writes the queue-and-push design that silently loses data.** One rule under all of
  it: never answer a mail question from anything but a successful live read.

- **VERIFICATION SWEEP 2026-08-16** (Kevin: "repo is ahead. check and close if true"). Three of the
  four open BUILD tickets were **already built** and are closed on evidence:
  [the two Gmail tools](issues/15-gmail-tools.md) (both wired in `declarations()`, briefing shape
  exact, nothing stored, four distinct failure messages),
  [read every calendar / write only writable](issues/17-read-all-calendars.md) (the
  `allGoogleCalendars` / `writableGoogleCalendars` split, correct on both sides, with on-device
  evidence already on record - the Notes stream went 10 items to 24), and
  [the calendar read tool](issues/19-calendar-read-tool.md), **whose own title was false** -
  `read_calendar` ships, is wired, reuses `eventsInWindow`, and refuses in words when the grant is
  missing. **Fourth instance of the repo being ahead of its docs** (lessons L24).
  **[The ship pass](issues/16-ship-pass.md) is genuinely NOT built** and its five remaining items
  are now listed on it; item 2 needs Kevin's wording for a CLAUDE.md §7 guardrail and gates the rest.
  **One hole found on the way past:** the mail read-through rule is enforced on the episodic path but
  **`remember` is not gated on it**, so mail content can still reach permanent memory -
  [the remember leak](issues/21-remember-leak.md).

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

**Graduated 2026-08-13 into build tickets 12-16**, the map's shipped half:
[grant plumbing](issues/12-google-grant-plumbing.md) ->
[calendar read](issues/13-calendar-read.md) ->
[calendar write](issues/14-calendar-write.md) and [Gmail tools](issues/15-gmail-tools.md) ->
[ship pass](issues/16-ship-pass.md), which is the destination gate. Both build-time verifications
listed below moved into 14 and 15 with them.

- **The conflict-awareness rule.** Kevin wants Alfred to know he is busy. What "busy" means, which
  calendars count, whether it suppresses a reminder or only colours the wording, and whether it is
  context on every live session or a tool call. Ticket 02 has now answered the cost half - the
  `Instances` URI makes "is he busy at T" a free local query - so this is waiting only on ticket 07
  and is close to ticketable.
Nothing else is left in the fog. **Every decision on this map is made; what remains is Kevin's
console work (11, 09) and the build (12-16).**
- **Whether the notes domain's local alarms survive alongside Google's own notifications.** Sharpens
  once ticket 04 lands. Today two systems would both fire for the same event.
- **Build tickets.** This map ships, so the fog past the last decision is the build. It graduates
  the way `cyberdeck-ui` did: theme/plumbing first, then surfaces, then a ship pass that is the
  destination gate.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **Gmail as an ingestion source.** Pulling bank statements and receipts out of mail attachments
  into ledger and pantry. Genuinely attractive and deliberately not here: it is a §4 reconciliation-
  gate feature, not a briefing feature, and it would drag the whole ingestion pipeline onto this map.
  Kevin was shown it while charting and did not take it.
- **Sending, replying to, or drafting mail.** Widest scope, largest blast radius, not wanted.
- **Any background or proactive Gmail fetch.** Ruled out by settled decision 4, which is the
  pull-based-tools rule applied rather than a new judgement.
- **Calendars other than the signed-in Google account's** (Outlook, CalDAV, subscribed ICS feeds).
