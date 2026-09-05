---
map: two-clients
ticket: "03"
title: "The Canvas poller, on Django's clock"
type: build
status: kiv
status-detail: "SUPERSEDED 2026-09-05 evening by ADR 0044 (Django is the engine, map .scratch/django-engine). Absorbed 2026-09-05 by django-engine (ADR 0044); see .scratch/django-engine/. Was: KIV 2026-09-05 (Kevin): no Django for now. Canvas state is refreshed by the manual read (tmp/canvas_reconcile.py) until a host exists."
blockers: ["01", "02"]
blocked-by: ["[[01-schema-ownership-and-django-role]]", "[[02-where-django-runs]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# The Canvas poller, on Django's clock

Absorbs the "Canvas is its own ticket" section of [[08-events-are-not-todos]]. That ticket put the
poller in a Supabase edge function; ADR 0043 puts it in Django on a cron schedule. **Every rule
ticket 08 states about the poller binds here exactly as it would have bound the function.** They are
copied in below so this ticket is complete on its own.

## What it does

On a schedule, with the household's own Canvas token (BYO, same shape as the Gemini key), read each
active course's assignments with submission state and write `events` rows of `kind = task`, through
the same RPCs the phone uses. The 2026-09-04 hand read (`tmp/canvas_reconcile.py`) is the prototype:
it matched 95 Canvas assignments against 117 seeded rows on the stored Canvas assignment id and
wrote only what changed.

## Binding rules, carried from ticket 08 verbatim

From ticket 08, "A discussion is more than one deadline (Kevin, 2026-09-05)":

> *"be careful about the canvas events called discussions. the due date in canvas calendar is one
> thing but sometimes it requires a post on wednesday and 2 replies on friday etc."*
>
> Canvas exposes ONE `due_at` per discussion, and it is the LAST obligation (the replies). The
> initial post is a separate, earlier deadline that lives only in the assignment description and the
> syllabus. The 2026-09-04 reconciliation held back 15 MATH 3391 "first post due Wednesday" rows as
> probable duplicates of the Friday discussion; that was wrong, and they are being written as their
> own task rows with `structured_meta.parent_canvas_assignment_id` pointing at the Canvas discussion
> they belong to.
>
> **Binding on the Canvas edge function when it is built:** a discussion's `due_at` may not be
> treated as the whole story. Parse the description for an initial-post deadline (or read it from the
> syllabus row already present), emit one task per sub-deadline grouped under the parent, and never
> let a single `submitted_at` mark all of them done - Canvas reports the discussion submitted on the
> FIRST post, so the replies row must key on a later signal (reply count, or stay manual) rather than
> the parent's workflow state.

From ticket 08, "Canvas is its own ticket":

> an auto-tick sourced from Canvas is an assertion from an external, falsifiable system - which is
> precisely what CLAUDE.md §4 asks to stand behind a claim. "You submitted this" because Canvas
> reports `submitted_at` is honest. The same sentence inferred from a calendar title is not.

And the three rulings from `memory/library/decisions.md`, 2026-09-04 ("Coursework is a task, Canvas
is its truth"):

1. Every coursework row is `kind = task`, including unpublished modules. Course notices are `event`.
2. `done` on a Canvas-backed row is Canvas's submission state, `provenance = DETERMINISTIC`, and the
   evidence rides in `structured_meta` (assignment id, workflow state, `submitted_at`, score, grade,
   `read_at`). §4 rule 8: the verdict travels with what it was decided from. A `not_graded`
   placeholder is exempt (`manual_completion: true`) because Canvas structurally cannot see it.
3. Matched rows keep their `origin_guid` and their seeded titles. Canvas's own id lives in
   `structured_meta`, so a re-read matches on it.

## Where the sub-deadline rule is enforced

ADR 0042: a rule both clients must agree on lives in Postgres. "A parent's `submitted_at` does not
complete its sub-deadlines" is such a rule - the phone can tick a first-post row by hand and Django
can flip the parent from Canvas, and neither may cascade into the other. So the poller does NOT
implement it in Python. It calls an RPC (name it `upsert_canvas_task`) that:

- matches on `structured_meta->>'canvas_assignment_id'`, falling back to `origin_guid`;
- sets `done` from `submitted_at` only on rows with no `parent_canvas_assignment_id`;
- refuses to touch `done` on a row carrying `manual_completion: true`;
- is idempotent: the same Canvas payload twice writes nothing the second time.

[[04-first-rules-move-down]] owns the RPC's first cut if it lands first; otherwise it lands here.

## Open, and to be ruled before the first scheduled run

- **Endpoint.** `/api/v1/users/self/todo` is cheap and incomplete (omits submitted work, which is
  the half we want). `/api/v1/courses/{id}/assignments?include[]=submission` is what the hand read
  used and is the default unless something is found against it.
- **Cadence.** Hourly is the ceiling worth paying for; assignments do not flip faster than a person
  submits them. Backoff on 429 is mandatory, Canvas rate-limits per token.
- **Deleted upstream.** An assignment Canvas no longer returns: soft-delete the task (tombstone,
  matching the schema's posture everywhere else), never hard-delete, and never touch a row Canvas
  never created.
- **Token storage.** An environment variable on the Django host ([[02-where-django-runs]]). Kevin
  was reluctant to generate a token on 2026-09-01; the hand reads used a browser session, which a
  scheduled job cannot. This needs his yes.

## Verification

- [ ] Run once by hand against the live project; diff the `events` table before and after against
      `tmp/canvas_writes_final.sql`'s expectations. Zero unexpected flips from done to open.
- [ ] Run twice in a row; the second run writes zero rows.
- [ ] Tick a first-post row on the phone, then run the poller with the parent submitted: the
      first-post row stays as the phone left it, the parent flips.
- [ ] Stop Django for a day. The phone still shows and ticks every task; only freshness is lost.
      This is ADR 0043's outage test and it is not optional.
