---
map: architecture
ticket: "03"
title: "Bind the backend interfaces"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Bind the backend interfaces

`LastAspectsBackend` / `SupabaseLastAspectsBackend` is the shape: an interface at the seam a test
needs to fake, one production binding. Every `*Backend` in `backend/` gets the same treatment where
it does not already have it, bound in a Hilt module. Where a test today reaches for Robolectric only
to construct a `Context` for a backend, it becomes a plain JVM test with a fake.

Rule from §8: interfaces only where a fake is needed. Do not interface a class with one
implementation and no test double.

Verify: suite green; report how many test files dropped `@RunWith(RobolectricTestRunner)` and the
suite wall-clock before and after.
