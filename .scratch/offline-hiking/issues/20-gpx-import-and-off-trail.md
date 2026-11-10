---
map: offline-hiking
ticket: 20
title: "Build: GPX / KML import, and an off-trail warning"
type: build
status: open
status-detail: ""
blockers: ["14", "03"]
blocked-by: ["[[14-hike-screen]]", "[[03-trail-data-and-safety-facts]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: GPX / KML import, and an off-trail warning

**Release:** v2 (first item). **Size:** M.

## What

- Open a `.gpx` or `.kml` from the share sheet or Files ("Open with LEGION"): its tracks and routes
  draw on the hike map as the planned line; its waypoints show as pins.
- Off-trail warning: when Kevin's fix is more than a set distance (default 40 m, beyond GPS error)
  from the planned line for more than a minute, a vibration and a banner say how far off and which
  way the line is.

## Rules

- First-party: a file Kevin chose to import (CLAUDE.md §7 "anything Kevin created or chose to
  import"). Stored on the phone as the original file. Where it came from (AllTrails, a park site, a
  friend) is his business; research 03 lists each source's export rules, and the app does not fetch
  from any of them.
- Parsing is deterministic (an XML parser), never an LLM. A file that does not parse is refused in
  words with the reason; nothing partial is drawn as if complete (§4 rule 6 in spirit).
- Distance to the line: nearest segment, metres, pure function.
- The warning is anchored to a line Kevin loaded and is silenceable per hike in one tap.
- `application/gpx+xml` is not IANA-registered (research 03), so apps send GPX as
  `application/octet-stream`, `text/xml` or worse. Intent filters for those plus
  `application/vnd.google-earth.kml+xml` and `.kmz`; sniff the content (`<gpx` / `<kml`) rather than
  trusting the type; and an in-app "Import file" picker as the reliable path.
- Sources (research 03): Gaia GPS and CalTopo export GPX and KML; AllTrails exports GPX only on its
  paid tier and its terms ban reuse (his personal use of his own download is his call; LEGION never
  fetches from it); TPWD publishes official trails as a statewide KMZ.

## Verification

- Unit: GPX 1.0 and 1.1, KML LineString and Placemark, malformed files, a 10,000-point track,
  distance-to-line and the hysteresis on the warning. Fixtures use made-up coordinates.
- Compile, suite green. On the phone: an exported GPX from another app opens and draws. Owed.
