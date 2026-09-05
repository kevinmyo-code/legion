---
map: architecture
ticket: "05"
title: "detekt joins the build, with a baseline and a 1000-line ceiling"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# detekt joins the build, with a baseline and a 1000-line ceiling

Kevin, 2026-09-05: *"1000 lines, add detekt."*

detekt Gradle plugin, default rule set plus `LargeClass` / `LongMethod` / `TooManyFunctions` at
values that match the intent (file ceiling 1000 lines); `detekt-formatting` OFF (this repo has no
ktlint and is not adopting one today). **Generate a baseline** (`detektBaseline`) so every existing
finding is recorded and the build goes red only on NEW debt. A baseline is not amnesty, it is a
list, and this ticket records the count. `./gradlew detekt` runs inside the `/verify` skill.

Not a style opinion. Same posture as `docs_check.py`: a check, not a hope.

Verify: `./gradlew detekt` green on the baseline; introduce one deliberate violation in a scratch
file and show it red; remove it. Record the baseline count here.
