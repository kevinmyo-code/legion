---
map: two-clients
ticket: "05"
title: "The WebAssign completion read"
type: build
status: kiv
status-detail: "Absorbed 2026-09-05 by django-engine (ADR 0044); see .scratch/django-engine/. Was: KIV 2026-09-05 (Kevin): no Django for now. WebAssign completion is ticked by hand."
blockers: ["01"]
blocked-by: ["[[01-schema-ownership-and-django-role]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# The WebAssign completion read

MATH 3391's homework and chapter quizzes live on WebAssign, not Canvas. Canvas carries `not_graded`
placeholders it can never mark submitted, so [[03-canvas-poller]] structurally cannot see whether a
WebAssign week is done. This ticket is the read that can.

## Rulings this is bound by

Both from `memory/library/decisions.md`, 2026-09-05, and the second reverses the first within the
hour - read them in order.

1. **Dates come from the SYLLABUS, never from WebAssign.** Kevin: *"i think the webassign will keep
   rolling over done assignments ... lets follow the syllabus, preseed if we can."* WebAssign's due
   column advances after work is submitted (Sections 1.1-1.4 read Aug 30 one week and Sep 6 the
   next), so it is a display artefact, not a deadline. The server already carries one homework row
   per syllabus week (14) and one quiz per chapter (13), preseeded. **This job never writes a date.**
2. **WebAssign is read for COMPLETION only.** What has been submitted and scored. That is the
   anchor Canvas cannot supply, and it is the same shape as §4: the system that actually holds the
   work is the one that can say the work was done.

## What it does

On a schedule, read WebAssign's assignment list for the course, match each entry to the preseeded
server row by section range and kind (homework vs quiz), and set `done` where WebAssign shows a
submission, with the evidence in `structured_meta` (WebAssign assignment name, score, submitted
marker, `read_at`) - §4 rule 8, the verdict travels with what it was decided from. Provenance
`DETERMINISTIC`: it is read from the system of record for completion, not inferred.

Through the same RPC as the Canvas poller, or a sibling with the same contract: idempotent, matches
on stored evidence, never touches a date, never touches a row it did not match.

## The problem this ticket owns and cannot yet answer

**WebAssign has no public API.** The 2026-09-05 read happened because Kevin handed over a logged-in
browser session. A scheduled job cannot do that. The options, none ruled:

| Option | Against it |
|---|---|
| Store a session cookie on the Django host and refresh it by hand when it expires | It will expire; the job then fails silently until someone notices. Must alert, not skip |
| Store WebAssign credentials and log in programmatically | Credentials for a third-party system in an env var; a login flow that may have a captcha or SSO through the university |
| Keep this read manual: a Django admin action Kevin fires with his session | Not scheduled, but honest. Satisfies ADR 0043's outage test trivially |

Kevin rules which. Until he does, the third option is the floor: build the matcher and the RPC call
as a management command that takes a session cookie argument, so the manual read at least stops being
a hand-written SQL file.

## Matching, and where it can go wrong

- WebAssign names look like `Sec 1.1-1.4 Homework` and `Chapter 1 Quiz`; the preseeded rows are
  titled per syllabus week. Match on the section range parsed from the WebAssign name against the
  syllabus week's section coverage, stored once in `structured_meta` on the seeded rows. Never on
  the date - rule 1.
- A WebAssign entry that matches no seeded row is logged and NOT written. Inventing a row from
  WebAssign would put a WebAssign date on the server, which rule 1 forbids.
- A seeded row WebAssign has not posted yet (future weeks) stays untouched and syllabus-dated.

## Verification

- [ ] Run against the current list: Sections 1.1-2.5 flip to done where scored; Chapter 1 Quiz flips;
      no `due_at` changes anywhere. Diff the table before and after.
- [ ] Run twice: the second run writes nothing.
- [ ] Feed it a name that matches no seeded row: nothing written, one log line naming it.
- [ ] Expire the session and run: a visible failure (log at error, and whatever alerting
      [[02-where-django-runs]] provides), never a silent zero-row success. A read that cannot see is
      not a read that found nothing - CLAUDE.md §1's unreadable-versus-empty rule.
