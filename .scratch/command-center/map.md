---
map: command-center
title: "Map: The app you can see, touch, and remember"
charted: 2026-08-22
charted-by: ""
effort: "`.scratch/command-center/`"
tickets: 0
open: 0
status: open
tags: [map]
---
# Map: The app you can see, touch, and remember

## Destination

Kevin, 2026-08-22, verbatim, and it is the whole charter:

> *"i need to know what the app does, users need a wiki on what the app can do, voice tools need a
> physical route. and also a redesign of the UI UX including in setup screen especially the
> proactive levers can be put in a submenu etc. audit each page and see if the visuals serve any
> purpose and if they are genuinely useful. especially the home page. the calorie intake shouldnt
> be the hero card. its a command center of my daily life. things like news, email, todos,
> workouts to do, alerts, location intelligence etc should all be there instead. after u make the
> plan just implement it using the whole team. keep the same art style but u have full freedom to
> redesign everything."*

## The evidence under it

Ticket 27's survey (hands-and-senses, resolved 2026-08-22): **69 declared voice tools, 32 covered
by a screen, 15 partial, 22 voice-only.** Money and Notes grew screens first and comply; everything
that grew voice-first (media, phone, location, outside world) is invisible unless you already know
to ask. That is the mechanism behind "I forget what the app can do".

## Rulings recorded at charting (Kevin's words above ARE the rulings)

1. **Mail-derived content may be DISPLAYED.** "news, email... should all be there" overrides the
   earlier "Gmail is voice-only" ruling (google-account). The read-through rule is UNCHANGED and it
   governs STORAGE, not display: fetched live, rendered, dropped. No Room row, no cache that
   outlives the process, a visible staleness timestamp, and CLAUDE.md sec 7's third-party bullet
   applies in full.
2. **Art style stays mission-control.** `ui/theme/Color.kt`, the Deck components, the existing
   register. Freedom is in layout and hierarchy, not palette.
3. **The hero of Home is the day, not a metric.** Calorie intake demotes to a tile.
4. **Everything else is delegated**: "full freedom to redesign everything."

## Waves (build order, collision-driven)

- **Wave 1** - disjoint domains, parallel: 03 body writes, 04 media panel, 06 places, 07 build sheet.
- **Wave 2** - parallel: 02 settings submenus, 05 phone surface, 08 outside-world cards, 11 small writes.
- **Wave 3** - 01 home command center (consumes wave 1-2 components; TodayScreen is rewritten once, not four times).
- **Wave 4** - 09 in-app discovery + user wiki, 10 per-page audits.

## Out of scope

- The driving screen. `drive-ui` ticket 12 owns its re-scope and stays there.
- New voice tools. This map is the hands half of ADR 0035.
- Any compulsion mechanic, streak, or score. Permanent (CLAUDE.md sec 7).
