---
map: hands-and-senses
ticket: 15
title: "Location intelligence: what LEGION knows about where you are, and when it speaks first"
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - 8 calls; wants its own map for the build"
blockers: ["14"]
blocked-by: ["[[14-location-intel-research]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Location intelligence: what LEGION knows about where you are, and when it speaks first

raw material; [ticket 14's findings](14-location-intel-research.md) become that map's resolved
research. The five numbered clusters below are each roughly a ticket. See map.md, "Efforts in
disguise".

## Question

What exists today: `LocationController` (permission + fast-poll mode), `PlaceController`
(`TaggedPlace` label + coords, nearest-place by raw distance math), place-triggered reminders via
`ReminderController`/`NotesController`, and `Geocoder`. What does not exist: OS geofencing, ETA,
traffic, or any area-data source. With [the source facts](14-location-intel-research.md) in hand,
decide:

1. **Which categories land.** Severe weather, earthquakes/disasters, air quality, traffic/ETA,
   crime stats, local incident feed - all, or a first cut? Each is a tool with prompt-token cost;
   can they collapse into ONE `area_info` tool with a category parameter? Write the descriptions.
2. **The proactive line, which is the real question on this ticket.** Kevin said "if I ask" for
   traffic - pull. But an NWS tornado warning at his current location is the strongest case on the
   whole map for speaking unprompted: external, falsifiable, safety-shaped, zero retention motive.
   Decide per category: which may raise, at what severity threshold, and how often. `ProactiveBus`
   + `ProactiveGate` + `ProactivePreferences` already exist - zoom them, do not rebuild. The
   compulsion ban (CLAUDE.md §7) is the constraint: a raise must serve the user, never manufacture
   a return.
3. **Attribution, always.** Every one of these is a third-party claim: "NWS has a warning until
   6pm", "FBI's 2024 city-level numbers show X". Never LEGION's own assertion. Crime is the sharp
   case - the honest answer is city-level and lagged, and Alfred must say so rather than implying
   neighborhood precision. Write the register lines.
4. **The departure advisor** ("leave now"). Chain: calendar event location -> geocode -> ETA with
   live traffic -> compare against event time minus prep buffer -> raise. **This cannot be
   pull-only and still work** - the ETA must be re-checked as T approaches. That is a scheduled
   background call, the second deliberate exception after the sitrep. Decide: does it ship,
   who owns the prep buffer (per-event, global, learned), what are the quiet rules (only events
   with locations, only when the window is closing), and how is it cancelled?
5. **Geofences.** Upgrade `PlaceController`'s distance math to the OS geofencing API - event-driven,
   battery-cheap, works in the background, and place reminders start firing properly. Confirm this
   is the right move and name what it changes for existing rows.
6. **Garage-on-approach.** Geofence entry at home + driving Phase -> the Shelly path (or HA once
   [home control](03-home-control-scope.md) lands). Automatic, or spoken offer ("open the
   garage?")? An outward-facing automatic action needs its own decided rule.
7. **Data plane, said in words.** ETA calls send origin and destination coordinates to a traffic
   vendor on Kevin's own key. Same posture as Gemini hearing his voice, but it belongs in the
   decision, not discovered later.

## Resolution - 2026-08-21 (Kevin, 8 calls)

### 1. All four keyless categories ship

Severe weather (NWS), earthquakes (USGS) and wildfire (NIFC WFIGS), air quality (AirNow), and FEMA
declarations. [Ticket 14](14-location-intel-research.md) confirmed every one live and keyless except
AirNow, which is a free self-service key with no card.

**One tool, `area_info`, with a category parameter** - not five tools. Five would spend five slots of
prompt budget on one idea.

**Two source choices carried from the research rather than re-derived:**

- **USGS real-time GeoJSON summary feeds, never `fdsnws/event/1/query`**, on any timer. USGS steers
  automated clients there explicitly, and they update every minute.
- **NIFC WFIGS over NASA FIRMS** for fire. FIRMS gives thermal anomalies; NIFC gives an incident
  name, a size and a containment percentage - the difference between "there is heat somewhere" and
  something worth saying out loud. If FIRMS is ever used, it is spoken as *"satellite heat
  detection"*, never as a fire.

**Open item before AirNow is written:** the research found lat/lon endpoints listed under
*"Web Services that will be retired in the fall of 2026"*, which is weeks away. Log in and read the
specific service page before writing that client. Do not assume the surviving endpoint.

### 2. Traffic: TomTom, on request only

The only vendor a private individual can use without a card on file. Traffic-aware ETA is the
**default** of its free 20,000-call Routing API rather than a premium SKU, which is the exact
feature wanted. Google now requires billing on every project, its traffic-aware modifiers are the
Pro tier by construction, and its $200 monthly credit is gone.

Guardrails, all from the research: key in Keystore via `KeyVault` (same BYO shape as Gemini and
Spotify), **do not persist an ETA to Room or Drive until TomTom's caching clause has actually been
read**, offline is stated in words, and a spoken ETA is attributed *"per TomTom"*.

**Failure mode without a key is a hard stop, not a surprise bill.** That is why it won.

### 3. What may speak unprompted, with thresholds

| Source | Raises? | Threshold |
|---|---|---|
| NWS | **Yes** | **Warnings only, at Severe or Extreme.** Never watches or advisories |
| USGS | **Yes** | **M4.5 within 150 miles** |
| NIFC | **Yes** | **Within 25 miles** |
| AirNow, FEMA, TomTom, FBI | No | Pull-only |

All three raising sources map to the proactive map's **Safety** category, which has shipped switched
off with no content since the build. Safety is uncapped by the daily budget and exempt from quiet
hours - **and still inside the master kill switch**, which has no exemptions (settled decision 2).

**Watches and advisories are excluded deliberately.** They fire constantly, and a channel that cries
wolf trains Kevin to ignore the one warning that matters. The thresholds are tunable once there is
real data on how often they trip; they are starting points, not findings.

### 4. Attribution is not optional

Every one of these is a third-party claim and is spoken as one: *"NWS has a tornado warning for your
area until 6pm"*, never *"there is a tornado warning"*. The distinction is not pedantry - it is what
lets Kevin judge the source, and it is the same posture as `DigestText`'s trust tiers.

### 5. Crime: ship the honest tool, and the refusal IS the feature

`get_reported_crime_history` only. **`is_area_safe` is never built.**

The research is unambiguous: agency-level rather than neighborhood, roughly 13 months stale,
voluntary and incomplete reporting, and measuring reporting propensity as much as crime. Asked *"is
this area safe"*, the assistant says plainly that this data cannot answer it, then offers what it
can: *"Agency X reported N violent-crime offenses in 2024, per the FBI's Crime Data Explorer, the
most recent complete year."*

This is CLAUDE.md §4 rule 5 in its strongest form - **not even "estimate" is an honest label**,
because there is no estimate to be made. The tool description states every limit, so the model cannot
round a jurisdiction count into a judgement about a street.

### 6. The departure advisor SHIPS, with bounded polling - and this amends call 2

Kevin's call, and it is an exception to the on-request-only rule he had just made, so it is written
down as one rather than left to blur.

**ETA polls only inside a window before a calendar event that HAS a location, roughly the last 90
minutes, and stops the moment the event starts or the advisor has spoken.** Nothing else polls
TomTom, ever.

Still to decide at build time: who owns the prep buffer (per-event, global, or learned), and how a
raise is cancelled once Kevin is already moving. Both are real questions and neither blocks the rest.

### 7. Geofences: upgrade to the OS API

`PlaceController`'s raw distance math on GPS polls becomes registered OS geofences - event-driven,
battery-cheaper than what runs today, and it works in the background, which is why **place reminders
start firing properly instead of depending on a poll happening to land near the place.**

Names what it changes: existing `TaggedPlace` rows must be registered as geofences on migration and
re-registered after a reboot, and the OS caps how many can be held at once. That cap needs a stated
policy before the migration is written.

### 8. Garage-on-approach: a spoken offer, never automatic

*"Open the garage?"* - one word from Kevin, then the relay fires.

**The relay is a single-button toggle that cannot report door state.** CLAUDE.md already forbids
saying "opening" or "closing" for exactly that reason. An automatic trigger on a location guess
could therefore close a door on something, with nobody having decided and no way to know which way
it went. A GPS drift or a drive-past is enough. One word is a small price.

## This is a MAP, not a ticket

Said in this ticket's own opening line - *"the five numbered clusters below are each roughly a
ticket"* - and eight decisions later it is more true, not less. The build spans four HTTP clients, a
BYO key, a geofence migration, a scheduled poller, a physical-world action, and the first real
content for the Safety category.

**Recommend charting `location-intelligence` as its own map**, with [ticket 14's
findings](14-location-intel-research.md) as its resolved research and the eight calls above as its
settled decisions. Nothing here needs re-deciding; the map is for sequencing the build.
