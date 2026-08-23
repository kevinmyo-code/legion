---
map: command-center
ticket: "01"
title: "Home is a command center, not a calorie poster"
type: build
status: built
status-detail: "Built: hero is the day (next event, tickable checklist, alerts from the raise history), context strip with weather and AQI, tiles for mail/news/money/fleet/media, intake demoted. Owes the on-phone look at 384x832 and the airplane-mode honesty check."
blockers: ["03", "04", "06", "07", "08"]
blocked-by: ["[[03-body-writes-by-hand]]", "[[04-media-panel]]", "[[06-places-by-hand]]", "[[07-build-sheet-screen]]", "[[08-outside-world-cards]]"]
open-blockers: 5
ready: false
tags: [ticket]
---
# Home is a command center, not a calorie poster

Kevin: *"its a command center of my daily life. things like news, email, todos, workouts to do,
alerts, location intelligence etc should all be there instead."* The calorie intake pane loses the
hero slot and becomes a tile.

## The hierarchy

1. **Hero: TODAY.** Next calendar event with its clock, today's plan checklist (the tickable panel
   from goal-plans 07, compact form), and anything the assistant currently wants him to know
   (pending/recent proactive raises, from the raise history - rendered as alerts, never re-spoken).
2. **Context strip:** weather line + where-am-I context (area name; AirNow air quality when the key
   and data exist - it is wired in BuildConfig and unconsumed).
3. **Tiles, glanceable, each opening its full surface:** mail highlights + packages + flights
   (fetch-on-demand, staleness shown, never stored), newsletters digest (same), money snapshot
   (balance + month spend), fleet status (maintenance due count; OBD context only when connected),
   intake (demoted), media now-playing mini (from ticket 04's panel).

## Rules

- **Read-through governs every mail-derived tile**: fetched live, in-memory only, staleness
  timestamp visible, a refresh affordance rather than an auto-poll. No Room row, no cache file.
- **Empty and unreadable are different sentences** on every tile (CLAUDE.md sec 1): "no mail found"
  is not "could not reach Gmail"; a missing AirNow reading is not clean air.
- No fabrication: a tile with nothing to say collapses or says so; it never invents content.
- Estimates labelled (flight/package extraction is an estimate and names its mail).
- `TodayScreen.kt` is rewritten ONCE, in this ticket, consuming components from 03/04/06/07/08.
  That is why this is wave 3.

## Verification

- Suite green both ways. On the phone: the hero is the day, every tile opens its surface, mail
  tiles show staleness and survive airplane mode with an honest failure line.
