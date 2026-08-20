---
map: spotify-voice
ticket: 11
title: "The recommendation engine LEGION has to build itself"
type: task
status: kiv
status-detail: "KIV 2026-08-20 (Kevin). Least important thing on the map; parked until the built Spotify surface has actually been driven."
blockers: ["10"]
blocked-by: ["[[10-what-like-this-means]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# The recommendation engine LEGION has to build itself

## Question

Build whatever ticket 10 decides. The scope is written after that resolves - **deliberately not
specified here**, because writing a build against a hypothetical is exactly what drive-test ticket
04's blocking relationship existed to prevent.

## What is already known and binding

- **Every input is first-party.** Top items across three time ranges, recently played, the saved
  library, his own playlists, and `legion_history`. **No Spotify endpoint available to us carries a
  similarity signal** - recommendations, related-artists and audio-features are all dead for apps
  registered after 2024-11-27.
- **Search is capped at 10 results**, so resolving a model-suggested name into a real URI is a narrow
  funnel and will sometimes fail. A suggestion that cannot be resolved is spoken as not found, never
  quietly swapped for the nearest hit.
- **Anything the model asserts about music that it did not read from Kevin's own record is an
  estimate** (CLAUDE.md sec 4 rule 5) and is labelled one, in the tool description and aloud.
- **Recently-played does not include podcast episodes**, per Spotify's own docs.

## Verification

- [ ] Whatever ticket 10 decides, its verification steps are binding here (L11).
- [ ] Ask for a recommendation on a real drive; what plays is what was described. `on-device`.
- [ ] A suggestion that cannot be resolved to a URI is spoken as not found. `on-device`.
- [ ] Nothing is written to the library or a playlist that the driver did not ask for.
