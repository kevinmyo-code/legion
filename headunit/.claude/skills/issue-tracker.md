# Issue tracker: Local Markdown

Issues and specs (you may know a spec as a PRD) for this repo live as markdown files in `.scratch/`.

MIDNIGHT AI is a solo repo with no issue-tracker workflow: work is tracked in `memory/MEMORY.md`
(live state) and `memory/library/` (long-term). `.scratch/` is deliberately NOT a competing tracker.
It is short-lived working state for one effort, and it is gitignored. When an effort finishes, its
decisions get filed to `memory/library/decisions.md` via the librarian (FILE mode) and the scratch
directory is disposable. See ATTRIBUTION.md for why this file exists at all.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01` — never a single combined tickets file
- Triage state is recorded as a `Status:` line near the top of each issue file
- Comments and conversation history append to the bottom of the file under a `## Comments` heading

There is no triage label vocabulary in this repo (the `triage` skill is not installed). Skills that
tell you to apply a triage label should skip that step.

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` — the Notes / Decisions-so-far / Fog body.
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`); a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.

### Refer by name, not number

Wayfinder's "Refer by name" rule still holds with local files: in anything Kevin reads, call a
ticket by its title, not `01`/`02`. The filename rides inside the name as its link.
