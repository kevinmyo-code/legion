# GitHub skills scout for LEGION (2026-08-23)

Tooling research. Nothing installed. Baseline: chrisbanes set (19 Compose/Kotlin skills, vendored),
mattpocock-skills plugin (tdd, diagnosing-bugs, code-review, research, prototype, domain-modeling,
codebase-design, writing-for-agents), repo-only wayfinder/to-spec/grillme. Gaps hunted: Room
migrations, Gradle build health, testing beyond Compose UI, ADB/device QA, security review,
release/R8, BLE, WebSocket audio.

Method: WebSearch + GitHub API trees + read the actual SKILL.md bodies (not just READMEs) for
every RECOMMEND below.

## Shortlist

| # | Skill(s) | Repo + path | License | Verdict | Why |
|---|---|---|---|---|---|
| 1 | `android-debugging` | github.com/rcosteira79/android-skills, `plugins/android-skills/skills/android-debugging/SKILL.md` | MIT | **RECOMMEND** | Logcat/ADB/ANR-trace/R8-retrace/LeakCanary evidence-gathering on a root-cause-first frame. Nothing in chrisbanes or mattpocock covers device-side debugging; complements `diagnosing-bugs` (which is process, not Android mechanics). |
| 2 | `gradle-build-performance` + `android-gradle-logic` | same repo, `.../gradle-build-performance/SKILL.md`, `.../android-gradle-logic/SKILL.md` | MIT | **RECOMMEND** | Concrete: configuration cache, kapt-to-KSP (LEGION still uses kapt for Room), lazy registration, AGP 9 deltas, convention-plugin wiring gotchas. Zero overlap with anything vendored. `android-gradle-logic` is lower priority (LEGION is single-module) but small and pairs with it. |
| 3 | `android-testing` | same repo, `.../android-testing/SKILL.md` | MIT | **RECOMMEND** | The Compose-test dispatcher traps (StandardTestDispatcher default, two-schedulers trap), test-shape selection table, test-clock vs wall-clock. Partial overlap with `compose-ui-testing-patterns` but the coroutine/scheduler material is absent there. Strip the TDD-foundation paragraph on vendoring (fights CLAUDE.md's plan-then-execute model, same reason upstream `tdd` was skipped). |
| 4 | `testing-setup` | github.com/android/skills (Google official), `testing/testing-setup/SKILL.md` | Apache-2.0 | **RECOMMEND** | Bootstrap/audit of a test stack: Robolectric's three usage modes, Roborazzi screenshot testing, instrumented runner, coverage. LEGION's Robolectric+PdfBox seam and the untested LedgerController/PantryController paths are exactly its territory. Has `references/` files - vendor those too. |
| 5 | `android-intent-security` | github.com/android/skills, `security/android-intent-security/SKILL.md` | Apache-2.0 | **RECOMMEND** | Exported components, intent redirection, PendingIntent, ContentProvider hardening. LEGION ships an FGS, multiple activities, SAF/ContentResolver paths; the built-in `/security-review` is generic, this is the Android-specific checklist. Only Android security skill found anywhere. |
| 6 | skydoves/android-testing-skills - the `adb/*` subtree (10 skills) | github.com/skydoves/android-testing-skills, `adb/**/SKILL.md` | Apache-2.0 | **RECOMMEND (adb subtree only)** | Deep, checked-quality ADB guidance: wireless connect, logcat buffers/filters/rotation, screenshot/screenrecord, input injection, install/manage, artifact extraction, CI scripting. Maps directly onto LEGION's real workflow (wireless ADB to the A25, qa agent, hash-verified installs). The `compose/*` and `jvm-tests/*` subtrees overlap chrisbanes + #3; take `adb/` alone. |
| 7 | `r8-analyzer` | github.com/android/skills, `performance/r8-analyzer/SKILL.md` | Apache-2.0 | **MAYBE** | Keep-rule audit and app-size work. Useful the day LEGION does a minified release build; premature now (debug-signed sideloads). Bookmark, don't vendor. |
| 8 | `android-profiler` | github.com/android/skills, `profilers/android-profiler/SKILL.md` | Apache-2.0 | **MAYBE** | Perfetto traces, heap dumps, jank/startup investigation via workflows + scripts. Powerful but bundles executables (violates this repo's markdown-only vendoring posture) and no current perf problem. Revisit if voice-path latency work starts. |

Priority order if vendoring a subset: 1, 6, 4, 5, 3, 2.

## Vendoring notes

- All shortlisted are MIT or Apache-2.0: vendorable with an ATTRIBUTION.md entry (source URL,
  commit, license, what was copied), same posture as chrisbanes.
- android/skills SKILL.md frontmatter says "license: Complete terms in LICENSE.txt" - copy the
  repo's LICENSE.txt alongside or cite it in ATTRIBUTION.md.
- rcosteira79 skills live under `plugins/android-skills/skills/` and some cross-link each other
  (`android-skills:android-gradle-logic` syntax) - fix or drop those links on vendoring, as was
  done for the chrisbanes router.
- Google's `testing-setup` and `android-profiler` carry `references/` dirs and (profiler) scripts.
  Markdown only, per the standing rule: skip the scripts, keep the reference markdown.
- Several rcosteira79 skills defer to Google/JetBrains skills by URL; those links are fine to keep
  (read-only pointers).

## Ruled out, and why

| Repo | Why |
|---|---|
| humanshell/android-skills | **No license file** - not vendorable. Content is opinionated MVI/Koin/JUnit5 architecture LEGION does not use. |
| dpconde/claude-android-skill | One monolithic skill pushing Hilt + offline-first + modularization - an architecture prescription, not additive guidance; LEGION's architecture is locked. |
| Drjacky/claude-android-ninja | Same shape: single SKILL.md prescribing multi-module Navigation3 architecture + convention-plugin assets. Apache-2.0 but wrong-shaped for a locked single-module app. |
| mmiani/kotlin-kmp-claude-agent-skills | KMP-first (expect/actual, Compose Multiplatform, KMP testing); LEGION is single-platform Android. Also NOASSERTION license. |
| new-silvermoon/awesome-android-agent-skills | Apache-2.0, broad, but shallow next to rcosteira79 on the same topics (Gradle perf, data layer, testing) and carries Hilt/Retrofit/XML-migration baggage. Redundant given #1-#4. |
| Kotlin/kotlin-agent-skills (JetBrains) | Only 6 skills, all migration/KMP/backend-shaped (`agp9-migration`, `java-to-kotlin`, JPA). Bookmark `kotlin-tooling-agp9-migration` for the eventual AGP 9 bump; nothing to vendor today. |
| skydoves/compose-performance-skills | Already ruled out in ATTRIBUTION.md; still correct (baseline profiles/R8 tuning, heavy chrisbanes overlap). |
| anthropics/skills | Still document-generation + skill-creator with bundled Python; ATTRIBUTION.md's prior rejection stands. |
| Jeffallan/claude-skills `kotlin-specialist` | Single generic "Kotlin specialist" persona skill; chrisbanes set is strictly deeper. |
| awesome-claude-skills lists (karanb192, ComposioHQ, travisvn, BehiSecc, Chat2AnyLLM) | Directories, not skills; swept for Android/Kotlin entries - everything relevant resolved to repos already assessed above. |
| SimoneAvogadro/android-reverse-engineering-skill | APK reverse engineering - not LEGION's problem. |
| OneWave-AI database-migrator, 0xDarkMatter sqlite-ops | Server-DB / Python-SQLite shaped; nothing about Room's migration model. |

## Gaps with no good skill found

- **Room migrations specifically** - no dedicated skill exists anywhere. LEGION's own CLAUDE.md §5
  discipline (verbatim generated SQL, additive, exportSchema, migration tests) is already stronger
  than anything found; if wanted as a skill, author it locally from §5.
- **BLE/Bluetooth (ELM327/RFCOMM)** - nothing beyond reverse-engineering tooling.
- **WebSocket live audio / Gemini Live** - nothing.
- **Release signing** - nothing beyond r8-analyzer's adjacency.
