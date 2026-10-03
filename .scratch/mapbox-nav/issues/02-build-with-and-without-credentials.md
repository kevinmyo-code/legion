---
map: mapbox-nav
ticket: "02"
title: "Building with and without Mapbox credentials"
type: decision
status: open
status-detail: "Research says moot; the spike proves it with Gradle"
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Building with and without Mapbox credentials

## Question

A stranger's clone must build and run with no Mapbox credentials at all (§2 clone-and-run). If
downloading the SDK needs a secret token at BUILD time, how does a clone without one still build?

## Options to weigh (once 01 lands)

- A Gradle property that, when absent, swaps in a stub source set and drops the dependency.
- A product flavor (`mapbox` / `plain`).
- Vendoring the artifacts (likely forbidden by the license; 01 to confirm).
- Moot, if 01 finds the Maven repo no longer needs auth.

Whatever wins: the no-credentials build says "navigation is not set up" in words wherever navigation
would appear, and `compileDebugKotlin -Pnokey` stays green.
