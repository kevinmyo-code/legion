---
map: mapbox-nav
ticket: "02"
title: "Building with and without Mapbox credentials"
type: decision
status: resolved
status-detail: "Built 2026-10-03: no gate needed"
blockers: ["01"]
blocked-by: ["[[01-sdk-facts]]"]
open-blockers: 0
ready: false
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

## Answer (2026-10-03)

**No gate, no flavor, no stub.** The spike (commit 6fc647df) resolved
`com.mapbox.navigationcore:android-ndk27:3.32.0` and its Maps dependencies through Gradle with no
Mapbox property on the machine, and compiled against Kotlin 2.1.0 / AGP 9.2.1 / compileSdk 36
(built). `settings.gradle.kts` attaches basic-auth credentials only if `MAPBOX_DOWNLOADS_TOKEN` is
set; that branch was never needed and is untried. Not isolated with a cold Gradle cache.
