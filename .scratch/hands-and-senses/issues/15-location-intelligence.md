---
map: hands-and-senses
ticket: 15
title: "Location intelligence: what LEGION knows about where you are, and when it speaks first"
type: grilling
status: open
status-detail: ""
blockers: ["14"]
blocked-by: ["[[14-location-intel-research]]"]
open-blockers: 0
ready: true
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
   background call, the second deliberate exception after the morning brief. Decide: does it ship,
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
