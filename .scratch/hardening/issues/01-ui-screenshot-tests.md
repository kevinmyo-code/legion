---
map: hardening
ticket: "01"
title: "Screenshot tests: the phone's eyes in the suite"
type: task
status: resolved
status-detail: "Resolved 2026-08-24. Ten Roborazzi baselines on the A25 profile, verify green in the JVM suite both key ways. Known limitation named: DeckGrid card content does not draw under Robolectric graphicsLayer capture; theme is dark-only by design so no light set."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Screenshot tests: the phone's eyes in the suite

## Question

Seven grid rounds proved every UI bug needed the real phone; screenshot tests catch the cheap
half of that class in the JVM suite. Build with Roborazzi (Robolectric-native, runs in
testDebugUnitTest - the vendored testing-setup skill covers wiring):

1. Roborazzi + Robolectric graphics config in app/build.gradle.kts; a record/verify Gradle
   task pair; baselines committed under app/src/test/snapshots/.
2. First baseline set, chosen for regression value: the widget pager HOME (seeded arrangement),
   DeckGrid edit mode (grid lines, chips, size chip), a generated list/detail/form for a seeded
   record type, a widget in each of its states (data / empty-in-words / error-in-words /
   not-configured), MirrorSyncActivity, and the A25's 384x832dp profile as the device config.
3. Light and dark if the theme supports it; deterministic clock/data via the seeders (no
   Date.now in fixtures - the Monday-flake lesson).
4. README section: how to re-record intentionally vs what a surprise diff means; diffs are
   review artifacts, not auto-accepted.
5. Suite green both key ways; record task NOT run in CI/hooks - on demand like the evals.

## Answer

Resolved 2026-08-24. Ten baselines committed (pager home, DeckGrid edit chrome, generated
list/detail/form, four widget states in words, mirror screen), recorded at 384x832dp, verified
by gradlew verifyRoborazziDebug inside the normal suite. Limitations stated, not faked: DeckGrid
per-card content does not render under Robolectric's graphicsLayer capture (chrome and grid lines
do); the theme is dark-only by stated design. Re-record deliberately per the snapshots README;
a surprise diff is a review artifact.
