---
map: ambient-listening
ticket: "01"
title: "What the listening indicator looks like, and where it lives"
type: prototype
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# What the listening indicator looks like, and where it lives

## Question

`AmbientListener`'s KDoc calls a persistent on-screen indicator **required** whenever the engine is
running, and points at `ui/CruiseScreen.kt`. That file does not exist in LEGION. The requirement
survived the port; the surface did not.

Kevin has already ruled the shape (2026-08-20): **an in-app indicator plus a toggle.** This ticket
decides what that indicator actually is, by making a rough one to react to rather than describing it.

Build it in the mission-control language (`docs/adr/0023-design-language-mission-control.md`,
`ui/theme/Color.kt`) and put it in front of him:

1. **Where does it live** so it is genuinely persistent - a bar on every screen, a fixed element in
   the chrome, or something the root layout owns?
2. **Is it visible when LEGION is not the foreground app?** If the answer is no, say so plainly here
   rather than letting "persistent" quietly mean "persistent while you happen to be looking."
3. **Does it distinguish listening from reacting?** Transcribing locally and sending text to Gemini
   are different acts and a passenger might reasonably care which is happening.
4. **What does it do when muted?** `ProactivePreferences.muted` is a hard listening gate here, so the
   indicator must be able to say "on, but not listening" without being confusing.

Colour alone is not an answer - `CLAUDE.md` sec 4 rule 7 already forbids that for unverified figures
and the same reasoning applies to a consent signal. Words, or a shape, or both.
