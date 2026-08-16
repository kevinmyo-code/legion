# Map: Hands and senses

Label: `wayfinder:map`
Effort: `.scratch/hands-and-senses/`
Charted: 2026-08-16

## Destination

**LEGION stops being read-only. Decisions locked for the write surfaces (clear DTCs, home control,
place calls and send texts) and the new senses (notification listener, wrench-mode camera, Health
Connect), plus the assistant's actual voice and a configurable morning brief.**

Destination is DECISIONS, not shipped - same shape as `.scratch/android-auto/`. Each surface
graduates its own build tickets (or its own effort, for home control) once its decisions land.
Exception: Clear DTC is small enough that its ticket carries the build spec.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v21), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything. Most of
`memory/library/` is FROZEN Midnight AI history.

**Where this map came from.** A 2026-08-16 brainstorm session that started as competitive research
(`.scratch/competitive-landscape/research/landscape.md`). The finding that framed it: LEGION's
moat is verified whole-life context, and today it almost exclusively READS. The write surfaces and
new senses below are what a JARVIS does that a dashboard does not.

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
| 8 | **The advisor/goals/body layers already exist too** (`advisor/` playbooks + digests, `goals/`, `meals/`, `sleep/`, `workouts/`, ledger `Categorization`/`CategoryAgent`/budgets, `YearlyWrapped`/`MonthlyRecap`). | Health Connect becomes a DATA SOURCE feeding the existing body controllers, never a new aspect. "Financial insight" is largely built - do not re-chart it. |
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
