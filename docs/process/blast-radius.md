---
title: Blast-radius tiers and review-finding checks
tags: [docs, process]
---

# Blast-radius tiers

How much review a change gets before it merges depends on what it can break. Every change gets a
tier from the files it touches; the tier sets the review depth. Once its review passes and CI is
green, a change merges to `dev` without waiting for a person.

**Some things are never just a merge.** An engine deploy, a phone or head unit publish, a content
load and a phone install need the owner's word in every tier, and no brief can lift that. A merge
that changes a rendered site file under `docs/` is also a publish: GitHub Pages serves `docs/` from
`dev`, so a change to a page (`.html`), a script, style or data file it loads (`.js`, `.css`,
`.json`), a privacy page, the Pages config (`CNAME`, `.nojekyll`, `_config.yml`) or an image at the top
of `docs/` needs the owner's word too (the default until he rules otherwise). Plain `.md` and `.txt`
docs are not: the repository is public already, so their appearing on Pages adds nothing. The script
prints PUBLISH for the files that count.

## The tiers

| Tier | What it covers | Review before merge |
|---|---|---|
| GREEN | Docs (`.md`, `.txt` and images under `docs/`, `vault/`, `.scratch/`, `memory/`; READMEs; top-level `.md`); tests whose subject is not RED; `strings.xml`; styling files by type (CSS, images, drawables) | CI green + one light review: a single pass over the task text and the diff, ending "Confirmed: SHIP at &lt;sha&gt;" |
| YELLOW | App, engine and tool logic that touches none of the RED areas; test fixtures the ship freezes; the detekt baseline (with its own check); any path the script does not recognise | CI green + one blind review ending "Confirmed: SHIP at &lt;sha&gt;" |
| RED | Agent rules, hooks and MCP config (`CLAUDE*.md` and `.claude/` at any depth, `.mcp.json`, top-level `AGENTS.md` and `TEAM.md`); the worker hook and containment; scripts the commit hooks run; auth and tokens; money, figure and privacy checks; read-through and crisis guards; migrations and schema; deploy, ship and CI tooling (these checks included, `conftest.py`, the detekt rule config); build scripts and dependencies; voice gate rules and the code the gate runs; the public demo's honesty guards | CI green + blind review with adversarial probes, repeated until "Confirmed: SHIP at &lt;sha&gt;" |

The reviewer gets only the task text and the full diff, never the author's plan or summary.

**The highest tier wins.** One RED file makes the whole change RED.

**Tests and test data of a RED subject are RED.** A test under a test root whose name points at a RED
subject (gate, read-through, crisis, money, auth, vault, privacy, migration, schema, relay, the
worker, ingest, sign-in, connect session, household, the synthetic finance allowlist, the ship and
release tools, the gate's own cases, bank statements and sync, dedup, categorising, tenant isolation,
visibility, write guards, spoken figures) is RED: weakening a guard's test weakens the guard. The name
list is a rough rule and will miss some tests: a reviewer who spots a test of a RED subject that tiers
lower raises its tier, and the name goes into the script.

**Case does not lower a tier.** Matching ignores case, and a path that differs only in case from
another tracked path is RED: on a Windows checkout the two collide.

**A file list cannot see logic.** GREEN styling is judged by file type only: a Compose screen or a
`.tsx` component is YELLOW even when the change is visual. A reviewer who finds a change doing more
than its tier says raises the tier.

**Anything the gates workflow runs is RED.** It runs with the voice gate's API key present, so the
gate cases, the eval scripts, the gate's test code (the phone's `voiceeval` tests, `realrun_*` and every
test module they import, kept current by a test) and the Gradle build scripts are RED. The rest of
the phone's test classpath also runs in that job; only a required reviewer on the `gates` environment
closes that fully, and that is a repository setting for the owner, never an agent.

## Merging

- The author never merges its own change.
- A merge needs "Confirmed: SHIP at &lt;sha&gt;" where the sha is the exact head being merged. A later
  commit needs a new review, at the tier of the full diff.
- Every workflow that ran on that sha must be green, not just the checks below: a docs change, for
  example, relies on the Server workflow's docs-corpus check.
- `dev` to `main`: a fast-forward of commits that each merged to `dev` this way needs no further
  review, only every workflow green on `main`'s new head.

## The script

`tools/blast_radius.py` prints the tier and the reason for each file:

```
git show origin/dev:tools/blast_radius.py | python - --base origin/dev --head <sha>   # trusted
python tools/blast_radius.py --short    # one line, for a board card or a brief
```

The trusted run is the copy on `dev`, never the branch's own: otherwise a change could edit the
script to lower its own tier. Run from a checkout, the script also loads the base copy from git (the
base tip's copy, else the merge base's) and reports the higher of the two. CI runs the base copy in its
own step. The script counts deleted and
renamed-away paths, and exits 2 on an empty file list rather than calling nothing GREEN.

Use the `--short` line in the board card's status and at the top of every review brief.

## Review-finding checks (CI)

Findings reviewers kept repeating are now checks, in `tools/checks/`, run by
`.github/workflows/checks.yml` on pushes to `dev`, `main`, `up/**`, `integrate/**` and `ship/**`, and
on pull requests. A push to a `feat/` or `fix/` branch runs nothing: push the commit to `up/<name>` to
check it, or run `python tools/checks/run_all.py --base origin/dev` locally. Each check reads only the
lines a change adds, so existing debt never fails a build.

| Check | Fails on | Warns on |
|---|---|---|
| (a) Clock times in docs and comments | a log-like time anywhere (with seconds, after a date, or with a zone name); a bare time in a doc | a bare time in a code comment; a time range (a time-of-day rule) |
| (b) Code files over 600 lines | a new code file over 600 | a new test file over 600; an existing file that crosses 600, or is over and grows. A pure move is compared with the file it came from |
| (c) Schema files | an edited or deleted migration; a Django migration number already used in its app; a Room schema version with no `MIGRATION_<n-1>_<n>` inside `.addMigrations(...)`; an edited shipped schema JSON | a new Django migration with a destructive operation; a failure accepted by a `migration-edit-ok:` marker |
| (d) Em dashes in user-facing copy | `strings.xml` (phone and head unit); the copy modules | web app source, phone and head unit Kotlin, the demo's engine code |
| (e) Offhand quotes | not checked: whether a quote was a deliberate ruling is not in its text | |
| (f) Detekt baseline | a finding (rule + text) the base never listed; a finding listed more often, unless the extra entries sit in files this change adds and the base listed it under a file this change modifies or deletes (code split out of it) | |

For (f), the file, the object, the visibility modifier and names of the objects this change moves are not part of a finding, so moving `private fun f` from one object into a new file as `internal fun f` is a re-key, not new debt. The baseline holds one entry per text per file, not a hit count, so the same text in a new file split out of a changed one cannot be told from new code there; the reviewer reads the moved code.

Escape hatches, each visible in the diff:
- (a): the allowlist `clock_times_allow.txt`, or the marker `clock-ok` on a line. Dates are always fine.
- (b): the allowlist `file_size_allow.txt`.
- (c): a line `migration-edit-ok: <repo path> <reason>` added anywhere in the change. Every file (c)
  covers is RED, so the marker always sits in a change that gets the adversarial review, and that
  review must accept the reason in writing.

Limits: the em dash check cannot see the recruiter demo's page content (it lives outside the
repository); the Django number check sees only the branch's own tree, so two unmerged branches that
each add the same number both pass until the second is rebased onto `dev`; `supabase/migrations` is
not checked.

`python tools/checks/run_all.py --all` prints the whole tree's counts without failing.
