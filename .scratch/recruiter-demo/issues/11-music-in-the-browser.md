---
map: recruiter-demo
ticket: "11"
title: "Music demo: 'play X' by voice, plays in the page via a Spotify embed"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-recruiter-role-and-allowlist]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Music demo: 'play X' by voice, plays in the page via a Spotify embed

## What (Kevin, 2026-11-02)

*"also showcase music control capability, like embed spotify app remote on the page or something?
then let ppl play what song they want etc. possible?"*

App Remote is Android-only, and it would drive KEVIN's Spotify. Neither works for a visitor. Instead:

- Tool `find_track` (recruiter role): engine searches the Spotify Web API with client credentials
  (server-side app key, no user login, no access to Kevin's account) and returns track id + title.
- The page receives the result and loads the Spotify Embed iframe for that track, then plays it via
  the iframe API. The mic tap counts as the user gesture autoplay needs.
- Visitor not signed in to Spotify in that browser: 30 s preview. Signed in: full track. Say so on
  the page.
- Assistant only claims "playing" after the page reports playback started (CLAUDE.md section 7).

## Verify / open

- Spotify dev-mode limits on new apps and client-credentials search: `reasoned`, check before build.
- Search counts against no Gemini budget, but does count a call; rate limit with ticket 02's per-visitor throttle.
