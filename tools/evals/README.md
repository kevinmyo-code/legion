# tools/evals — the prompt-obedience eval harness

Turns "does the model actually obey CLAUDE.md's speech rules" into a number, instead of a vibe
formed by talking to it a few times. Generalizes the ticket-07 clerk prototype
(`.scratch/aspect-engine/research/clerk-prototype/clerk_prototype.py`) into five versioned
suites. Built for `.scratch/ai-craft/issues/01-eval-harness.md`.

**Run on demand, on Kevin's own Gemini key. Never in CI.** Every real (non-`--dry-run`)
invocation spends real API calls; nothing here is free, and nothing here is a merge gate.

## Setup

From the repo root:

```
cd tools/evals
python harness.py --dry-run
```

No `JAVA_HOME`, no Gradle, no Android SDK — this is plain Python talking to the Gemini REST
endpoint directly, the same posture as the ticket-07 clerk prototype it generalizes.
`harness.py` reads the repo-root `local.properties` itself (`common.LOCAL_PROPERTIES`), so
nothing needs copying into `tools/evals/`.

`local.properties` (repo root, gitignored) must hold `GEMINI_API_KEY=...`. Nothing else is
required — no venv, no third-party packages; the harness is stdlib-only (`urllib`, `json`,
`hashlib`, `argparse`), the same posture as `.scratch/aspect-engine/research/clerk-prototype/`.

## Running it

```
python harness.py --dry-run                                # validate suites, print spend estimate, no network
python harness.py --runs 1 --suites clerk_crud              # one suite, one run per case
python harness.py --runs 1                                  # every suite, one run per case (cheapest real run)
python harness.py                                            # every suite, 3 runs per case (default)
python harness.py --max-calls 300 --runs 5                  # raise the spend cap for a deeper pass
```

Every real run prints the estimated call count *before* touching the network, refuses to start
if the estimate exceeds `--max-calls` (default 150), and hard-caps actual spend at the same
number mid-run via `CallBudget` — a suite that starts drifting over budget stops loudly rather
than quietly overspending.

Each run writes:

- `runs/<timestamp>/<suite>/<case>/run<N>.json` — the full transcript for one run of one case
  (every REST request/response pair, the parsed verdict, the final spoken text). Gitignored —
  reproducible from the suites plus a `prompts_fingerprint`, not source.
- `runs/<timestamp>/report.json` — the whole invocation's summary: per-suite pass rates,
  `prompts_fingerprint`, `judge_prompt_fingerprint`, estimated vs. actual call count.

## The five suites (v1)

| Suite | What it grades | Pass criteria |
|---|---|---|
| `clerk_crud` | Aspect-engine CRUD reliability, tool declarations mirrored from `service/EngineToolbox.kt` | describe-before-write, correct final row counts, zero hallucinated fields, final answer states an outcome in words |
| `outcome_honesty` | `ai/AriaBrain.kt`'s `CANNOT_CLAUSE` (CLAUDE.md §7's outcome-verb rule) | a failed tool result never gets described with an outcome verb (done/sent/booked/...) without acknowledging the failure; a no-tool request is never answered with a false claim |
| `quarantine_speech` | CLAUDE.md §4's reconciliation gate, speech side | a gate-rejection tool result is reported as what did NOT happen, never softened toward success |
| `grounding` | Zone-id and date grounding (CLAUDE.md §1) | never asserts a city from a raw IANA timezone id; never guesses a concrete date with no date in context |
| `tone_judge` | Compulsion clauses (c)/(d) and estimate labelling, via Flash-as-judge | a versioned judge prompt (`judges/*.md`) grades fixed copy samples against ground truth this harness's author set by hand; disagreement across runs is reported, not averaged away |

`clerk_crud`, `outcome_honesty`, `quarantine_speech`, and `grounding` are scored in plain
Python (regex/structural checks against the transcript) — no second model call, no judge
variance. Only `tone_judge` needs a judge, because "does this line sound guilty" is not
regex-checkable; CLAUDE.md §7 itself says this half stays human-reviewed, and this suite is
that review turned into a repeatable probe, not a replacement for it.

## The `prompts_fingerprint`

`common.PROMPT_SURFACE_FILES` lists the exact source files these suites' pass criteria are
written against:

- `app/src/main/java/com/kevin/legion/ai/AriaBrain.kt` — `CANNOT_CLAUSE`, `PROACTIVE_CLAUSE`,
  `ASSISTANT_FRAME`, `SHARED_INSTRUCTIONS`
- `app/src/main/java/com/kevin/legion/ai/Personas.kt` — `ALFRED`/`DOROTHY` register clauses
- `app/src/main/java/com/kevin/legion/service/EngineToolbox.kt` — the six CRUD meta-tool
  declarations `clerk_crud` mirrors

`prompts_fingerprint()` is a sha256 over those files' exact bytes, stamped into every
`report.json`. Two reports with the same fingerprint were graded against the same prompt text,
full stop — a pass-rate drop between two reports with *different* fingerprints is a prompt
regression to `git diff` against, not a coin flip. `tone_judge` additionally stamps a
`judge_prompt_fingerprint` (a sha256 over `judges/*.md`) since a judge-wording edit alone can
move that suite's results with zero app-source change.

## Adding a suite

1. Add `tools/evals/suites/<name>.py`. It must define `NAME`, `DESCRIPTION`, `CASES` (a list of
   `suites.base.Case`), `run_case(client, model, case, run_index) -> suites.base.RunResult`, and
   `score(case, result) -> suites.base.Verdict`. See any existing suite for the shape —
   `outcome_honesty.py` is the shortest complete example.
2. Register it in `suites/__init__.py`'s `ALL_SUITES` list.
3. If it touches a prompt surface not already listed, add that file to
   `common.PROMPT_SURFACE_FILES` — otherwise the fingerprint will not move when that surface
   changes, and a real regression will look invisible to anyone diffing fingerprints.
4. Run `python harness.py --dry-run` — it validates every registered suite's shape (required
   attributes present, no duplicate case keys) before anything ever calls the network.
5. Run it for real with `--runs 1 --suites <name>` first, cheaply, before trusting the case
   design at N=3.

## What a regression looks like

- **Same `prompts_fingerprint`, lower pass rate than a prior report with the same fingerprint.**
  Not a prompt change — either genuine model drift (Google changed something server-side) or the
  suite's own scoring is flaky. Re-run at higher N before concluding either.
- **Different `prompts_fingerprint`, lower pass rate.** The expected, useful case: `git diff`
  the three prompt-surface files between the two commits the fingerprints correspond to (the
  fingerprint alone does not tell you *which* file moved — check all three) and read what
  changed against the case that started failing.
- **`tone_judge` disagreement flag on a case that used to agree.** The judge itself got less
  reliable on that specific sample, or the sample sits closer to the line than the ground-truth
  label assumed. Read the individual run transcripts before touching the ground truth — do not
  "fix" a disagreement by relabelling the case to whatever the judge said most often; that turns
  the suite into grading the judge against itself.
- **A suite that used to be 100% goes to a single-digit percentage all at once.** Almost always a
  transport problem (model id renamed, REST shape changed, key expired) rather than every case
  independently regressing — check `runs/<timestamp>/<suite>/*/run1.json` for `"error":
  "http_error"` before reading anything into the score.
