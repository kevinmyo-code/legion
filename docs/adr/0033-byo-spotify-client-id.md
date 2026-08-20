---
status: accepted
decided: 2026-08-19
decided-by: Kevin
source: "[[decisions#2026-08-19 - Spotify voice control: the settled shape (.scratch/spotify-voice, tickets 01-09 + 13 BUILT, none on-device)]]"
tags: [adr]
---

# 33. BYO Spotify client ID satisfies clone-and-run

## Standing

Each user registers their own Spotify developer app and pastes only its client ID. Nothing ships a
shared ID; Premium is assumed. Reaffirms the 2026-07-21/22 reopen; settled again, wider, for the
spotify-voice effort.

## Context

Spotify Development Mode caps a registered app at 5 users and requires the app owner to hold
Premium. The extended-quota programme is permanently closed to LEGION (since 2025-05-15 it accepts
only registered businesses with 250k+ MAU). A shared Kevin-registered client ID would both trip the
cap and violate the no-Kevin-hosted-anything rule ([[0002-no-hosted-backend]]).

## Decision

The client ID is driver-supplied through Setup (`CompanionProfile.saveSpotifyClientId`), the same
BYO shape as the Gemini key. The redirect URI is app-fixed
(`com.kevin.legion://spotify-callback`) because a manifest intent-filter scheme is static - it must
be entered verbatim in the driver's own Spotify dashboard. Every `/me/player` write is
Premium-gated and the UX assumes Premium rather than degrading around it ("no point for non
premium").

## Consequences

- Dev-mode caps and quota programmes are the driver's relationship with Spotify, not LEGION's.
- Setup is a real registration chore for a stranger: create a Spotify dev app, paste the ID, enter
  the redirect URI exactly.
- Policy caveat carried from the original reopen: whether BYO-own-dev-app complies with Spotify's
  developer terms is gray; risk accepted by Kevin 2026-07-21.
