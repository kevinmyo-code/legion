---
name: verify
description: Run LEGION's full pre-commit verification in one pass - compile with no baked key, the unit suite with totals read from the JUnit XML, the docs, voice-guide, decision-debt and SQL checks - and print READY or NOT READY with the failing step named. Use before claiming work is done, before a commit, and before merging dev into main.
---

<!--
ADAPTED VENDORING. Original: everything-claude-code's `skills/verification-loop/SKILL.md`, MIT
licensed ("Copyright (c) 2026 Affaan Mustafa"), from github.com/affaan-m/everything-claude-code
(fetched 2026-09-05). What survives from upstream: the shape - a fixed ordered list of checks, each
PASS/FAIL, ending in a READY / NOT READY verdict with the failures listed. What was replaced: every
step. Upstream's phases are TypeScript/Python build, type check, lint, tests with an 80% coverage
target, a secrets grep and a diff review. LEGION's are the commands CLAUDE.md sections 6 and 8
already name, the JUnit-XML rule, the docs/voice/decision-debt/SQL checks, and the
one-Gradle-writer precondition. No coverage number: a percentage here rewards Robolectric churn.
See `.claude/skills/ATTRIBUTION.md`.
-->

# Verify

CLAUDE.md section 8: `compileDebugKotlin` + `testDebugUnitTest` green before each commit. Section 6:
read test totals from the JUnit XML, never the console summary. This skill is that sentence as a
procedure, so "green" means the same thing every time someone says it.

Run every step. Do not stop at the first failure - the report lists all of them. Do not skip a step
because "nothing in that area changed"; the docs check exists because a rename three directories away
breaks a path in `docs/`.

## 0. Precondition: one Gradle writer

Only one agent may run Gradle in this tree at a time. Two concurrent runs corrupt each other and an
empty or missing test-results file reads exactly like a pass to anything that only checks an exit
code. Before step 1:

```
git status --short
```

Unfamiliar dirty files, or a `.gradle`/`app/build` lock error on the first command, means another
agent is building here. Take a worktree or wait. Never report a verdict from a contended run.

## 1. Compile, honest path

```
./gradlew compileDebugKotlin -Pnokey
```

`-Pnokey` is the no-baked-key path, the one a stranger's clone takes. A compile that only passes
with a key in `local.properties` is not green.

## 2. Unit suite, totals from the XML

Record the time, then run:

```
./gradlew testDebugUnitTest
```

Then read the totals from the XML, never from Gradle's summary line:

```
python - <<'EOF'
import glob, os, time, xml.etree.ElementTree as ET
files = glob.glob('app/build/test-results/testDebugUnitTest/*.xml')
t = f = e = s = 0
newest = 0
for p in files:
    a = ET.parse(p).getroot().attrib
    t += int(a.get('tests', 0)); f += int(a.get('failures', 0))
    e += int(a.get('errors', 0)); s += int(a.get('skipped', 0))
    newest = max(newest, os.path.getmtime(p))
print(f"files={len(files)} tests={t} failures={f} errors={e} skipped={s}")
print("newest result written:", time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(newest)))
EOF
```

Three things make this step FAIL: `failures + errors > 0`; `files == 0`; or the newest result is
older than the run you just started (a stale directory from a previous run reads as a pass). Name
the failing test classes from the XML (`<testcase>` elements with a `<failure>` child).

## 3. Docs paths and ADR links

```
python tools/docs_check.py
```

Fails on a source path named in `docs/` that no longer exists, on bad ADR frontmatter, on a
one-sided supersession link, on a dead wikilink.

## 4. Voice surface drift

```
python tools/voice_guide.py --check
```

Fails when a voice tool in `service/LiveToolbox.kt` has no copy in `tools/voice_guide_copy.py`, or
when the generated `docs/voice.html`, `ui/help/VoiceGuideData.kt` or the README block have drifted
from what the copy would produce. A drift is fixed by editing the copy file and running the script
without `--check`, never by editing the generated file.

## 5. Decision debt

```
python tools/decision_debt.py --quiet
```

Fails when a resolved decision ticket has no build ticket behind it (CLAUDE.md section 12). The
commit hook runs this too; a READY verdict that then trips the hook was a lie.

## 6. SQL parse check, when it applies

```
python tools/sql_check.py
```

Run it whenever `supabase/migrations/` is in the diff; running it otherwise is harmless. It parses
every migration with Postgres's own grammar. It does NOT check plpgsql bodies or semantics - a green
run means "well formed", never "will apply". If it exits 2 with "pglast is not installed", the step
is SKIPPED (say so, with the install line it prints), not passed and not failed.

## 7. The diff, once

```
git status --short
git diff --stat
```

Every file listed is one you meant to change. A generated file (`docs/index.html`, `docs/board.json`,
`vault/Board.md`, `*.canvas`, `app/schemas/**`) modified without its source changing is a finding.

## Report

```
VERIFY
  1 compile -Pnokey        PASS | FAIL
  2 testDebugUnitTest      PASS | FAIL   files=N tests=N failures=N errors=N skipped=N   newest=<time>
  3 docs_check             PASS | FAIL
  4 voice_guide --check    PASS | FAIL
  5 decision_debt          PASS | FAIL
  6 sql_check              PASS | FAIL | SKIPPED (why)
  7 diff                   N files: <list>

READY | NOT READY
```

NOT READY names each failing step and pastes the failing command's last relevant lines verbatim -
the compiler's words, the failing test class, the path `docs_check` could not find. No paraphrase.

Then the assumptions ledger, per CLAUDE.md section 8: what was `tested` by this run, what is
`reasoned`, and what is owed `on-device`. A green suite says nothing about the phone; say so in
words when the change touches anything the phone alone can settle.
