---
title: Blast-radius tiers and review-finding checks
tags: [docs, process]
---

# Blast-radius tiers

How much review a change gets before it merges depends on what it can break. Every change gets a
tier from the files it touches; the tier sets the review depth. Once its review passes and CI is
green, a change merges to `dev` (and `dev` to `main`) without waiting for a person.

**Deploys and publishes are not merges.** An engine deploy, a phone or head unit publish, and a
content load still need the owner's word in every tier, unless he rules otherwise later.

## The tiers

| Tier | What it covers | Review before merge |
|---|---|---|
| GREEN | Docs (`docs/`, `vault/`, `.scratch/`, `memory/`, READMEs, top-level `.md`; not prompt text or app assets, which are `.md` and `.txt` read at runtime); tests only; copy and wording (`strings.xml`, the copy modules); styling and layout files (CSS, images, drawables); scripts under `tools/` outside the worker | CI green + one light review: a single reviewer pass, no adversarial rounds |
| YELLOW | App or engine logic that touches none of the RED areas; any path the script does not recognise | CI green + one blind review that ends "Confirmed: SHIP" |
| RED | The worker hook and containment; auth and tokens; money and figure checks; read-through and crisis guards; migrations and schema; deploy, ship and CI tooling (these checks included); voice gate rules; the public demo's honesty guards; frozen rules (`CLAUDE.md`) | CI green + blind review with adversarial probes, repeated until "Confirmed: SHIP" |

Then, in every tier: auto-merge. A blind review gets only the task text and the full diff, never the
author's plan or summary, and the author never reviews its own change.

**The highest tier wins.** One RED file makes the whole change RED.

**Tests in a RED area are RED.** A test is GREEN on its own, but a test next to a guard (the worker's
tests, the frozen recruiter-delivery test) is matched as RED first: weakening a guard's test weakens
the guard.

**A file list cannot see logic.** "Styling with no logic" is judged by file type only: a Compose
screen or a `.tsx` component is YELLOW even when the change is visual, because the script cannot
prove it is. A reviewer who finds a GREEN change doing more than its tier says raises the tier.

## The script

`tools/blast_radius.py` prints the tier and the reason for each file:

```
python tools/blast_radius.py                    # this branch vs origin/dev
python tools/blast_radius.py --base A --head B
python tools/blast_radius.py --short            # one line, for a board card or a brief
git diff --name-only A B | python tools/blast_radius.py --files -
```

It counts deleted and renamed-away paths (moving a RED file out is a RED change), and it exits 2 on
an empty file list rather than calling nothing GREEN. The patterns live at the top of the script;
changing them is itself a RED change.

Use its `--short` line in the board card's status and at the top of every review brief, so the
reviewer knows which depth is owed.

## Review-finding checks (CI)

Findings reviewers kept repeating are now checks, in `tools/checks/`, run by
`.github/workflows/checks.yml` on pushes to `dev`, `main`, `up/**`, `integrate/**` and `ship/**`, and
on pull requests. A push to a `feat/` or `fix/` branch runs nothing: push the commit to `up/<name>` to
check it, or run `python tools/checks/run_all.py --base origin/dev` locally. Each check reads only the
lines a change adds, so existing debt never fails a build.

| Check | Fails on | Warns on |
|---|---|---|
| (a) Clock times in docs and comments | a log-like time anywhere (with seconds, after a date, or with a zone name); a bare time in a doc | a bare time in a code comment; a time range (a time-of-day rule) |
| (b) Code files over 600 lines | a new code file over 600 | an existing file that crosses 600, or is over and grows |
| (c) Schema files | an edited or deleted migration; a Django migration number already used in its app; a Room schema version with no `MIGRATION_<n-1>_<n>` registered in its `CarDatabase.kt`; an edited shipped schema JSON | a new Django migration with a destructive operation |
| (d) Em dashes in user-facing copy | `strings.xml` (phone and head unit); the copy modules | web app source, phone and head unit Kotlin, the demo's engine code |
| (e) Offhand quotes | not checked: whether a quote was a deliberate ruling is not in its text | |

Allowlists sit next to each check (`clock_times_allow.txt`, `file_size_allow.txt`). A single
legitimate time elsewhere carries the marker `clock-ok` on its line. Dates are always fine.

The em dash check cannot see the recruiter demo's page content: it lives outside the repository.

The Django number check sees only the branch's own tree: two unmerged branches that each add the same
number both pass until the second is rebased onto `dev`.

`python tools/checks/run_all.py --all` prints the whole tree's counts without failing.
