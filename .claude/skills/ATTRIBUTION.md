# Skills provenance

These skills are vendored (copied in), not managed by a package manager, so this file records
where they came from and how to refresh them.

## Ported from MIDNIGHT_AI, 2026-08-01

This whole directory came over from `C:\Users\Kwin\StudioProjects\MIDNIGHT_AI\.claude\skills\` when
LEGION's tooling was set up. Upstream provenance below is unchanged and still accurate.

**Seven files were adapted for LEGION; the rest are verbatim.** Adapted: `issue-tracker.md`,
`to-spec/SKILL.md`, `grillme/SKILL.md`, `domain-modeling/SKILL.md`, `domain-modeling/ADR-FORMAT.md`,
`prototype/SKILL.md`, `prototype/LOGIC.md`, `prototype/UI.md`. They carried Midnight AI section
numbers, the dead frame-clock motion ban, head-unit preview sizes, and the retired entitlement
broker. Three of them were actively harmful if followed literally: the prototype trio told an agent
that duration-based Compose animation would break the build and that previews should be 1024x600,
both of which are head-unit constraints that the phone-only pivot lifted.

**The `mattpocock-skills` plugin is installed user-scoped**, so `grilling`, `research`, `prototype`,
`domain-modeling`, `tdd`, `codebase-design`, `code-review`, and `resolving-merge-conflicts` are also
available as `mattpocock-skills:<name>` in every project. The locally vendored `grilling` and
`research` stubs are kept anyway so `/wayfinder`'s bare-name references resolve, and because the
local `grilling` carries a repo-specific rule the plugin version does not ("never ask for a fact you
could look up").

**`wayfinder` has no plugin equivalent.** It is repo-only. If this directory is lost, `/wayfinder`
is lost with it.

## chrisbanes/skills (all 19 skills here)

- Source: https://github.com/chrisbanes/skills
- Commit: `484032a80136cc720437e8463792ca1d09c1f8b6`
- Vendored: 2026-07-09
- License: Apache-2.0 (author: Chris Banes)
- What was copied: only the `skills/<name>/SKILL.md` guidance files (pure markdown). The upstream
  repo's release scripts, CI workflows, and plugin manifests were deliberately NOT copied - nothing
  executable was vendored.

Kotlin / Jetpack Compose / Android correctness guidance. `using-chrisbanes-skills` is the router;
it points at the focused skills via relative links, so keep the folder names intact if you prune.

Highest-relevance for LEGION: `compose-side-effects`, `compose-state-hoisting`,
`compose-state-holder-ui-split`, `compose-slot-api-pattern`, `kotlin-flow-state-event-modeling`,
`kotlin-coroutines-structured-concurrency`, `compose-ui-testing-patterns`.

**This list is deliberately different from the one MIDNIGHT_AI kept.** That repo ranked
`compose-recomposition-performance` and `compose-state-deferred-reads` highest because it was
fighting cheap head-unit GPUs under a frame-clock-only motion rule. LEGION is phone-only with an
empty `ui/`, so the pressing questions are structural rather than performance: where state lives,
how a screen splits from its state holder, and how a component's varying regions are expressed.
`compose-animations` is now genuinely usable here, having been unusable under the old motion ban.

### To refresh
Re-clone the source repo and copy `skills/*` over this directory, then update the commit above.

## grillme (concept only, from mattpocock/skills)

- Source concept: https://github.com/mattpocock/skills (`skills/productivity/grill-me`)
- Vendored: 2026-07-15
- License: MIT (mattpocock/skills), covers the CONCEPT only
- What was copied: nothing verbatim. Upstream's `grill-me` is a stub - its `SKILL.md` body is
  literally "Run a `/grilling` session", `disable-model-invocation: true`, plus an
  `agents/openai.yaml` Codex display config - and defers all real behavior to an unpublished
  private `/grilling` command that isn't in the public repo. There was no implementation to vendor.
- What's local: the entire `SKILL.md` body (interview-mode rules, one-question-per-turn with
  defaults, the CLAUDE.md-specific grill checklist covering §8 frozen decisions / §5 Room / §4.3
  LiveToolbox / §13 UI map / §9 guardrails, and the §10-shaped EXECUTION-BRIEF output) was authored
  from scratch for this repo on 2026-07-15. Only the name and the "refuse to implement, interview
  until crisp, then hand off a brief" concept came from upstream.
- 2026-07-16 update: upstream published the real `/grilling` (see next section). It is 6 sentences
  and `grillme` already supersedes all of it, so `grillme` stays. One rule was backported from it:
  "never ask for a fact you could look up." Everything else in upstream's is already covered.

## mattpocock/skills (6 skills, 2026-07-16)

- Source: https://github.com/mattpocock/skills
- Commit: `9603c1cc8118d08bc1b3bf34cf714f62178dea3b`
- Vendored: 2026-07-16
- License: MIT (author: Matt Pocock)
- Install method: **vendored markdown**, deliberately NOT the plugin (`claude plugin install
  mattpocock-skills@mattpocock`) and NOT `npx skills@latest add`. The plugin installs all ~40 skills
  read-only, which would (a) collide with Claude Code's built-in `/code-review`, (b) pull in a
  TypeScript/web-shaped toolchain, and (c) make the CLAUDE.md adaptations below impossible. Same
  posture as chrisbanes above: only guidance markdown, nothing executable, adapt to this repo.

What was copied, and how much was changed:

| Skill | State |
|---|---|
| `grilling/` | **Verbatim.** The 12-line interview primitive. `wayfinder` calls it. Model-invocable, unlike `grillme` (user-invoked only) - they coexist deliberately: `grilling` is the generic primitive, `grillme` is the repo-adapted brief generator. |
| `research/` | **Verbatim.** Already convention-aware ("save it where the repo already keeps such notes"), so it self-adapts to `memory/library/`. |
| `wayfinder/` | **One edit.** The tracker pointer now names `issue-tracker.md` instead of telling you to run `/setup-matt-pocock-skills` (not installed). Body otherwise untouched. |
| `to-spec/` | **Adapted.** Publishes to `.scratch/<feature>/spec.md` instead of a real tracker; the `ready-for-agent` triage label is dropped (`triage` not installed); glossary/ADR pointers redirected to CLAUDE.md + `decisions.md`; a **Verification** section was added to the template because CLAUDE.md §10 requires one and upstream's template has none. |
| `domain-modeling/` | **Heavily adapted.** Upstream writes a `CONTEXT.md` glossary + one ADR per file in `docs/adr/`. Both are banned here: CLAUDE.md is the declared single source of truth and `memory/library/decisions.md` is the append-only decision log. The file-structure section is rewritten as a mapping table, `CONTEXT-FORMAT.md` was NOT copied, and `ADR-FORMAT.md` was rewritten to target `decisions.md` (no numbering) plus the §8 frozen-decision check. The valuable part - challenge terms, sharpen fuzzy language, concrete scenarios, cross-reference code, the 3-part ADR test - is upstream's and intact. |
| `prototype/` | **SKILL.md adapted; LOGIC.md and UI.md authored from scratch.** Upstream's branches are web-only (a `pnpm`/`bun` terminal app; UI variants on a route behind a `?variant=` URL param and a floating bottom bar). Neither exists in a Compose/Android head-unit app. LOGIC.md now targets the JVM unit-test source set (the project's one fast no-hardware seam, per `SyncMergeTest` prior art); UI.md targets `@Preview` variants at 1024x600 with a debug variant-switcher as sub-shape B. Both carry the §9 CI-enforced motion ban, the token rule, and §12's reference images. Only the concept and structure are upstream's. |

Also added: `issue-tracker.md` at this directory's root, from upstream's
`skills/engineering/setup-matt-pocock-skills/issue-tracker-local.md`, near-verbatim. `wayfinder` and
`to-spec` both link it. It documents `.scratch/` as SHORT-LIVED working state, explicitly not a
competing tracker - `memory/MEMORY.md` and `memory/library/` remain the real ones, and a finished
effort's decisions get filed to `decisions.md` via the librarian. `.scratch/` is gitignored.

`agents/openai.yaml` (Codex display config) was not copied from any of them - Claude doesn't read it.

### Deliberately NOT installed

- `grill-me`, `grill-with-docs`, `implement` - the three Kevin originally asked for. All near-empty
  stubs (7/7/15 lines) that delegate to `/grilling`, `/domain-modeling`, `/tdd`, `/code-review`.
  `grillme` already supersedes the first two for this repo.
- `code-review` - name-collides with Claude Code's built-in `/code-review`.
- `implement`, `tdd` - drive TDD then commit. Fights CLAUDE.md §10 (Opus/Fable plans, Sonnet executes
  an approved execution-spec) and this codebase's reality: most of the shipped surface (OBD, voice,
  launcher, BT audio) is hardware-validated, not headless-testable. The `senior-dev` agent already
  covers diff review against the plan.
- `to-tickets`, `triage`, `setup-matt-pocock-skills` - assume an issue tracker and triage labels.
  Solo repo; work is tracked in MEMORY.md + the library. `setup-*` would actively reshape repo
  conventions (tracker, labels, docs layout).
- `ask-matt` - a router over the full flow (`/to-spec` to `/to-tickets` to `/implement`), most of
  which isn't installed, so it would point at absent skills.
- `handoff`, `loop-me` - overlap the librarian FILE workflow and the harness's own `/loop`.

### To refresh
Re-clone the source repo at a newer commit and diff each vendored file against its upstream
counterpart. `grilling` and `research` can be copied straight over. `wayfinder` is a one-line
reapply. `to-spec`, `domain-modeling`, and `prototype` carry real local edits - merge, don't
overwrite. Then update the commit above.

## rcosteira79/android-skills (4 skills, 2026-08-23)

- Source: https://github.com/rcosteira79/android-skills
- Commit: `80406d9aacfcc030b1f9705a6d2b1407403414bc`
- Vendored: 2026-08-23
- License: MIT (author: Ricardo Costeira)
- What was copied: only `plugins/android-skills/skills/<name>/SKILL.md` for the four skills below.
  Nothing executable; the repo's plugin manifests and `scripts/` were not copied. Selection per
  `.scratch/aspect-engine/research/kotlin-skills-scout.md` (#1, #2, #3).

| Skill | State |
|---|---|
| `android-debugging/` | **One edit.** The closing pointer to `android-skills:compose` → `references/performance.md` (not vendored) now points at the vendored chrisbanes `compose-recomposition-performance` / `compose-stability-diagnostics` skills, with a note. External URL pointers (Perfetto skills, reverse-engineering plugin) kept as read-only references. |
| `gradle-build-performance/` | **One edit.** The `android-skills:android-gradle-logic` plugin-syntax link now names the locally vendored `android-gradle-logic` skill. |
| `android-gradle-logic/` | **Verbatim.** Vendored as `gradle-build-performance`'s companion; lower priority for a single-module app but small. Its "this repo's `gradle-build-performance`" reference resolves locally. |
| `android-testing/` | **Two edits.** (1) **The "Test-first (the foundation)" section was STRIPPED** - RED-GREEN-REFACTOR discipline is the `mattpocock-skills:tdd` plugin's ground, the same reason upstream `tdd` was not vendored; a vendoring note marks the strip, the intro line lost its "test-first foundation" clause, and the section's testing-setup pointer now names the locally vendored `testing-setup`. The frontmatter description still mentions test-first (kept intact per the vendoring instruction). (2) The `compose/references/focus-navigation.md` pointer (not vendored) now names the vendored `compose-focus-navigation` skill. |

### To refresh
Re-fetch `plugins/android-skills/skills/<name>/SKILL.md` at a newer commit and diff. All four carry
local edits (link retargets; `android-testing` a section strip) - merge, don't overwrite. Then
update the commit above.

## android/skills - Google official (2 skills, 2026-08-23)

- Source: https://github.com/android/skills
- Commit: `6685cac2923e3ccc7e5c385019464374699cda95`
- Vendored: 2026-08-23
- License: Apache-2.0 (author: Google LLC). The SKILL.md frontmatter says "Complete terms in
  LICENSE.txt", referring to the repo's `LICENSE.txt` (Apache-2.0) at the source URL above; it was
  not copied alongside, this citation stands in for it.
- What was copied, all verbatim, markdown only:
  - `testing/testing-setup/SKILL.md` plus its three `references/` files at their original relative
    paths (so the intra-skill links resolve unchanged):
    `references/android/develop/ui/compose/testing/common-patterns.md`,
    `references/android/studio/preview/compose-screenshot-testing.md`,
    `references/android/training/dependency-injection/hilt-testing.md`
  - `security/android-intent-security/SKILL.md` (no references dir upstream)
- Deliberately NOT vendored from this repo: `performance/r8-analyzer` (premature - no minified
  release build yet) and `profilers/android-profiler` (bundles executables, violating the
  markdown-only posture; also no current perf problem). Both are bookmarked in the scout note.

### To refresh
Re-fetch the same paths at a newer commit and copy straight over (verbatim, no local edits). Then
update the commit above.

## skydoves/android-testing-skills - the adb/ subtree only (10 skills, 2026-08-23)

- Source: https://github.com/skydoves/android-testing-skills
- Commit: `8665ed59643c90eb1056a256a6ddec161aa1cfa1`
- Vendored: 2026-08-23
- License: Apache-2.0 (author: Jaewoong Eum, skydoves)
- What was copied: the ten `adb/**/SKILL.md` files, all **verbatim**, each into a directory named
  by its frontmatter `name` (upstream's category subdirs flattened). No name collided with an
  existing skill, so none was prefixed. The repo's `compose/*`, `jvm-tests/*`, `fundamentals/*`,
  `instrumentation/*`, `kotlin/*`, and `platform/*` subtrees were deliberately NOT vendored
  (overlap with the chrisbanes set and `android-testing`); `scripts/install-skills.sh` was not
  copied (nothing executable is vendored).

| Vendored directory | Upstream path |
|---|---|
| `connecting-to-devices/` | `adb/devices/connecting-to-devices/` |
| `connecting-over-wifi/` | `adb/devices/connecting-over-wifi/` |
| `understanding-adb-architecture/` | `adb/architecture/understanding-adb-architecture/` |
| `installing-and-managing-apps/` | `adb/apps/installing-and-managing-apps/` |
| `extracting-logs-with-logcat/` | `adb/observability/extracting-logs-with-logcat/` |
| `capturing-screenshots-and-screenrecord/` | `adb/capture/capturing-screenshots-and-screenrecord/` |
| `injecting-input-and-state/` | `adb/control/injecting-input-and-state/` |
| `running-instrumented-tests-via-adb/` | `adb/tests/running-instrumented-tests-via-adb/` |
| `extracting-test-artifacts/` | `adb/transfer/extracting-test-artifacts/` |
| `scripting-adb-for-ci/` | `adb/automation/scripting-adb-for-ci/` |

### To refresh
Re-fetch `adb/**/SKILL.md` at a newer commit and copy straight over (verbatim). Then update the
commit above.

## cursor/plugins - thermo-review (1 skill, 2026-08-23)

- Source: https://github.com/cursor/plugins, path
  `cursor-team-kit/skills/thermo-nuclear-code-quality-review/SKILL.md` (first committed
  2026-05-21; fetched 2026-08-23)
- Vendored: 2026-08-23, as `thermo-review/SKILL.md`
- License: MIT (`cursor-team-kit/LICENSE`, "Copyright (c) 2026 Cursor")
- What was copied: the SKILL.md methodology only, **adapted**. Scout note:
  `.scratch/aspect-engine/research/cursor-review-skill-scout.md`.

Adaptations:
- **Rule 5 rewritten for Kotlin.** Upstream is TypeScript-flavored (`any`/`unknown`/casts).
  Local version targets `!!`, unchecked casts, platform types leaking past the interop seam,
  one-off nullable-with-sentinel modes, `runCatching`/broad-catch swallowing into defaults, and
  unjustified `@Suppress`. The two list items mentioning `any`/`unknown` were retargeted to match.
- **A LEGION-invariants preamble was ADDED that outranks the rest of the file:** CLAUDE.md §4/§5/§7
  hard rules outrank any restructure suggestion (surfaced, never demanded); findings carry
  BLOCKING / SHOULD-FIX / NIT and end with a `traced`/`reasoned`/`tested` assumptions ledger; the
  skill is the DELIBERATE per-branch pre-merge maintainability pass, never the per-commit
  senior-dev correctness review, and never a license to demand restructures of code following an
  approved plan (§8).
- **The companion Cursor agent file** (`cursor-team-kit/agents/thermo-nuclear-code-quality-review.md`)
  was **NOT vendored** - Cursor-harness plumbing only; the skill file is self-contained.
- Upstream's nine canned tone phrases were dropped (repo register is already terse); the tone
  section's four prose lines were kept. Renamed `thermo-review`; keeps upstream's
  `disable-model-invocation: true`. "PR" became "branch" throughout (no PR flow here).
- Two of its ideas were also lifted into `.claude/agents/senior-dev.md` (anti-nit priority
  ordering; the crossing-1k SHOULD-FIX flag).

### To refresh
Re-fetch the upstream SKILL.md at a newer commit and diff against the vendored copy - it carries
heavy local edits (invariants preamble, Kotlin rule 5, dropped phrases) - merge, don't overwrite.

## Considered but not installed
- `skydoves/compose-performance-skills` - heavy overlap with the above plus premature tooling
  (baseline profiles, R8 tuning) for this project. Revisit if deep release-mode profiling is needed.
- `anthropics/skills` (`skill-creator`, `pdf`/`docx`/`pptx`/`xlsx`, etc.) - bundle Python scripts
  and target document generation; tangential to this Android/Kotlin app and not worth the
  script-review burden right now.

## affaan-m/everything-claude-code - ideas only (2026-09-05)

- Source: https://github.com/affaan-m/everything-claude-code
- Fetched: 2026-09-05, from `main` (raw files named per row below; no commit pinned, because nothing
  verbatim was copied and so there is nothing to diff against on refresh)
- License: MIT ("Copyright (c) 2026 Affaan Mustafa")
- What was copied: **nothing executable and nothing verbatim.** ECC's hooks are Node scripts with
  per-session state under `~/.gateguard`; this repo's tooling is `tools/*.py` and shell lines in
  `.claude/settings.json`, so each hook below was re-authored in Python from the idea, and each
  agent or rules section was written in the existing file's voice. Scout note: the job-local
  `ecc_proposal.md` (not in the repo).

| Here | ECC source | What changed |
|---|---|---|
| `tools/hooks/guard_generated.py` (PreToolUse Edit, Write; exit 2) | `scripts/hooks/config-protection.js` | Upstream blocks edits to linter/formatter configs so an agent fixes code rather than weakening the check. Here the protected set is the GENERATED layer CLAUDE.md sections 5, 12, 13 say never to hand-edit (wiki, board, ADR index, canvases, schema JSON, `VoiceGuideData.kt`), and the message names the generator and the source to edit instead. README gets a warning only, since only its VOICE-SURFACE block is generated. |
| `tools/hooks/guard_destructive.py` (PreToolUse Bash; exit 2) | `scripts/hooks/gateguard-fact-force.js`, destructive branch | Upstream recognises `rm -rf`, `git reset --hard`, forced push, `git checkout --`, `git clean -f`, `git commit --amend` and denies with a fact-forcing prompt plus per-session state. Here: adb and pm uninstall, the pm wipe, `supabase db reset` added; checkout, clean, amend and stash deliberately not blocked; `rm -rf` allowed inside `$CLAUDE_JOB_DIR` or a tmp dir; no state file; each block says what is lost and the way around. |
| `tools/hooks/warn_room.py` (PreToolUse Edit, Write; exit 0) | `scripts/hooks/doc-file-warning.js` | Shape only: a path-filtered PreToolUse warning via `additionalContext`. Upstream warns on ad-hoc `NOTES.md`-style files; here the filter is `data/local/*.kt` and the content is CLAUDE.md section 5's Room checklist. |
| `tools/hooks/warn_long_kotlin.py` (PreToolUse Bash `git commit*`; exit 0) | `rules/common/coding-style.md` | Upstream states a file ceiling (800 lines) as a prose rule. Here it is a commit-time warning listing staged `.kt` files over the ceiling, with the number Kevin set (1000, 2026-09-05) and detekt as the enforcing gate in the build. |
| `.claude/skills/verify/SKILL.md` | `skills/verification-loop/SKILL.md` | Kept: the ordered-checks-then-READY/NOT-READY shape. Replaced: every step, with CLAUDE.md sections 6 and 8's own commands (`compileDebugKotlin -Pnokey`, `testDebugUnitTest` with totals from JUnit XML, `docs_check.py`, `voice_guide.py --check`, `decision_debt.py`, `sql_check.py`), plus the one-Gradle-writer precondition. Dropped: the 80% coverage target and the secrets grep. |
| `.claude/agents/auditor.md`, "Swallowed failures" | `agents/silent-failure-hunter.md` | Upstream's five hunt targets (empty catch, weak logging, dangerous fallbacks, lost propagation, missing timeout/rollback) re-expressed as the Kotlin shapes this codebase produces, anchored to L-2026-09-04. |
| `.claude/agents/auditor.md`, "Review the tests" | `agents/pr-test-analyzer.md` | Upstream's "meaningful assertions, flaky patterns, isolation" phase turned into a checklist of the failures seen here (fake asserting on its inputs, two clocks, shared Room, empty fixture), anchored to L-2026-09-05. No coverage percentage. |
| `.claude/agents/auditor.md`, "Postgres diffs" | `agents/database-reviewer.md` | Upstream's Postgres/Supabase checklist minus the multi-tenant and pooling items, plus the four LEGION-specific checks (constraint case per the 2026-08-29 incident, `private.apply_household_rls`, `supabase_realtime` membership, unique index behind `on conflict`). Grammar is left to `tools/sql_check.py`. |
| `TEAM.md`, "Completion contract" | `rules/common/agents.md`, "Delegation Completion Contract" | Upstream's three points (final message is the deliverable; delegator owns collection; decompose only when it does not fit) kept in substance, reworded, and joined to this repo's existing assumptions-ledger and relay-tag rules. |
| `.claude/agents/builder.md`, "Reaching green honestly" | `agents/kotlin-build-resolver.md` | Two of upstream's rules - never suppress a warning without approval; stop after three failed attempts at one error - as two bullets. The rest of that agent (a `./gradlew build` loop, detekt/ktlint fixing) was not taken; `builder` already builds. |

### Deliberately NOT taken

`tdd-guide` and `rules/testing` (coverage percent as a gate, mocks over fakes; this repo has fakes
and no MockK), `continuous-learning` (silent prompt self-modification; `lessons.md` exists so a rule
is written by someone and read next run), the telemetry and Plan Canvas hooks, every team-scale,
marketing, SEO and commercial item (CLAUDE.md section 2), `rules/git-workflow` (`feat:` prefixes;
commits here are prose and `devlog.py` reads them), and the `android-clean-architecture` skill (an
architecture decision, Kevin's, item 10 of the proposal). Full inventory in the scout note.

### To refresh
There is nothing to diff. Re-read the upstream files named in the table if the idea behind one of
these needs re-examining; the local files carry no upstream text.
