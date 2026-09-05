---
status: superseded
decided: 2026-09-05
decided-by: Kevin
supersedes: [0002-no-hosted-backend]
superseded-by: [0044-django-is-the-engine]
source: "[[decisions#2026-09-05 - Two clients, one Postgres: the rules live in the database]]"
tags: [adr]
---

# 43. Django is the second client, and what it may be hosted for

## Standing

**A Django app is LEGION's second client. It does what must run while the phone is asleep, and it is
the desk UI. It is not a second voice client and it owns no table.** With it, the rule "no
Kevin-hosted anything" ([[0002-no-hosted-backend]]) is SUPERSEDED by a narrower one: **no Kevin-hosted
anything the PHONE depends on to function.**

Kevin, 2026-09-05: *"well, 1 live backend, a django app that will consume that, plus our android
app."*

**This reopens a CLAUDE.md §2 locked decision.** It is recorded because Kevin named the shape
himself, in those words, and confirmed *"go with both"* when asked whether to write it down. The
CLAUDE.md §2 and §7 text carrying the old clause is owed an edit in the same change.

## Context

[[0002-no-hosted-backend]] was written when the phone was the only client and Room was the truth.
[[0038-byo-supabase-is-the-system-of-record]] made a household's own Postgres the system of record on
2026-08-25 and called that a narrowing, not a supersession: BYO Supabase is Kevin operating nothing.
That was true on paper and already false in practice - a Supabase project is a hosted service, the
free tier pauses after seven days, and a keepalive RPC exists because something has to be up for the
phone to work. The rule had been superseded by the pivot and never on paper.

Meanwhile a class of work has no home on the phone. Ticket 08
(`.scratch/one-today/issues/08-events-are-not-todos.md`) put Canvas polling in "a Supabase edge
function" because the phone is not always awake and an assignment's state should be true for every
client at once. The 2026-09-04 Canvas read and the 2026-09-05 WebAssign read were both done by hand
from a browser session, and both went stale the moment they finished. Nightly analysis over checklist
ticks, reports, and admin screens are the same shape: scheduled, or desk-sized, or both.

## Decision

1. **Django's role.** Scheduled Canvas polling (absorbing ticket 08's edge function - Django plus
   cron replaces it, and every rule ticket 08 states about discussions and `submitted_at` binds
   Django as it would have bound the function); the WebAssign completion read; nightly analysis over
   checklist ticks; reports and admin screens. **Django is NOT a second voice client and does NOT
   own any table.** Every table stays defined in `supabase/migrations/` and readable by the phone
   ([[0042-business-rules-live-in-postgres]]).
2. **The hosting rule, restated.** No Kevin-hosted anything the phone depends on to function. The
   phone must still clone-and-run against Supabase alone ([[0003-clone-and-run]]). Django is
   additive: if it is down, the phone loses scheduled freshness - Canvas state goes stale, WebAssign
   completion stops updating - and never loses function. A stranger who clones the repo gets a
   working phone with no Django at all.
3. **Where Django runs is a decision, not an accident**, and it is not decided here.
   `.scratch/two-clients/issues/02-where-django-runs.md` holds it open; candidates are Fly.io,
   Railway, and a home box.

## Consequences

- The test for a new Django job is the outage test: turn Django off, and the phone must still do
  everything by hand that the job did on a schedule. Canvas state can be re-read manually; a
  checklist can be ticked; a report can be skipped. A job that fails that test belongs in an RPC the
  phone can call, not in Django.
- BYO stays the credential shape. The Canvas token, a WebAssign session, the Postgres role: each is
  the household's own, configured on the household's own Django, never Kevin's.
- Django reads and writes the same rows the phone does, through the same RPCs, so a Django write
  reaches the phone by the same pull and Realtime path any other client's write does. No
  Django-to-phone channel exists or is wanted.
- CLAUDE.md §7's feature-add checklist line "Does it need a backend? Then it is wrong" is now
  "Does the PHONE need it to function? Then it is wrong on Django and belongs in an RPC."
- Tickets: `.scratch/two-clients/issues/03-canvas-poller.md` and
  `.scratch/two-clients/issues/05-webassign-completion-read.md`.
