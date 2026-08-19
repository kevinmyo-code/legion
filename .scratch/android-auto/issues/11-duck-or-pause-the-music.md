---
map: android-auto
ticket: 11
title: "Duck the music, or pause it?"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Duck the music, or pause it?

## Question

Spotify will be playing. LEGION is about to talk over it. Ticket 04 establishes what the platform
does automatically (a telecom call may pause other media whether or not the app asks) and what focus
requests are available; this ticket decides what LEGION *wants*, which is a taste call, not a
technical one.

Decide:

1. **Duck or pause**, for a spoken answer. Ducking keeps the drive's mood and risks LEGION being hard
   to hear over a chorus. Pausing is unambiguous and jarring.
2. **Does the answer differ by length?** A one-line answer ducks, a two-minute briefing pauses.
3. **Does it differ by surface?** A tapped briefing from the browse tree is a deliberate act; a
   proactive interruption (map fog) is not, and Kevin may want those treated differently.
4. **Resume behaviour.** Music comes back automatically, or stays paused until Kevin restarts it.
   Getting this wrong is the most annoying possible bug in a car.
0. **KEVIN, 2026-08-13, and it may decide the map: he does not want the now-playing bar at all.**
   His Android Auto home screen is the split layout - Spotify's media card beside maps beside a third
   pane - and **Spotify keeps that card**. A media app cannot sit quietly next to another media app:
   the card holds *the* active media session, so LEGION taking it means Spotify losing it. If merely
   browsing LEGION's tree switches the media surface (likely, `inferred`, and cheap to test), then
   **the browse tree evicts Spotify every time it is used** - which is the opposite of what Kevin
   asked for.
   **A call does not have this problem.** It is not a media session: it takes the call surface, ducks
   the music, and hands the card back when it ends. **So this constraint is a stronger argument for
   the call design than the microphone ever was**, and the microphone argument turned out to be
   false. Ticket 07 should be decided on this. Test tonight: open LEGION's browse tree while Spotify
   plays, and see whether Spotify keeps its card.
5. **The awkward one: LEGION *is* the media app.** It occupies a media slot, so Android Auto may
   treat tapping LEGION as switching media source and stop Spotify outright - Kevin then has to go
   back and restart it after every question. If ticket 04 says that is what happens, this stops being
   a preference and becomes a real problem the design has to route around. Name the workaround or
   accept the cost, explicitly.
6. **Spotify App Remote vs generic MediaSession.** `media/SpotifyController` drives Spotify directly.
   Does LEGION use that to pause and resume precisely, rather than relying on audio focus? More
   control, and one more thing to keep in sync.
