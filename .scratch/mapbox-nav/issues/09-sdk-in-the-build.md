---
map: mapbox-nav
ticket: "09"
title: "Mapbox in the build, behind the clone-and-run gate, with token setup"
type: build
status: open
status-detail: ""
blockers: ["02", "08", "13"]
blocked-by: ["[[02-build-with-and-without-credentials]]", "[[08-where-the-token-lives]]", "[[13-vehicle-usage-clause]]"]
open-blockers: 1
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
