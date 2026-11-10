---
map: open-loops
title: "Open loops: what he said he would do, proposed, ask first"
charted: 2026-11-10
charted-by: "Kevin + Opus"
effort: "`.scratch/open-loops/`"
tickets: 7
open: 7
status: open
tags: [map]
---

# Open loops: what he said he would do, proposed, ask first

**Kevin, 2026-11-10 ("5 yes", then on the shape "sure. ask first."):** something he said he would do
and never set up (made-up examples: "I need to call the dentist", "I should renew the registration")
vanishes today unless he says "remind me". The nightly memory review also flags these, and Alfred
PROPOSES each one: "Track it?" Yes makes it a task with a date that is chased like any reminder. No
drops it for good.

Binding decision: **ADR 0085** (`docs/adr/0085-open-loops-are-proposed-never-asserted.md`), a narrow
exception to proactive-mode decision 11. Proactive-mode decisions 8, 12, 13, 19 and 20 bind here
unchanged; so do memory-review R4 (paraphrase plus record ids), "retire, never delete" and the
per-night cap.

## Contract

**Source.** Only his own words to Alfred: `user final` lines (spoken or typed) from turns that ran no
tool, carry no `read_through` event and no redaction; only web records and phone records that carry
a `run_id` (sources that mark such turns), never a head unit, older phone or unknown record; never an
assistant line, never mail or other people's words (`server/open_loops/words.py`). Each record is offered for extraction once (`open_loop_runs`).

**Extraction.** Inside the nightly memory review on the Claude worker (Kevin's Alienware). The context
gains `open_loops: {on, cap, own_words: [{label W1.., text}], already_proposed}`; the helper may write
`loops.json` (`[{text, evidence: [W labels], reason}]`); `review_memories.py submit` sends it with the
change list; `POST review/changes` gates it in the same transaction (`server/open_loops/gate.py`).

**The gate (code, not prompt).** Words he said in one line of the cited records only; no money, no identity or
bank number; a date only when his words print one calendar date not yet past; a per-member key on
content words, so an answered one never returns; not already an open reminder or unticked to-do; at
most `LEGION_OPEN_LOOPS_CAP` (5) a night across submits, the rest read again tomorrow; a reason that
quotes six of his words in a row is dropped.

**Storage.** `public.open_loops`, `open_loop_runs`, `open_loop_settings`: tenanted, private to the
member, owner guard, in `TENANT_TABLES`, `visible()`; recruiter, work laptop and demo refused. Pending
proposals expire after `LEGION_OPEN_LOOPS_EXPIRE_DAYS` (14), computed on read. No time on the wire.

**Endpoints (`/api/open-loops/`).** `GET` list, `POST offer` (the spoken brief's batch, each carried at
most twice), `POST <id>/track` (dated reminder or Todo line, idempotent), `POST <id>/dismiss` (for
good), `PUT settings` (`proposing`). Every route refuses any Claude worker token.

**Phone.** One line in the whole spoken brief (`get_sitrep` and the wake-up brief read
`BriefController.forVoice`) and a section in the Brief modal; answers ride `manage_item` add and
remove (`service/OpenLoopsToolbox.kt`), refused in a read-through turn; the hands path is
`ui/openloops/OpenLoopsRows.kt` under Digest on Settings, Proactive speech. "stop proposing tasks"
rides `why_did_you_say_that`'s `stop`. A yes pulls the aspect and re-arms reminders.

**Off by default.** `LEGION_OPEN_LOOPS=on` and the memory review not `off`.

## Tickets

- [01 Engine: own words, gate, proposals, endpoints](issues/01-engine.md)
- [02 Worker: loops.json in the nightly review](issues/02-worker.md)
- [03 Phone: brief line, voice answers, hands path](issues/03-phone.md)
- [04 Worker update and a live night](issues/04-worker-update.md)
- [05 Run it on the phone](issues/05-phone-run.md)
- [06 Kevin's rulings on the picks](issues/06-picks.md)
- [07 Consolidation and the review ignore read_through marks](issues/07-consolidation-read-through.md)

## Out of scope

- Mail, a web page, another person's words, or anything the assistant said becoming a proposal.
- A proposal raised on its own, outside a brief.
- A date the model works out ("Friday", "next week"); only a date his words print.
- The web assistant and `/mcp` answering proposals (no surface asked for).
