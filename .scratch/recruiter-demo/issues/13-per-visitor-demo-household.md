---
map: recruiter-demo
ticket: "13"
title: "Each recruiter visit gets its own copy of the demo household, writes allowed"
type: build
status: built
status-detail: "server and web built on docs/recruiter-demo, suites green; owes deploy (Kevin's call) and a visit on the live engine"
blockers: ["01", "02", "03"]
blocked-by: ["[[01-recruiter-role-and-allowlist]]", "[[02-invite-code-to-visitor-token]]", "[[03-demo-household-seed]]"]
open-blockers: 3
ready: false
tags: [ticket]
---

# Each recruiter visit gets its own copy of the demo household, writes allowed

## Why (Kevin, 2026-11-02)

*"everything i can do on the phone save the phone only functions i should be able to do on the web
legion page and by extension the recruiter on the demo mode"*, then option C: each visitor gets a
private copy of the demo household.

This reverses ruling 2's "read-only" for recruiters INSIDE their own copy. ADR 0068 ("the recruiter
role only narrows") still holds: the role narrows to one throwaway household and to tools that do not
reach an outside account; it gains writes only on rows nobody else can see.

## What

- `/api/demo/enter` creates a fresh household per visit (name e.g. "Demo visit <id>", flagged
  `is_demo_visit`), seeds it with `seed_demo_household`'s data, and makes the visitor its only member
  with role `recruiter`. The shared "Demo" household stops being what visitors join.
- Recruiter writes allowed: the write tools that touch only household rows (records, events,
  checklists, ticks, purchases, notes). Still never: mail, Spotify account, Plaid, Drive, calendar
  sync, Claude sessions, device logs.
- Expiry: `prune_demo_visitors` deletes the whole visit household with its rows.
- Budget (04) unchanged: still one global $5/day.

## Rulings (Kevin, 2026-11-02, after the parity inventory, research/01)

1. Recruiters use the NORMAL web app screens on their own visit household (Money, Fleet, Lists,
   Calendar, Places, Notes...), not only the assistant. Outside-account screens stay hidden/refused:
   bank link (Plaid), mail, Spotify account, Drive, calendar sync, Claude sessions, device logs,
   household settings/members/invites.
2. The landing page stays: after the code, the visitor lands on `/try` (about Kevin, Quanex, Alfred)
   with a way into the app; the app shows a demo banner linking back to it.
3. Body aspect stays EXCLUDED for recruiters (no seed, no screens, no tools).
4. Voice notes ALLOWED for recruiters (seeded, fake; read via tools and the Notes screen).
5. Build order: this ticket first, then the quick-win engine tools (members and recruiters), then the
   bigger ports, then the aspect engine.

## Integrity exemption (Kevin approved, 2026-11-02: "ok")

The database refuses to delete verified money rows (`forbid_mutation_of_facts`). Per-visit
households cannot be cleaned up without an exemption. Proposal: the trigger allows deletes only for
rows whose household is flagged `is_demo_visit`, set at creation by `/enter` and never settable by
any API. Kevin's household can never carry the flag. ADR records the exception (amends the integrity
rule's scope, not its meaning).

## Verify

- Two concurrent visitors: neither sees the other's writes; neither sees Kevin's household (extend
  ticket 06's isolation tests to writes).
- A write tool from a recruiter changes only its own visit household.
- Prune removes every row of an expired visit, including gated ones, and nothing else.
- Seeding time per visit measured; target under ~2 s on Cloud Run.

## Built (2026-11-02, branch `docs/recruiter-demo`)

- `/api/demo/enter` makes and seeds `Demo visit <id>` (`is_demo_visit`), the visitor its only
  member. Seed per visit measured ~0.15-0.2 s on a local Postgres 17 (fsync off).
- `household/migrations/0008`: DELETE exemption in `public.gated_row_guard()` and
  `public.ledger_transactions_guard()`, by household flag, checked last (ADR 0069).
- `prune_demo_visitors` deletes the expired visit's household whole (`demo/visit_cleanup.py`).
- Route fence: `household/recruiter_routes.py` (allowed / read-only / denied with reasons), route
  walk in `tests/test_recruiter.py`. Tools: member web surface less `list_tables`.
- Web: `/try` gains "Explore the app"; the app shell lets a recruiter in, hides denied screens,
  shows the demo banner, and a denied screen by URL says why in words.
- Isolation: `tests/test_recruiter_isolation.py` section 4, `tests/test_demo_visit.py`.

