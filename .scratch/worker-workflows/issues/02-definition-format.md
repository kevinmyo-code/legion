---
map: worker-workflows
ticket: "02"
title: "Definition format: workflow.json beside SKILL.md, parser and lint"
type: build
status: open
status-detail: ""
size: S
blockers: ["01"]
blocked-by: ["[[01-rulings]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Definition format

A workflow is a worker skill folder that also holds `workflow.json`. `SKILL.md` stays the how (the
steps a helper follows). `workflow.json` is the contract the worker, the engine and Alfred read.
JSON, because the worker is stdlib-only and Claude Code's skill frontmatter is not ours to extend.

## Shape (invented examples)

A read-only one:

```json
{
  "format": 1,
  "name": "rates-sheet",
  "says": "Builds this week's exchange-rate sheet as Excel.",
  "aliases": ["rates sheet", "make the rates sheet", "exchange rates"],
  "params": [
    {"name": "currencies", "says": "which currencies, like 'euro and yen'", "default": "the usual three"}
  ],
  "asks": [],
  "outputs": ["summary", "report"],
  "deliver": null,
  "scope": ["full"],
  "every": null
}
```

A writing one with a schedule:

```json
{
  "format": 1,
  "name": "tidy-downloads",
  "says": "Sorts the Downloads folder into dated subfolders.",
  "aliases": ["tidy my downloads", "clean up downloads"],
  "params": [],
  "asks": [{"at": "move", "says": "Move the files into dated folders?"}],
  "outputs": ["summary", "report"],
  "deliver": null,
  "scope": ["full"],
  "every": {"hours": 168, "idle_minutes": 10, "force_after_hours": 216, "retry_after_hours": 6}
}
```

## Fields

| Field | Rule |
|---|---|
| `name` | kebab-case, equals the skill folder name, unique per machine |
| `says` | one sentence, at most 120 chars, spoken by Alfred when listing |
| `aliases` | spoken phrases, lowercased, at most 8. The matcher uses these and `name` (hyphens as spaces) |
| `params` | free text in v1: `name`, `says`, optional `default`, optional `required` (default false). The rest of Kevin's words after the matched alias is the params text; the skill reads it. A required param missing: the helper asks with `needs_confirmation` (the build-feature pattern) |
| `asks` | the confirmation points, each a short `at` key and a spoken `says`. Descriptive only: enforcement stays the ask-list hook, `ship.py` and `send_mail.py` tokens. Lint fails a workflow whose `SKILL.md` names `ship.py` or `send_mail.py` but declares no ask |
| `outputs` | subset of `summary` (always implied), `report`, `page`. `page` makes the run `deliver: phone` |
| `deliver` | `null` or `email_self` (R3's one standing yes) |
| `scope` | machine scopes it may run on; `listed` only for workflows that read repos and return summaries |
| `every` | `null`, or the catch-up schedule ([Catch-up runs](04-catch-up-runs.md)) |

Nothing in `workflow.json` may hold a path, an address, a figure or a person's name: it goes to the
engine. Paths live in `SKILL.md`, which never leaves the laptop. Lint rejects drive letters,
backslashes, `@` and digits-with-currency in `says`, `aliases` and param text.

## Build

- `tools/claude_session/workflows.py`: `load_catalog(skills_dir)`, `lint(...)`,
  `catalog_for_engine(...)`, `catalog_hash(...)`, `match(phrase, catalog) -> (workflow,
  params_text) | None`. The engine ports `match` (ticket 03); both run one shared phrase table.
- `python tools/claude_session/workflows.py --lint <skills dir>` for Kevin and the miner.
- Fixtures: `tools/claude_session/test_fixtures/workflows/` with the two invented examples above
  and broken ones (bad name, path in `says`, ship without an ask, duplicate alias).
- `test_workflows.py`: parse, lint, hash stability, matcher table (longest alias wins, whole words
  only, "have Claude", "ask Claude to" and "on my Alienware" stripped, no match returns None).

## Verification

- [ ] `python -m pytest tools/claude_session/test_workflows.py`.
- [ ] Lint against the real private folder on the Alienware: today's skills have no sidecar, so
      they are skills, not workflows, and nothing breaks.
