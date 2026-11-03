---
map: recruiter-demo
ticket: "13"
title: "Each recruiter visit gets its own copy of the demo household, writes allowed"
type: build
status: open
status-detail: "after the phone-web parity inventory"
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
