---
name: verify
description: Run LEGION's full verification in one pass - compile with no baked key, detekt against its baseline and the unit suite in GitHub Actions on the pushed commit with totals read from the JUnit XML, the docs, voice-guide, decision-debt and SQL checks - and print READY or NOT READY with the failing step named. Use before claiming work is done, before a commit, and before merging dev into main.
---

<!--
ADAPTED VENDORING. Original: everything-claude-code's `skills/verification-loop/SKILL.md`, MIT
licensed ("Copyright (c) 2026 Affaan Mustafa"), from github.com/affaan-m/everything-claude-code
(fetched 2026-09-05). What survives from upstream: the shape - a fixed ordered list of checks, each
PASS/FAIL, ending in a READY / NOT READY verdict with the failures listed. What was replaced: every
step. Upstream's phases are TypeScript/Python build, type check, lint, tests with an 80% coverage
target, a secrets grep and a diff review. LEGION's are the commands CLAUDE.md sections 6 and 8
already name, the JUnit-XML rule, the docs/voice/decision-debt/SQL checks, and the
pushed-commit precondition (the suites run in CI). No coverage number: a percentage here rewards Robolectric churn.
See `.claude/skills/ATTRIBUTION.md`.
-->

# Verify

CLAUDE.md section 8: `compileDebugKotlin` + `testDebugUnitTest` green before the work is called
done. Section 6: they run in GitHub Actions on the pushed commit, and test totals are read from the
JUnit XML, never the console summary. This skill is that sentence as a procedure, so "green" means
the same thing every time someone says it.

Run every step. Do not stop at the first failure - the report lists all of them. Do not skip a step
because "nothing in that area changed"; the docs check exists because a rename three directories away
breaks a path in `docs/`.

## 0. Precondition: the commit is pushed

CI tests only what is on the remote, so an unpushed change is "not tested", whatever ran locally.

```
git status --short
git rev-parse HEAD
git push origin HEAD:<your-branch>
```

Use a branch of your own: one run per workflow per branch, and a newer run on the same branch
cancels the older one. A cancelled run is never green.

## 1-3. Compile with no baked key, detekt, the unit suite: one CI run

```
gh workflow run android.yml --ref <your-branch>
gh run list --workflow android.yml --commit <full-sha> --json databaseId -q '.[0].databaseId'
gh run watch <run-id> --exit-status
```

`android.yml` runs `./gradlew compileDebugKotlin testDebugUnitTest -Pnokey` (the no-baked-key path,
the one a stranger's clone takes) and `:app:detekt` against its baseline
(`config/detekt/baseline.xml`; CLAUDE.md's "Code health" ruling): the detekt job fails only on
findings past the baseline's ceiling. A new finding is fixed, never hidden by regenerating the
baseline. Check that the run's head sha is the commit you pushed.

Then read the totals from the XML, never from Gradle's summary line:

```
gh run download <run-id> -n android-test-results -D <dir>
python3 .github/scripts/junit_totals.py '<dir>/*.xml'
```

FAIL when the run did not conclude `success` on your sha, `failures + errors > 0`, or no XML came
back (an empty results directory reads like a pass). Name the failing test classes from the XML.
Head unit, server and web changes use `headunit.yml`, `server.yml` and `frontend.yml` the same way
(artifacts `headunit-test-results`, `server-junit-xml`, `frontend-junit-xml`).

## 4. Docs paths and ADR links

```
python tools/docs_check.py
```

Fails on a source path named in `docs/` that no longer exists, on bad ADR frontmatter, on a
one-sided supersession link, on a dead wikilink.

## 5. Voice surface drift

```
python tools/voice_guide.py --check
```

Fails when a voice tool in `service/LiveToolbox.kt` has no copy in `tools/voice_guide_copy.py`, or
when the generated `docs/voice.html`, `ui/help/VoiceGuideData.kt` or the README block have drifted
from what the copy would produce. A drift is fixed by editing the copy file and running the script
without `--check`, never by editing the generated file.

## 6. Decision debt

```
python tools/decision_debt.py --quiet
```

Fails when a resolved decision ticket has no build ticket behind it (CLAUDE.md section 12). The
commit hook runs this too; a READY verdict that then trips the hook was a lie.

## 7. SQL parse check, when it applies

```
python tools/sql_check.py
```

Run it whenever `supabase/migrations/` is in the diff; running it otherwise is harmless. It parses
every migration with Postgres's own grammar. It does NOT check plpgsql bodies or semantics - a green
run means "well formed", never "will apply". If it exits 2 with "pglast is not installed", the step
is SKIPPED (say so, with the install line it prints), not passed and not failed.

## 8. The diff, once

```
git status --short
git diff --stat
```

Every file listed is one you meant to change. A generated file (`docs/index.html`, `docs/board.json`,
`vault/Board.md`, `*.canvas`, `app/schemas/**`) modified without its source changing is a finding.

## Report

```
VERIFY
  0 pushed                 <branch> <full sha>
  1 compile -Pnokey        PASS | FAIL   CI run <id> on <sha>
  2 detekt                 PASS | FAIL   CI run <id>
  3 testDebugUnitTest      PASS | FAIL   files=N tests=N failures=N errors=N skipped=N   (CI run <id>)
  4 docs_check             PASS | FAIL
  5 voice_guide --check    PASS | FAIL
  6 decision_debt          PASS | FAIL
  7 sql_check              PASS | FAIL | SKIPPED (why)
  8 diff                   N files: <list>

READY | NOT READY
```

NOT READY names each failing step and pastes the failing command's last relevant lines verbatim -
the compiler's words, the failing test class, the path `docs_check` could not find. No paraphrase.

Then the assumptions ledger, per CLAUDE.md section 8: what was `tested` by this run, what is
`reasoned`, and what is owed `on-device`. A green suite says nothing about the phone; say so in
words when the change touches anything the phone alone can settle.
