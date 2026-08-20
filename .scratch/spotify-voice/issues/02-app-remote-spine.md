---
map: spotify-voice
ticket: 02
title: "App Remote is the spine, and it creates the device rather than needing one"
type: task
status: built
status-detail: "Built (66d1d4c). PlayOutcome + switch-to-local + held connection. The cold case has never been run. NOT installed, NOT verified on the phone."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# App Remote is the spine, and it creates the device rather than needing one

## Question

The research inverted the obvious architecture. **App Remote is not the fast path, it is the
spine.** `SpotifyAppRemote.connect()` starts Spotify's own service and, once connected, "will
prevent Spotify from shutting down even if the user is not playing anything" - it **creates** an
active device instead of requiring one. The Web API cannot: a write with nothing active has no
target, and transfer can only move playback to a device Spotify already sees online.

That single fact decides the cold case, which is the likeliest real failure in a car: asking for
music with Spotify closed.

The division is fixed and neither side replaces the other: **Web API resolves a name into a URI;
App Remote makes sound come out.**

## Scope of the build

1. **One play path, used by every tool on this map:** `isInstalled` then `ensureConnected` then
   `ConnectApi.connectSwitchToLocalDevice()` then `PlayerApi.play(uri)`. The switch-to-local step is
   new and is what pulls playback off a house speaker onto the phone.
2. **Four distinct spoken failures, never one generic one:** `CouldNotFindSpotifyApp`,
   `NotLoggedInException`, `UserNotAuthorizedException`, `OfflineModeException`. Each names what the
   driver would have to do about it.
3. **Hold the connection in the foreground service** (Kevin, 2026-08-19). Spotify's own guidance is
   connect in `onStart`, disconnect in `onStop`, "otherwise Spotify will not be able to shutdown".
   **We violate that deliberately** - a per-command connect costs latency on every utterance, and
   this is a car assistant. Write the violation and its reason into the code, not only here.
4. **`subscribeToPlayerState` replaces polling** for anything needing to know what is playing. Push,
   no quota. Tickets 07 and 09 both read from it.
5. **Premium detection via `UserApi.getCapabilities().canPlayOnDemand`** - the only route left, since
   `user.product` was removed for dev-mode apps in Feb 2026. Not a gate (this UX is deliberately
   Premium-only); it is how a non-Premium account gets told so in words rather than getting silent
   shuffled playback.

## Verification

- [ ] Kill Spotify entirely, then ask for music. It plays. **This is the ticket.** `on-device`.
- [ ] Playing on another Connect device, ask for music: playback moves to the phone. `on-device`.
- [ ] Log out of Spotify; the spoken failure names logging in, not a generic error. `on-device`.
- [ ] Airplane mode: the offline case is spoken as offline. `on-device`.
- [ ] Confirm the held connection does not stop the driver killing Spotify, and record what actually
      happens - the docs warn about shutdown and we are choosing to ignore that guidance.
