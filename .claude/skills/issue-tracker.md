# Issue tracker: Local Markdown

Issues and specs (you may know a spec as a PRD) for this repo live as markdown files in `.scratch/`.

LEGION is a solo repo with no issue-tracker workflow: work is tracked in `memory/MEMORY.md` (live
state) and `memory/library/` (long-term). `.scratch/` is deliberately NOT a competing tracker. It is
working state for one effort. When an effort finishes, its decisions get filed to
`memory/library/decisions.md` via the librarian (FILE mode). See ATTRIBUTION.md for why this file
exists at all.

> **`.scratch/` WAS LOST ONCE AND IT COST REAL WORK.** A 15-ticket wayfinder map with twelve
> unresolved contested calls was destroyed in a machine port on 2026-07-31, because `.scratch/` was
> blanket-gitignored, never committed, and none of its content had been filed to `memory/library/`
> yet. It is gone, not stale.
>
> **Fixed 2026-08-01: maps, tickets and research findings are now TRACKED.** `.gitignore` still
> ignores `.scratch/` churn, but negates `map.md`, `issues/**`, and `research/**`. Commit them like
> any other file. "Disposable" was true of the churn and false of the map.
>
> **This does not replace filing decisions.** File to `memory/library/decisions.md` when a decision
> is made, not when the effort ends. Tracked-in-git protects the working state; the library is the
> durable record a future session actually reads. Both, not either.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`,
  numbered from `01` - never a single combined tickets file
- Triage state is recorded as a `Status:` line near the top of each issue file
- Comments and conversation history append to the bottom of the file under a `## Comments` heading

There is no triage label vocabulary in this repo (the `triage` skill is not installed). Skills that
tell you to apply a triage label should skip that step.

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number
directly.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` - the Destination / Notes / Decisions-so-far / Fog body.
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question
  in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`);
  a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it
  lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed;
  first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a
  context pointer (gist + link) to the map's Decisions-so-far in `map.md`. **Then file the decision
  to `memory/library/decisions.md`** - see the warning above; do not defer this to the end of the
  effort.

Because ticket filenames are numbered predictably, blocking edges can be written at creation time
rather than in a second pass. Wayfinder's create-then-wire two-pass instruction exists for trackers
that assign ids on creation; it does not apply here.

### Refer by name, not number

Wayfinder's "Refer by name" rule still holds with local files: in anything Kevin reads, call a
ticket by its title, not `01`/`02`. The filename rides inside the name as its link.

## Active efforts

- `.scratch/ledger-drive-ingestion/` - Drive-folder batch ingestion for the ledger aspect plus a
  basic UI across all three aspects. Charted 2026-08-01, ten tickets; 12 and **13** added later.
  **Ticket 13 (`categoryPending` default drift) was found by the fleet-maintenance map**, not by
  this one - a schema audit against Kevin's real device turned up the only drifted default in 44
  entities. Filed here because it is a ledger table.
- `.scratch/legion-shape/` - what LEGION actually is. Charted 2026-08-06/07, twelve tickets.
- `.scratch/notes-lists-calendar/` - notes, lists and a local calendar, absorbing `CarTask` and
  `PlaceReminder`. Charted 2026-08-07, ten tickets. No external blockers, deliberately.
- `.scratch/google-account-integration/` - Gmail (read-only, pull-only) and Google Calendar
  (Google owns timed events). Charted 2026-08-13, ten tickets, three of them research fired at
  charting. Unblocked by the OAuth client finally being registered; **reverses**
  `notes-lists-calendar`'s ruling that Google Calendar mirroring was out of scope.
- `.scratch/mission-control/` - full visual re-do of every LEGION screen in a mission-control
  aesthetic (red-orange chrome, mint data, global CRT bezel, tiled roots). Charted 2026-08-14,
  eleven tickets. **Supersedes parts of `cyberdeck-ui`** (its tickets 01, 03, 04, builds 12-20, and
  its utility-screens-out-of-scope ruling); that map stays closed as history. Execution in scope.
  Reference photos live in `research/refs/`.
- `.scratch/fleet-maintenance/` - the fleet aspect's maintenance layer rebuilt and shipped: a
  schedule Kevin owns rather than one an LLM guessed, hand add/edit/delete, an odometer with a
  manual entry path, a service-history screen, fleet spend, and a recall button. Charted
  2026-08-15, twelve tickets, two research fired at charting. Execution in scope. **The core defect
  class is the silent no-op** - the assistant reported changing an interval it could not have
  written.
- `.scratch/android-auto/` - LEGION in the car: an Android Auto media app whose play button places a
  self-managed telephony call, so the live session gets the car's microphone. Charted 2026-08-13,
  fifteen tickets. Destination is DECISIONS, not shipped. **All five research tickets resolved the
  same day**; they falsified one settled decision, corrected a charting claim, and surfaced two
  defects in shipped code (tickets 13 and 15).
