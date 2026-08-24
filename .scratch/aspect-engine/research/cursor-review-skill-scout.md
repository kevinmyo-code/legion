# Scout: Cursor's "thermo-nuclear" code review skill

Date: 2026-08-23. Question: Kevin heard "cursor dropped a thermo nuclear code review skill" -
what is it, can we integrate?

## What it actually is

- **Name:** `thermo-nuclear-code-quality-review`. A **192-line plain-Markdown SKILL.md** in
  Cursor's official open-source plugins repo, inside the `cursor-team-kit` plugin.
- **Where:** `github.com/cursor/plugins`, path
  `cursor-team-kit/skills/thermo-nuclear-code-quality-review/SKILL.md`, plus a thin companion
  agent def at `cursor-team-kit/agents/thermo-nuclear-code-quality-review.md`. Also listed on
  Cursor's marketplace (`cursor.com/marketplace/skills/thermo-nuclear-code-quality-review`; a
  second marketplace entry `thermo-nuclear-review` exists - community variant, not the canonical
  one).
- **When:** first committed **2026-05-21** ("Add thermo-nuclear-code-quality-review skill and
  agent"), briefly relocated into a "Thermos" plugin 2026-05-28 and restored the same day
  (GitHub API commit log, `traced`). The recent noise is social, not a new release: Eric
  Zakariasson (Cursor) tweeted it as "the most used skill internally at cursor right now",
  Matt Pocock and daily.dev amplified in August. So "dropped recently" = went viral recently.
- **What it does:** not a correctness review. An **extremely strict maintainability review**:
  frontmatter description says "abstraction quality, giant files, and spaghetti-condition
  growth". `disable-model-invocation: true` - explicitly invoked, never auto-fired.

## Methodology (read in full, `traced` against the raw file)

1. **Core prompt:** deep quality audit of the branch's changes; "be ambitious"; rethink
   structure to improve quality "without impacting behavior".
2. **Rule 0, the signature move - "code judo":** do not stop at local cleanup; look for
   restructurings that make whole branches/helpers/modes/layers "disappear entirely";
   "delete complexity rather than rearrange it"; prefer the solution that "makes the code
   feel inevitable in hindsight".
3. **Seven numbered non-negotiables:** (1) no PR pushes a file from under 1k lines to over 1k
   without a very strong reason - presumptive blocker; (2) no spaghetti growth - new ad-hoc
   conditionals in unrelated flows are a design problem, not a nit; (3) clean the design, don't
   rubber-stamp working code; (4) direct boring code over magic; flag thin/identity wrappers;
   (5) type-boundary cleanliness - question `any`/`unknown`/casts/optionality (TS-flavored);
   (6) logic in the canonical layer, reuse canonical helpers, no architectural drift;
   (7) unnecessary sequential orchestration and non-atomic updates are design smells.
4. **13 primary review questions**, an 18-item "flag aggressively" list, a 16-item "preferred
   remedies" list (delete layers, reframe state models so conditionals disappear, typed
   dispatchers over condition chains, split big files).
5. **Output contract:** findings in a fixed 7-level priority order (structural regressions
   first, legibility last); "do not flood the review with low-value nits"; "smaller number of
   high-conviction comments".
6. **Approval bar:** approval is NOT "behavior seems correct" - it is an 8-item checklist of
   absences, plus a 6-item list of **presumptive blockers** (file crosses 1k lines, ad-hoc
   branching tangles an existing flow, feature checks scattered across shared code, duplicate
   of a canonical helper, unnecessary wrapper/cast churn, preserved incidental complexity when
   a code-judo move is visible) that only an explicit author justification can waive.
7. **Tone section** with nine canned review phrases ("this refactor moves complexity around,
   but doesn't really delete it").

The companion **agent md** is Cursor-harness plumbing (parent collects `git diff` + file
contents via parallel shell/explore Task calls, feeds this agent as a subagent). Skip it; the
skill file is self-contained.

## License and portability

- **MIT.** Repo README states MIT and `cursor-team-kit/LICENSE` is a full MIT text,
  "Copyright (c) 2026 Cursor" (`traced` - fetched both). Vendoring/adapting with attribution
  is clean, same posture as our vendored Chris Banes skills; add a line to
  `.claude/skills/ATTRIBUTION.md`.
- **Technically portable:** the SKILL.md contains **zero Cursor-specific tool calls, UI hooks,
  or APIs**. Pure methodology prose. Only TS-isms (`any`, `unknown`, casts) need translating
  to Kotlin (`!!`, unchecked casts, platform types, nullable-mode booleans).

## Overlap vs what LEGION already runs

| Concern | senior-dev (Ravi) | mattpocock code-review | built-in /code-review | thermo-nuclear |
|---|---|---|---|---|
| CLAUDE.md hard rules (gate, cents, no-backend) | YES, the core | no | no | no |
| Spec drift vs plan | YES | YES (Spec axis) | no | no |
| Correctness at seams | YES | no | YES | no |
| Fowler smells (wrapper, duplication, speculative generality) | no | YES (12-smell baseline) | partial | YES, harsher |
| Ambition mandate ("delete complexity", code judo) | no | no | no (simplify is local) | **YES - unique** |
| Hard file-size threshold as blocker | no | no | no | **YES - unique** |
| Explicit approval bar / presumptive blockers | verdict format only | no | no | **YES - unique** |
| Priority-ordered output + anti-nit rule | no | no | partial | **YES** |
| Canonical-layer / helper-reuse drift | partial (via arch violations) | Feature Envy-ish | no | YES, explicit |
| Sequential-orchestration / atomicity smell | partial (coroutine seams) | no | no | YES |
| Assumptions ledger (traced/reasoned/tested) | **YES - ours, unique** | no | no | no |

Genuinely novel for us: the **ambition mandate**, the **1k-line presumptive blocker**, the
**approval bar as an explicit blocker list**, and the **priority ordering + anti-nit output
contract**. The smell content itself largely duplicates the mattpocock Fowler baseline, just
angrier.

## Tension to manage

- Thermo-nuclear pushes reviewers to demand **restructurings**. LEGION's working model (§8) is
  the opposite during execution: follow the plan exactly, one logical change per commit, stop
  and surface forks. So this cannot be the per-commit senior-dev bar - a reviewer demanding a
  code-judo restructure mid-plan is spec drift by our own rules.
- Right altitude: **per-branch / per-effort maintainability pass**, run when a feature branch
  is done and before dev merge, or as a periodic audit. That matches how Cursor uses it (on a
  finished PR, users report 30-min runs).
- The 1k rule as written only fires on a PR **crossing** 1k, so it will not endlessly relitigate
  `service/LiveToolbox.kt` (7,106 lines) or `ai/AriaBrain.kt` (1,067) - but its decomposition
  remedies are exactly the conversation LiveToolbox is owed someday.

## Verdict: INTEGRATE (adapted), as a new skill, not a senior-dev rewrite

1. **Vendor an adapted copy** at `.claude/skills/thermo-review/SKILL.md` (MIT, attribution line
   in ATTRIBUTION.md). Keep: rule 0 code-judo mandate verbatim; the seven non-negotiables with
   rule 5 rewritten for Kotlin (`!!`, unchecked casts, platform types, one-off nullable modes);
   the flag-aggressively and preferred-remedies lists; the priority-ordered output contract and
   anti-nit rule; the approval bar with its presumptive blockers. Drop: the Cursor agent md,
   the marketplace framing. Add: LEGION's own review invariants - findings still end with an
   assumptions ledger, verdicts stay BLOCKING / SHOULD-FIX / NIT, and CLAUDE.md hard rules
   outrank any restructure suggestion (a code-judo move that touches the reconciliation gate is
   surfaced, never demanded).
2. **Scope it per-branch, pre-merge** - explicitly NOT the per-commit senior-dev pass. Like the
   original's `disable-model-invocation: true`, dispatch it deliberately.
3. **Lift two small pieces into senior-dev itself** (cheap, no tension): the anti-nit output
   rule ("smaller number of high-conviction comments; do not flood with nits when structural
   issues exist") and the file-crossing-1k check as a SHOULD-FIX-level flag.
4. Do not adopt its tone phrases wholesale; our register is already terse.

Not done here (per brief): nothing vendored, `.claude/` untouched, nothing committed.

## Sources

- https://github.com/cursor/plugins/blob/main/cursor-team-kit/skills/thermo-nuclear-code-quality-review/SKILL.md (read in full via raw.githubusercontent.com)
- https://github.com/cursor/plugins/blob/main/cursor-team-kit/agents/thermo-nuclear-code-quality-review.md
- https://github.com/cursor/plugins (README, license section) and `cursor-team-kit/LICENSE`
- GitHub API commit history for the SKILL.md path (dates 2026-05-21 / 2026-05-28)
- https://cursor.com/marketplace/skills/thermo-nuclear-code-quality-review
- https://x.com/ericzakariasson/status/2057521364622553442 (Cursor employee: most-used internal skill)
- https://daily.dev/posts/can-cursor-s-hardcore-review-skill-stop-the-slop--sjkerp9tv
- https://www.linkedin.com/posts/mapocock_cursor-shipped-a-thermo-nuclear-code-review-activity-7465700583425855488-yieg

## Assumptions ledger

- Skill text, license text, commit dates, file paths: `traced` (fetched raw files and GitHub API directly).
- "Went viral in August, released in May": `traced` for the May commit date; `reasoned` for the August-virality framing (search-result recency + tweet coverage, exact tweet dates not independently verified).
- Marketplace `thermo-nuclear-review` being a non-canonical variant: `reasoned` (not fetched).
- Overlap table rows for senior-dev and mattpocock code-review: `traced` (read both local files in full). Built-in /code-review row: `reasoned` from its harness description only.
- 30-min PR runtimes: `reasoned` (single user tweet, anecdotal).
- LiveToolbox.kt 7,106 lines / AriaBrain.kt 1,067 lines: `traced` (wc -l).
