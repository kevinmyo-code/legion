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

## Considered but not installed
- `skydoves/compose-performance-skills` - heavy overlap with the above plus premature tooling
  (baseline profiles, R8 tuning) for this project. Revisit if deep release-mode profiling is needed.
- `anthropics/skills` (`skill-creator`, `pdf`/`docx`/`pptx`/`xlsx`, etc.) - bundle Python scripts
  and target document generation; tangential to this Android/Kotlin app and not worth the
  script-review burden right now.
