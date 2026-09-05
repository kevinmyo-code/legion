---
map: architecture
ticket: "05"
title: "detekt joins the build, with a baseline and a 1000-line ceiling"
type: build
status: built
status-detail: "Built 2026-09-05. detekt 2.0.0-alpha.0 (built against Kotlin 2.2.10), baseline 4817 findings, LargeClass 1000 as the file-ceiling proxy, proven red on a planted violation. Not a phone item."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Baseline (built 2026-09-05)

detekt version: **2.0.0-alpha.0**, group `dev.detekt` (the 2.0 line's renamed group/plugin id,
`io.gitlab.arturbosch.detekt` has no 2.x releases). Chosen over the last 1.x stable (1.23.8,
2025-02-21) because detekt's own docs state it is "tightly coupled to the Kotlin compiler" and a
mismatch fails outright at runtime; checking each 2.0.0 release's own GitHub notes for the Kotlin
version it was "built against" turned up alpha.0 -> **2.2.10 exactly**, the same Kotlin release
pinned in the root `build.gradle.kts` (alpha.1 onward move to 2.2.20/2.3.x/2.4.x, all past this
project's pin). 1.23.8 predates Kotlin 2.2 entirely.

`./gradlew :app:detektBaseline` recorded **4817 findings** across the existing codebase (`config/detekt/baseline.xml`).
Top 10 rules by count:

| Rule | Count |
|---|---|
| MaxLineLength | 2514 |
| MagicNumber | 795 |
| FunctionNaming | 554 |
| ReturnCount | 313 |
| LongMethod | 134 |
| TooGenericExceptionCaught | 112 |
| CyclomaticComplexMethod | 110 |
| TooManyFunctions | 90 |
| SwallowedException | 54 |
| LoopWithTooManyJumpStatements | 48 |

(`LargeClass`, the file-length-ceiling proxy - see `config/detekt/detekt.yml`'s own comment for why
there is no direct file-length rule to use instead - has 2 baseline findings; `LongParameterList`
has none.)

Proven to bite: a scratch file with a 69-line function (`allowedLines: 60`) failed
`./gradlew :app:detekt` with `LongMethod` naming the exact file, line and length; deleting the file
returned the build to green. Full transcript in the session report.

Zero baseline findings were fixed as part of this ticket - the baseline is a list, not amnesty.
