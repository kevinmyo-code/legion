---
map: location-intelligence
title: "Map: Location intelligence"
charted: 2026-08-21
charted-by: ""
effort: "`.scratch/location-intelligence/`"
tickets: 0
open: 0
status: open
tags: [map]
---
# Map: Location intelligence

## Destination

**LEGION knows where Kevin is, tells him what is happening there, and speaks first when a hazard
genuinely warrants it - running on his phone.**

**This map carries the BUILD, not only the decisions** (Kevin, 2026-08-21, explicitly overriding
wayfinder's decisions-only default). It is done when a real NWS warning reaches him on the A25, not
when the last question is answered. That is a deliberate departure from how `proactive-mode` ran,
and it is why several tickets below end in "on the phone" rather than in a resolution.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v28+), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything.

**Where this came from.** `.scratch/hands-and-senses/issues/15-location-intelligence.md`, resolved
2026-08-21 with eight calls, whose own closing line was that it is a map rather than a ticket. Its
research is `.scratch/hands-and-senses/issues/14-location-intel-research.md` and the full findings
with per-claim URLs are at `.scratch/hands-and-senses/research/14-location-intel.md` - **read those
before writing any client.** Every source fact below is already established there; nothing on this
map needs re-researching what ticket 14 settled.

**Standing preferences for this effort (Kevin, 2026-08-21):**
- **Anything ready is built, not parked.** A ready ticket with no open decisions is unstarted work.
- Bring forks with real cost or taste; decide implementation without asking.
- Nothing that requires a Kevin-hosted backend. BYO keys only, in `KeyVault`.
- **Install and look.** Every claim about behaviour on the phone is owed a run on the phone.

**This map feeds the SAFETY category.** `proactive-mode` shipped five category switches; Safety and
Wellbeing and Digest have no content. The three hazard raises below are Safety's first, which means
they inherit the master kill switch, the raise history, decline suppression and the register clause
for free - and must not reinvent any of it. `ProactiveBus`, `ProactiveRaise`, `ProactiveSettings`
already exist: **zoom them, never rebuild.**

## Settled, carried in - binding on every ticket

From `hands-and-senses` ticket 15 (8 calls) and this map's charting session (8 more). None is
re-openable without Kevin.

| # | Decision | Consequence |
|---|---|---|
| 1 | **Four keyless categories ship: NWS weather, USGS quakes + NIFC fire, AirNow air quality, FEMA declarations.** | Behind ONE `area_info` tool with a category parameter, never five tools. |
| 2 | **USGS real-time GeoJSON summary feeds, never `fdsnws/event/1/query`, on any timer.** USGS steers automated clients there and they update every minute. | |
| 3 | **NIFC WFIGS over NASA FIRMS for fire.** NIFC gives incident name, size and containment; FIRMS gives thermal anomalies. | If FIRMS is ever used it is spoken as "satellite heat detection", **never** as a fire. |
| 4 | **TomTom for traffic, on request only** - the one vendor needing no card, with traffic-aware as its default tier rather than a premium SKU. | Key in `KeyVault`. **Superseded in two places by [ticket 07](issues/07-tomtom-caching.md):** the caching clause came back at zero permitted retention (decision 18), and the "per TomTom" phrasing was dropped (decision 19). Read those two rather than this one for storage and attribution. |
| 5 | **Raises: NWS warnings at Severe or Extreme only; USGS M4.5 within 150 miles; NIFC fire within 25 miles.** All Safety category. | **Watches and advisories are excluded deliberately** - they fire constantly, and a channel that cries wolf trains Kevin to ignore the one warning that matters. Thresholds are starting points, not findings. |
| 6 | **Attribution always.** "NWS has a tornado warning until 6:15pm", never "there is a tornado warning". | Baked into the tool's own output (decision 12), not left to a prompt rule. |
| 7 | **Crime ships as `get_reported_crime_history`. `is_area_safe` is NEVER built.** | Agency-level, ~13 months stale, measures reporting propensity as much as crime. **Not even "estimate" is an honest label** - CLAUDE.md §4 rule 5 at its strongest. The refusal is the feature. |
| 8 | **The departure advisor ships, polling TomTom ONLY inside a window before a located calendar event** (~90 min), stopping when the event starts or it has spoken. | A deliberate, bounded amendment to decision 4. Nothing else polls TomTom, ever. |
| 9 | **Geofences move to the OS API**, nearest-N re-registered as Kevin moves (Android caps at 100/app). | Place reminders start firing properly instead of depending on a GPS poll landing near the place. |
| 10 | **Garage-on-approach is a SPOKEN OFFER, never automatic. Silence does nothing.** | The relay is a single-button toggle that cannot report door state, so an automatic trigger on a location guess could close a door on something. **No answer is not consent** - consistent with the decline detector, where silence is neither refusal nor agreement. |
| 11 | **`ACCESS_BACKGROUND_LOCATION` is requested.** | Without it geofences do not fire when the app is closed and hazards only reach him with LEGION open - which is useless for a category about acting when he is not looking. Costs Android's two-step "Allow all the time" flow and a real battery conversation. |
| 12 | **Hazard checks every 15 minutes, and only when location changed meaningfully.** Tool output is **pre-formatted with attribution baked in**, not raw JSON. | A warning has lead time in minutes; skipping the call when he has not moved cuts most of the cost on a phone on a desk. |
| 13 | **One NWS alert speaks ONCE, ever, keyed on its stable alert id.** | An upgrade (watch becoming warning) is a NEW id and does speak, which is the wanted behaviour. Uses the raise history already built. |
| 14 | **"Here" is the live GPS fix, with NO fallback.** With no fix it says so and checks nothing. | No stored hometown - the research warned it breaks the moment he travels, and it could not determine his city anyway. |
| 15 | **The departure advisor's prep buffer is ONE global setting** Kevin can change. | Wrong for a flight versus a dentist; he overrides in the moment by ignoring it. Learned buffers need departure data that does not exist. |
| 17 | **TomTom's Evaluation licence is read as covering this app, knowingly.** Its terms license "internal evaluation and testing by you"; LEGION is a sideloaded personal build with one developer and one user, never distributed. (Kevin, 2026-08-21, on the full terms.) | An ambiguous term read deliberately, not missed. The pricing page advertises the free tier without ever calling it Evaluation Use, and how the two map onto each other **is not stated in either document**. Failure mode is a revoked key (23.1 allows termination at any time, without notice), never a bill - there is no card. |
| 18 | **NO ETA is persisted. Not to Room, not to Drive, ever.** Request-time use and immediate discard. (Established, not chosen - [ticket 07](issues/07-tomtom-caching.md).) | Routing returns `Cache-Control: no-cache` with no `max-age`, so clause 11.4's caching exception permits **zero** retention. Independently, 11.6.1 forbids building "any secondary or derived database" from Results. A second reason [decision 15](#) chose a global prep buffer over a learned one: the history a learned buffer needs cannot legally exist. |
| 19 | **Spoken ETAs carry NO attribution** (Kevin, 2026-08-21, against the recommendation). | **This narrows decision 6, which it does not otherwise touch** - NWS, USGS, NIFC, AirNow, FEMA and FBI results are still always attributed. The cost, stated once: an unattributed ETA sounds like LEGION's own claim rather than a vendor's, which is the thing decision 6 exists to prevent. The counter-argument is that TomTom's own terms cannot be satisfied by a voice surface at all - 17.3 wants the Copyright API and a **logo** - so no spoken phrasing was ever compliant. |
| 16 | **The Samsung sleeping-apps risk is NOTED, and the build proceeds anyway** (Kevin, against the recommendation). | `.scratch/proactive-mode/issues/07-scheduling-research.md`: an app unused ~3 days drops to one alarm a day and no network **while the foreground service keeps running and everything looks fine.** **The first real tornado warning is therefore also the first test of whether delivery works at all.** Accepted knowingly. |

## Decisions so far

<!-- one line per closed ticket -->

- [TomTom's caching clause, before anything is stored](issues/07-tomtom-caching.md) — **No storage
  of an ETA, for any duration**, and a licence problem bigger than the question asked. Routing emits
  `Cache-Control: no-cache` with no `max-age`, so clause 11.4's carve-out permits zero retention, and
  "Results" covers a travel-time integer exactly as it covers a polyline - there is no
  derived-number allowance of the kind Google and HERE grant. On-device is not a loophole; 11.4.1
  regulates caching "in clients" and the phone is the client. **Separately**, the free key licenses
  only "internal evaluation and testing by you" and the paid tier is scoped to apps with "Asset
  Management Functionality" licensed to end users - Kevin read the former as covering a personal
  build he alone uses (decision 17), knowing the pricing page never maps the advertised free tier
  onto either grant.

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

- **What a hazard raise actually SAYS.** The register clause covers proactive tone generally; a
  tornado warning at 3am is the flattest, most urgent thing LEGION will ever say, and
  `proactive-mode` ticket 08 deferred register-by-category rather than deciding it. This is where
  that comes due.
- **Whether the 15-minute cadence survives contact with the battery.** Decision 12 is a guess with
  a rationale, not a measurement. Revisit once it has run a full day on the A25.
- **What "location changed meaningfully" means numerically**, and which layer decides it - the
  geofence work may hand this over for free.
- **Whether the departure advisor and the sitrep should share a scheduler.** Both want a timed
  background job on a phone that Samsung may put to sleep. Two implementations of the same fragile
  thing would be a mistake, and neither is built yet.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **`is_area_safe`, in any form.** Settled decision 7. Not deferred - ruled out on honesty grounds.
- **Local police-incident feeds** (Socrata / ArcGIS). Ticket 14 documented the general pattern and
  could not determine Kevin's city. A city-specific feed is a fresh effort once there is a city.
- **Google Routes and HERE.** Both require a card on file; decision 4 chose TomTom for exactly that
  reason. Revisiting means revisiting the no-card constraint.
- **Becoming the default dialer, or any InCallService work.** Out of reach and out of scope here.
- **Home Assistant as the garage path.** `hands-and-senses` ticket 03 owns that; this map uses the
  existing Shelly path and does not care which is behind it.
