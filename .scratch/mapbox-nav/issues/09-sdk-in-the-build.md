---
map: mapbox-nav
ticket: "09"
title: "Mapbox in the build, behind the clone-and-run gate, with token setup"
type: build
status: built
status-detail: "Built 2026-10-03, suite green; owes a run on the phone: paste, bad token, clear, no-key build"
blockers: ["02", "08", "13"]
blocked-by: ["[[02-build-with-and-without-credentials]]", "[[08-where-the-token-lives]]", "[[13-vehicle-usage-clause]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Mapbox in the build, behind the clone-and-run gate, with token setup

## Build

Per 02 and 08: the dependency, the credentials gate, the token entry in Setup, and a Mapbox nav
controller (injected class, Hilt) that reports "not set up" in words when there is no token. No
route, no screen yet.

## Verification

- `compileDebugKotlin -Pnokey` green with NO Mapbox credentials on the machine.
- The same build with credentials green; `testDebugUnitTest` green.
- On the phone: Setup accepts a token; a bad token is reported in words.

## Built (2026-10-03)

- `navigation/MapboxTokenProvider.kt` owns the order: pasted (KeyVault via `CompanionProfile`), then
  `BuildConfig.MAPBOX_ACCESS_TOKEN`, then none. A paste or clear re-applies `MapboxOptions.accessToken`
  live. Setup row: `ui/MapboxTokenSection.kt`, on the Gemini key screen. `pk.` only; `sk.` refused by name.
- Bad token is learnt from the SDK (a route failure whose text reads as 401/not authorized, or a map
  style-load error), never REST (ToS 2.9.1). The match is on wording because the SDK has no typed auth code.
- Owed on the phone: paste a good token, paste a bad one (navigation says "Mapbox refused the token"),
  clear it, install a `-Pnokey` build (reads "Navigation isn't set up"). The auth-wording match is
  reasoned, not seen against a real 401.
