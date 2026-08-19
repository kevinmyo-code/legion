---
map: hands-and-senses
title: "Map: Hands and senses"
charted: 2026-08-16
charted-by: ""
effort: "`.scratch/hands-and-senses/`"
tickets: 21
open: 9
status: open
tags: [map]
---
# Map: Hands and senses

## Destination

**LEGION stops being read-only. Decisions locked for the write surfaces (clear DTCs, home control,
place calls and send texts) and the new senses (notification listener, wrench-mode camera, Health
Connect), plus the assistant's actual voice and a configurable morning brief.**

Destination is DECISIONS, not shipped - same shape as `.scratch/android-auto/`. Each surface
graduates its own build tickets once its decisions land. Exception: Clear DTC is small enough that
its ticket carries the build spec.

**SCOPE CORRECTION (Kevin, 2026-08-16, after charting): this map is a SURVEY, and six of its
tickets are efforts in disguise.** See "Efforts in disguise" below. They stay here as the record of
what was found and why it matters; each one's first act when picked up is to chart its OWN map,
seeded from its ticket body. What remains natively on this map is the genuinely ticket-sized work.
The destination above therefore holds only for those; the six graduate rather than resolve.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v21), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything. Most of
`memory/library/` is FROZEN Midnight AI history.

**Where this map came from.** A 2026-08-16 brainstorm session that started as competitive research
(`.scratch/competitive-landscape/research/landscape.md`). The finding that framed it: LEGION's
moat is verified whole-life context, and today it almost exclusively READS. The write surfaces and
new senses below are what a JARVIS does that a dashboard does not.

> **CORRECTION (2026-08-16, from ticket 04's premise check): "almost exclusively READS" is false.**
> `AmbientListener` ships today - local Vosk transcription of cabin conversation, a periodic
> `SubAgent` pass deciding whether to react, and it **speaks unprompted**
> (`AriaForegroundService.kt:268`, `AmbientListener.kt:245-248`). An ambient sense already exists
> and is already accepted. What makes it acceptable is the pattern any future one should copy:
> **off by default**, explicit opt-in, mute as a hard **listening** gate rather than only a
> speaking gate, re-checked at reaction time, and excluded from `EpisodicTurn`. The map's framing
> overstated the gap; the write surfaces are still the real hole.

**Skills each session should consult:** `/grilling` and `/domain-modeling` for HITL tickets,
`/research` for research tickets, `/prototype` where a surface needs something to react to.

**Standing preferences for this effort (Kevin, 2026-08-16):**
- Pull-based tools always (CLAUDE.md §7). Every new domain must argue its tool-token cost; the
  count was 69 at the google-account charting and went DOWN when the notes domain landed.
- Nothing that requires a Kevin-hosted backend. External keys follow the BYO shape (KeyVault).
- Destructive or outward-facing actions (Mode 04 erase, sending a text, placing a call) require an
  explicit confirm turn. Never fire on a one-shot.
- Estimates labelled as estimates, in tool descriptions and spoken words (§4 rule 5). Wrench-mode
  vision answers and Health-Connect-derived insights are estimates.
- The mail read-through rule (google-account ticket 07) is precedent for every new sense: read,
  used, dropped; excluded from `EpisodicTurn`/`CompanionMemory`; nothing reaches the Drive backup
  unless a ticket argues otherwise.

### Settled while charting (Kevin, 2026-08-16) - binding on every ticket

| # | Decision | Consequence |
|---|---|---|
| 1 | **Home control integrates Home Assistant; LEGION never rebuilds per-device integrations.** | The Shelly garage opener stays the one hand-wired device. HA is the device layer, LEGION is the voice in front of it. |
| 2 | **Glasses are a peripheral; the phone stays the brain.** | Any glasses work is a display/mic/camera driver, never a second app. Purchase decision deferred to fog. |
| 3 | **Wrench mode ships phone-first.** Glasses raise it to hands-free later; they do not gate it. | Camera source is an implementation detail behind one vision surface. |
| 4 | **Morning brief news comes from Kevin's newsletters via the existing Gmail tool first.** RSS is a later add, in fog. | No aggregator service, no new auth. |
| 5 | **No writes to money, ever.** Restated from the brainstorm so no ticket drifts there. | Ledger stays read-only permanently; auto-pull ingests documents, it never touches accounts. |
| 6 | **Carfax, KBB, Edmunds are dead ends** (no consumer API; unverified for VINwiki). NHTSA recall/VIN decode already shipped in LEGION. | No tickets against them. Paid BYO-key lookups (CarMD, NMVTIS) sit in fog behind wrench mode. |
| 7 | **Calendar, Gmail, and the NHTSA recall checker already exist.** Asserted missing twice during the brainstorm; both times the code had them. | Every ticket greps the tree before proposing anything as new. |
| 8 | **The advisor/goals/body layers already exist too** (`advisor/` playbooks + digests, `goals/`, `meals/`, `sleep/`, `workouts/`, ledger `Categorization`/`CategoryAgent`/budgets, `YearlyWrapped`/`MonthlyRecap`). | Health Connect becomes a DATA SOURCE feeding the existing body controllers, never a new aspect. "Financial insight" is largely built - do not re-chart it. **The Health Connect half is moot as of 2026-08-16 - ticket 11 archived, no wearable.** The advisor/goals/body finding itself stands. |
| 9 | **LEGION's Drive grant is `drive.appdata` only** - a hidden folder Kevin cannot put files in. Verified 2026-08-16 in `DriveAuth.kt`. | "Use the Drive folder we already have" is FALSE for any user-visible folder. The vault rides SAF `ACTION_OPEN_DOCUMENT_TREE` (already proven for ledger, API 30+, no new scope). |
| 10 | **Searching public data on private individuals is OUT** (people-lookup/OSINT), dropped by Kevin 2026-08-16 on usefulness plus safety. | See Out of scope. Self-OSINT (breach checks on Kevin's own identifiers) and situational feeds went with it. |
| 13 | **Proactivity is a master switch plus FIVE categories - Safety, Timing, Wellbeing, Fleet, Digest** (Kevin, 2026-08-16). Two states each, no tri-state; **the master is a true kill switch and nothing is exempt, safety included.** `CrisisDetector` is unaffected because it responds to speech rather than initiating it. | Question 3 of [The proactive switch](issues/21-proactive-mode.md) is closed. Every raising ticket maps its lines onto one of the five; none invents a sixth switch or its own opt-in. The notification listener stays pull-only. |
| 12 | **A proactive layer already ships, in the wrong shape.** `ProactivePreferences` is ONE global inverted `muted` boolean (proactive is currently ON by default); `ProactiveGate.speakIfIdle` gates idle/mute/call/onboarding; callers are car-shaped ambient chatter (opener, drive monitor, arrival). Nothing is goal-aware, time-aware, or category-aware. | [The proactive switch](issues/21-proactive-mode.md) owns replacing it. No other ticket invents its own raise policy. |
| 11 | **The companion memory architecture ported from Midnight AI; the FORGETTING did not.** Verified 2026-08-16: `CompanionMemory` (importance, lastAccessedAt, embeddingVector), `MemoryConsolidator` and `ReflectionEngine` all exist; no decay function, no pruning, no retrieval scorer, and a second legacy `MemoryEntry` table. | [Memory decay](issues/20-memory-decay.md) owns building the missing half. Do not re-chart the consolidation/reflection layers - they work. |

## Decisions so far

<!-- one line per closed ticket -->

- [What does Home Assistant's local API actually offer a phone voice client?](issues/02-ha-api-research.md)
  — **REST alone suffices for a pull-based client**: get-states, call-service, even conversation,
  no persistent socket, no Doze conflict. Long-lived tokens live 10 years but carry NO scoping -
  the only narrowing lever is a non-admin HA user, and **HA's exposed-entities gate does not bind
  raw REST** (reasoned from docs' framing). Remote: Nabu Casa 6.50 USD/mo recommended, VPN
  sanctioned, auth unchanged. Assist's `conversation/process` executes without a confirm turn, so
  LEGION keeps its own tools. Cheapest hub: 0 USD container on existing hardware; Green 199 USD.
- [What does Health Connect actually expose, and on what terms?](issues/10-health-connect-research.md)
  — **all five metrics are first-class records with provenance**, read via per-type runtime grants
  through HC's own consent sheet. Foreground pull tools need NO background permission; the 30-day
  read window is real (`READ_HEALTH_DATA_HISTORY` lifts it, uninstall resets the clock). Sideload
  is fine: the Play declaration is store-review-only, no platform gate. A25 on Android 14 = HC is
  a framework module. Native aggregation API; never hand-sum raw records. Sync freshness is
  UNDOCUMENTED - "how did I sleep at 7am" needs an on-device test, and the tool must say "not
  synced yet" in words. One verify-at-build: connect-client library minSdk vs app minSdk 24.
- [What can Gemini Live video actually do for wrench mode, on Kevin's key?](issues/06-wrench-vision-research.md)
  — **camera frames work on a plain API key**: JPEG `realtimeInput` on the existing WebSocket, max
  1 fps. The constraint is session plumbing, not money: audio+video sessions die at 2 minutes
  without `contextWindowCompression` and recycle at ~10 without `sessionResumption`; both become
  mandatory. Cost is pocket change either way (30-min stream ~0.40 USD, 20 one-shots ~0.03 USD).
  The real gap is DETAIL: a Live frame is 70 tokens vs ~1,100+ for a one-shot photo - evidence
  points at a hybrid (stream for context, one-shot for "identify this"), flagged reasoned, not
  decided. Five on-device spikes named in the ticket (L10).
- [What location data can LEGION actually get, and on what terms?](issues/14-location-intel-research.md)
  — **four of six areas are keyless** (NWS alerts by point, USGS quake summary feeds, FEMA
  OpenFEMA county declarations, NIFC wildfire ArcGIS), two need a free no-card key (NASA FIRMS,
  AirNow, api.data.gov for FBI). **Traffic verdict: TomTom** - the only vendor needing no card
  ("no upfront credit card needed"), with traffic-aware ETA as the DEFAULT of its free 20K/month
  routing tier. Google now requires billing enabled, puts traffic-aware routing behind the **Pro
  SKU** (5,000 free/month then 10 USD/1,000), has **retired the 200 USD monthly credit**, and
  carries the most restrictive caching terms; HERE closed its no-card Limited plan on March 27.
  **Crime honesty verdict: "is this area safe" cannot be honestly answered from FBI CDE** -
  granularity is the reporting AGENCY's jurisdiction (the returned lat/lon is the agency's own
  address), lag is ~13 months (a live query on 2026-08-16 returned complete data only through
  07-2025), and reporting is voluntary. **Do not ship `is_area_safe`;** ship
  `get_reported_crime_history` whose description states agency-level, a year stale, voluntary, and
  that it does not answer safety. Two live flags: AirNow lists lat/lon services under a "will be
  retired in the fall of 2026" heading (ambiguous, weeks away, needs a logged-in read), and
  **Kevin's city is not recorded anywhere in this repo** (verified by three greps) - ask him or
  reverse-geocode at runtime before any local-incident feed can be chosen.
- [Does the document vault need retrieval machinery at all?](issues/16-vault-retrieval-research.md)
  — **no. No embeddings, no chunker, no index.** A 300-page vault is 77,400 tokens against a
  1,048,576-token window - it fits 13 times over, and a PDF page bills a flat 258 tokens with its
  native text layer free. Routed whole-document: **0.48 USD/month** at 5 queries/day. Full RAG
  saves 2.44 USD/YEAR in exchange for a Room migration and a driftable index. **Context caching is
  a trap** (56 USD/month kept warm, 15x worse than re-sending); take free implicit caching by
  ordering document-before-question. **PDFs go to Gemini natively, not through PdfBox** - 2.7x
  fewer tokens, reads scans (PdfBox returns empty on them), preserves torque-spec tables, cites
  page numbers, and drops Robolectric from vault tests. **The free API tier is disqualifying** for
  a folder headed toward tax returns: Google's pricing page says free-tier content improves their
  products, so the vault needs a billing-enabled key, said in words on the setup surface. SAF's
  core premise is `tested` on-device (files added after the grant ARE visible, 2026-08-02) but
  four vault-specific edges are unproven, including offline reads and Google-native docs - **a
  resume is likely a Google Doc, which is a virtual file**. Ten spikes listed; S6 and S8 can
  invalidate the recommendation and run before [the vault](issues/17-document-vault.md) builds.
- [Clear DTCs: fleet's first write to the car](issues/01-clear-dtc.md)
  — **clearing is a TRANSACTION, not a send**: snapshot, send, re-read, and only the re-read may be
  spoken. Forced by a code fact, not taste - `sendCommand` returns `""` on failure and
  `Elm327Io.exchange` returns whatever arrived on timeout without throwing, so a quiet link and a
  successful clear are the same value at that seam (android-auto ticket 13's defect). **Five
  outcomes** (`NOTHING_TO_CLEAR`, `CLEARED`, `RETURNED`, `UNVERIFIED`, `REFUSED`); the `44` ack is
  **diagnostic, never dispositive**; Mode 04 is excluded from the PID-silence counter so a failed
  clear cannot trigger a protocol reinit mid-write. **New `code_clear_events` table, Room v21 ->
  v22** - `code_events` has no update, no delete and no field that can mean "cleared", and a
  `clearedAt` column would retroactively rewrite observations that were true when made. Confirm
  turn copies `activate_garage` verbatim (`confirmed` param, gate in pure controller code), but the
  prompt is **informed** - call 1 does the snapshot, names the actual codes, and may end the
  operation without ever asking; **recited every time**, live-session only, engine-running warns
  rather than gates. **Both surfaces through one gate** (`DtcClearController`), recall-checker
  shape. **No maintenance-log row** - a clear is not work performed (`AdvisorProposalExecutor`
  precedent). Fleet must **subtract cleared codes by union rule** or the table buys nothing. Build
  spec and its verification gates are in the ticket. **Note: the tree is Room v21, not the v20
  `MEMORY.md` still claims.**

- [Write the assistant's actual voice](issues/12-assistant-identity.md)
  — **CLOSED, premise false. The voice was already written.** `AssistantIdentity.kt:8` says "No
  longer placeholder"; it is a resolver, and the register copy is `ai/Personas.kt` - ALFRED and
  DOROTHY, each a full clause plus delivery, shortClause and greetings, picked per profile in a
  shipping UI. CLAUDE.md §1/§6/§10 all said otherwise and were corrected in the same commit
  (**third** `repo-ahead-of-docs` instance). **Item 4 was never a decision:** LEGION is the app,
  the companion is user-named, `AssistantIdentity.withName` already swaps the persona's default
  name, and "Alfred/JARVIS" is a **register band, not a name**. What is actually missing is
  **freeform personality authoring**, which Kevin put on the **BACK BURNER** ("we just keep alfred
  and dorothy"). Midnight AI's builder is **ported, complete and orphaned** - `PersonaTraits`'
  `assemblePersona()` is only called by `CompanionProfile.savePersona()`, which has no production
  caller. **Do not just re-wire it:** `persona()` is dual-typed and `personaFor()` silently falls
  back to ALFRED on any unrecognised string, so freeform prose is discarded with no error. When it
  returns it **graduates to `persona-authoring`**, because the honesty rules currently live inside
  each persona's own clause and must be extracted into an immutable kernel first. Also found: **no
  tests at all** over identity, personas, or base-prompt assembly.

- [Health Connect: what does LEGION do with a body's data?](issues/11-health-connect-scope.md)
  — **ARCHIVED, not resolved.** Kevin, 2026-08-16: "i dont have a fitbit or a watch." Health
  Connect reads what other apps write, and with no wearable four of the five metrics are simply
  absent. The prize this ticket existed for - **pantry's estimated macros in versus measured energy
  out** - needs the "out" side, which is the half that requires a device. **The ticket's own
  question 6 predicted this** ("if nothing writes to Health Connect on his phone, this aspect has
  no data and the ticket may park"). [The research](issues/10-health-connect-research.md) **stays
  resolved and nothing in it was falsified** - it simply has no consumer. Steps are the one
  phone-only exception and were not pursued, because steps alone do not compute the insight.
  **Un-archives if Kevin gets a wearable.**

- [Ledger auto-pull: statements walk from the inbox into the gate](issues/09-ledger-gmail-autopull.md)
  — **KILLED.** Kevin, 2026-08-16: "my statements dont land in gmail." The pipeline's first step has
  no input; there is nothing to search for. Killed rather than archived because it does not park on
  anything Kevin is likely to change. **Nothing was wrong on the merits** - it was a correct plan
  for a mailbox he does not have. SAF Drive-folder ingestion stays the only route.
- [Voice and persona: the picker surface and the reconnect](issues/13-voice-persona-surface.md)
  — **CLOSED, already built.** Kevin: "yeah already done"; verified against the tree first. Its
  premise line "Missing: any screen that hosts them" is false, and all five questions are answered
  in shipped code: the roster + editor + audition surface exists, **`refreshIdleVoice()` decides the
  reconnect** (next line, never mid-turn, with the field-test bug named in its KDoc), a persona edit
  re-materialises **silently and immediately** for the active profile, and `updatedAt` is a caller
  concern precisely so a Drive-pulled row keeps the remote clock. Only sliver left: the silent swap
  was an implementation choice, not a taste call. Not worth a session.
- [Notification listener: the phone as a sense](issues/04-notification-listener.md)
  — **ARCHIVED.** Kevin, 2026-08-16: "we dont need notifications for now." None of its six
  questions answered. **Its premise check is the valuable artifact and is kept in the ticket:**
  LEGION **already holds full notification-read access** - `MediaNotificationListener` is a
  manifest-registered `NotificationListenerService` with an **empty body**, existing only so
  `MediaSessionManager.getActiveSessions` works (`AndroidManifest.xml:194-201`). The ticket would
  have widened an existing grant, not requested a new one. Also found: **a live defect** (nothing
  ever calls `NowPlayingController.hasAccess`, so media silently does nothing on a phone that never
  granted access - **fourth orphan in one day**); **item 4's premise was wrong** (no driving
  `Phase`; it is the conversation phase); **the map's own framing sentence is false** (see the
  correction under Notes); and two facts carried forward - **there are three proactive gates, not
  one** (to ticket 21) and **78 tool declarations today** plus the `onboardingDeclarations()`
  landmine (to any ticket adding a tool).
- **PATTERN, FIVE tickets running (2026-08-16): this map was charted from a competitive-landscape
  brainstorm, so its tickets describe what a JARVIS COULD do rather than what Kevin's data actually
  looks like.** Ticket 12 died because the thing already existed; 13 for the same reason; 11
  because the device does not exist; 09 because the data does not exist. **Every remaining ticket
  confirms its data source and greps its premise before a session is spent on it.**
  **Kevin's triage, 2026-08-16, on the survivors:** 04 notification listener "good"; 05 comms "yes";
  **08 morning brief KEEPS ITS PREMISE - "newsletters are important, dont kill"**, which confirms
  settled decision 4's Gmail assumption and by extension the source (not the scope) of
  [inbox intelligence](issues/18-inbox-intelligence.md). 18 and 19 explained to Kevin, not yet
  ruled on. **18 should still check `CalendarProvider` first** - its own question 3 predicts it
  shrinks to packages only, because flights already land in Google Calendar.

- [The proactive switch: Alfred speaks first](issues/21-proactive-mode.md)
  — **GRADUATED to `.scratch/proactive-mode/`** (Kevin, 2026-08-16: "chart it properly"). 8 tickets.
  Everything settled here carries into that map as settled input. **Its first ticket is the choke
  point**: Kevin's "master is a true kill switch, nothing exempt" ruling **cannot be honoured today**,
  because `AmbientListener` and `TelephonyController` bypass `ProactiveGate` entirely. Also verified
  while charting: **`setMuted` has ZERO callers** - the switch exists and nothing can flip it, so
  proactive is currently ON with no way to turn it off.

## Efforts in disguise

Charted as tickets, sized like maps. **A ticket is one ~100K session resolving ONE decision; these
each carry three to eight decision clusters plus a build.** Do not try to resolve one in a session.
When Kevin picks one up, session 1 charts its own map under `.scratch/<slug>/`, taking the ticket
body as raw material for the Destination and first tickets - the ticket then closes here with a
pointer to that map, recorded in Decisions so far like any other resolution.

| Ticket | Why it is an effort | Suggested slug |
|---|---|---|
| [Home control scope](issues/03-home-control-scope.md) | Hub provisioning, token custody, tool surface, danger tiers, entity exposure, Shelly migration, offline, clone-and-run. The HA research already resolved is that effort's research ticket. | `home-control` |
| [Wrench mode shape](issues/07-wrench-mode-shape.md) | Session architecture (Live vs one-shot vs hybrid), context injection, capture artifacts, entry, register - **plus five on-device spikes that must run before any of it is decidable**, and it later absorbs the glasses peripheral. | `wrench-mode` |
| [Location intelligence](issues/15-location-intelligence.md) | Five separable clusters: area-data categories, the proactive line, the departure advisor, the geofence migration, garage-on-approach. Any one is a session. | `location-intelligence` |
| [Document vault](issues/17-document-vault.md) | Storage location, retrieval, gate semantics, document classes and privacy, the filing/metadata convention, wrench-mode handoff - **plus ten spikes, two of which can invalidate the resolved research**. The largest single capability on the map. | `document-vault` |
| [Memory decay](issues/20-memory-decay.md) | Decay curve, what "fuzzy" means mechanically, the §7 anchoring tension, unforgettability, scheduling, whether embeddings were ever wired, a legacy table needing a Room decision, backup semantics. | `memory-decay` |
| [The proactive switch](issues/21-proactive-mode.md) | Trigger-engine architecture, the compulsion test, quiet hours and the nudge budget, delivery routing, Android scheduling per trigger class, register, deprecating the shipped mute. **Category shape already settled** - that decision carries into the new map as a settled input. | `proactive-mode` |

**Judged ticket-sized and staying here:** [Clear DTC](issues/01-clear-dtc.md) (**RESOLVED**
2026-08-16, build spec in the ticket, not yet built),
~~[notification listener](issues/04-notification-listener.md)~~ (**ARCHIVED** 2026-08-16), [comms](issues/05-comms.md),
~~[ledger Gmail auto-pull](issues/09-ledger-gmail-autopull.md)~~ (**KILLED** 2026-08-16),
~~[Health Connect scope](issues/11-health-connect-scope.md)~~ (**ARCHIVED** 2026-08-16, no
wearable), [assistant identity](issues/12-assistant-identity.md) (**CLOSED** 2026-08-16, premise false;
freeform authoring back-burnered and graduates to `persona-authoring` when wanted),
~~[voice and persona surface](issues/13-voice-persona-surface.md)~~ (**CLOSED** 2026-08-16,
already built),
[inbox intelligence](issues/18-inbox-intelligence.md) (and it may shrink to packages only),
[people dates](issues/19-people-dates.md).

**[Morning brief](issues/08-morning-brief.md) is the borderline one.** It is a module registry plus
config plus delivery plus composition - arguably an effort - but every module it composes is an
existing read, and its delivery question is now owned by the proactive map. Judged a ticket, on the
condition that it does NOT invent its own raise policy. Revisit if it resists one session.

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

- **Glasses purchase and peripheral protocol.** Brilliant Labs Frame/Halo shaped (open source,
  camera + mic + display) but the buy decision and the BLE/audio bridge design wait until wrench
  mode works on the phone (settled decision 3) and until [Wrench mode
  shape](issues/07-wrench-mode-shape.md) fixes what the glasses must deliver.
- **Repair-cost and buying-advisor lookups.** CarMD-style fix/cost by VIN+DTC pairs with wrench
  mode; NMVTIS title lookups are a buying-advisor use case. Both are paid BYO-key APIs whose value
  is unclear until wrench mode lands. API claims are from model knowledge, unverified.
- **RSS news module for the brief**, if newsletters prove insufficient (settled decision 4).
- **An HA hub purchase task** graduates from [Home control scope](issues/03-home-control-scope.md)
  if Kevin does not already run Home Assistant.
**Graduated 2026-08-16** into [The proactive switch](issues/21-proactive-mode.md), which now owns
the cross-cutting question: one master toggle plus per-category control, the compulsion line
written as a checkable test, quiet hours, a daily nudge cap, and which trigger class uses which
Android scheduling primitive. Every ticket that raises a line (location, brief, notifications,
health, people dates) inherits its rules rather than inventing its own.
[Location intelligence](issues/15-location-intelligence.md) still carries the sharpest single case
(an NWS warning at Kevin's location).
- **Commute patterns.** "You usually leave at 8:10" computed from logged drives -
  `DailyDriveLogController` and `TelemetryRecorder` already hold the raw material. Stats over
  falsifiable facts, so it is allowed; it waits on the departure advisor landing first.
- **Carrier tracking APIs.** UPS/FedEx/USPS developer keys, if
  [inbox intelligence](issues/18-inbox-intelligence.md) decides mail-only staleness is not enough.
- **Vault expansion beyond documents Kevin curates.** Tax returns, medical records and IDs will
  land in that folder eventually; whether LEGION treats any class differently sharpens once
  [the vault](issues/17-document-vault.md) resolves its privacy line.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **Any write to money or accounts.** Settled decision 5; it is a CLAUDE.md-level permanent rule,
  not an effort scoping call.
- **Remote start or any car-vendor cloud API.** Vendor accounts and vendor clouds, against the
  no-backend spirit.
- **Meta glasses ecosystem.** Closed SDK, Meta's cloud posture. Wrong tree per the landscape
  research.
- **Comparative or anonymized fleet data.** Permanent CLAUDE.md ban, restated.
- **Sending, replying to, or drafting mail.** Stays out, per the google-account map. Texting is
  [Comms](issues/05-comms.md); mail is not.
- **Looking up private individuals** - public professional data (LinkedIn, employer, education) as
  much as anything else. Raised and dropped by Kevin 2026-08-16: not useful enough now, and the
  safety guardrails are not worth arguing for a feature he does not need. **The narrower hazard
  that would have stayed out regardless: physical-location aggregation on a person, and
  DPPA-restricted lookups (plate-to-owner), which are illegal for a private individual.** Facts
  Kevin states about his own circle are IN, as [People dates](issues/19-people-dates.md).
- **Self-OSINT (breach checks on Kevin's own identifiers)** and **situational feeds** (ADS-B
  "what's that plane", AIS, scanner audio). Dropped with the OSINT thread. Genuinely cheap and
  genuinely JARVIS-shaped, so they return as a fresh effort if Kevin wants them.
