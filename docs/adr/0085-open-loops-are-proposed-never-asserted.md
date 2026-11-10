---
status: accepted
decided: 2026-11-10
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-10 - Open loops: what he said he would do is proposed, ask first (Kevin)]]"
tags: [adr]
---

# 85. Open loops are proposed from his own words, never asserted, and nothing is tracked without his yes

## Standing

**Something Kevin said he would do and never set up ("I need to call the dentist") is found by the
nightly memory review in his own words to Alfred, stored on the engine as a private PROPOSAL, and
asked about in the brief as a question about tracking it. Yes makes it a task; no drops it for
good.** Off until `LEGION_OPEN_LOOPS=on` (and the memory review not `off`). Built on
`feat/open-loops`; not deployed, and the live worker does not run it until Kevin orders a worker
update.

**A narrow exception to proactive-mode decision 11** ("no raise may let the model decide both that
there is something to say and what it is"): here the model (the Claude helper in the nightly review)
originates the CONTENT of a proposal. The exception is held to three walls, and only these three make
it acceptable:

1. **It is never spoken as a fact about Kevin.** A proposal is only ever a question about tracking a
   task, worded from the stored row (task and date). No surface is handed when he said it (no time
   field exists on the wire), and the line forbids "still undone", absence or engagement in words.
2. **Nothing is tracked without his yes**, given by him on his phone (a tap, or his voice in a clean
   turn). A Claude worker's token may write proposals and may never answer one.
3. **The engine, not the model, decides what may be proposed.** Every proposal passes code checks:
   made only of words he said in the cited records; no money and no identity or bank number; a date
   only when his words print one; never one he already answered (a per-member key on its content
   words); never one already on his list; at most 5 a night.

Whether to raise is still a rule (decision 8): "pending proposals exist", read from the rows. The line
rides the brief (the tapped one and the wake-up one) and is never a raise of its own.

## Context

Kevin, 2026-11-10: commitments he says in passing ("I should renew the registration") vanish unless
he says "remind me". The nightly memory review (ADR 0067 amendment, ADR 0075) already reads his own
words on his Alienware; he said yes to it also flagging commitments, then "sure. ask first." Batching
the proposals into the brief rather than raising each was the orchestrator's pick, not yet his.

## Decision

- **Source: only what he himself said or typed to Alfred.** The review's context carries his
  `user final` lines from turns that ran no tool, carry no `read_through` mark (mail, web grounding,
  `fleet_web`, a camera look, a spoken brief or watch line, and the turn after one) and no redaction;
  never an assistant line; never a head unit record (its search grounding is unmarked). Each record
  is offered once (`open_loop_runs`), so a later night cannot reword an answered one back in from
  the same conversation.
- **Evidence is a paraphrase plus record ids** (memory-review R4): the helper's reason is kept only
  if it does not repeat six of his words in a row.
- **Storage:** `public.open_loops` (+ `open_loop_runs`, `open_loop_settings`), tenanted, private
  (ADR 0052, owner guard), in `TENANT_TABLES`, read through `visible()`, refused to a recruiter
  (ADR 0068), the work laptop and a demo household. Unanswered proposals leave every surface after
  14 days, computed on read; retire, never delete.
- **A yes** (`POST /api/open-loops/<id>/track`) makes the task on the engine in the same
  transaction: a dated reminder (`events`, kind `reminder`, all day) when his words or his answer
  named a date, otherwise an undated line on his Todo checklist, where a spoken plain to-do lands.
  Idempotent. The phone then pulls that aspect and re-arms reminders, so it is chased like any
  reminder.
- **A no** (`dismiss`) is permanent.
- **Voice, with no new declaration:** the brief line tells the model that yes is `manage_item` add
  with the task's words and no is `manage_item` remove; a `manage_item` add or remove naming a
  pending proposal (read from the engine at call time) answers it, and anything else is
  `manage_item` as before. Refused in a read-through turn, so the yes right after a spoken brief
  (whose news is web text) is refused once and must be said again or tapped.
- **Silence:** "stop proposing tasks" (through `why_did_you_say_that`'s `stop`) flips one switch on
  the engine, which stops extraction as well as the line; the same switch is a row under Digest on
  Settings, Proactive speech, beside the Track and Dismiss list (ADR 0035).
- **A spoken brief carries one proposal at most twice**; after that it waits on the list until it
  expires. The Brief modal and the list show it without counting.

## Consequences

- **A prompt rule still carries part of it.** Nothing inspects the spoken audio: the guarantee that
  the line is a question and never says when rests on the stored facts holding no time and on the
  wording (CLAUDE.md section 7, clause c stays human-reviewed). The engine's gate guarantees only
  what a proposal may contain, not that the helper found every commitment or judged each one well.
- **The key holds only for his words.** A commitment he says again in different words ("ring the
  dentist") after declining "call the dentist" is a new key and may be proposed again.
- **The head unit is recognised by its version name** (`1.0+<sha>`), a convention.
- **Found and not fixed here:** memory consolidation's filter (`member_memory.consolidate.transcript`)
  and the review's own `conversations` ignore `read_through` events, so a carry turn, a look or a
  grounded turn reaches them. Ticketed (`.scratch/open-loops/issues/07-*.md`).
- **Owed:** a worker update from a checkout carrying this branch (the prompt and `review_memories.py`
  change), the engine deploy with `open_loops.0001_initial`, `LEGION_OPEN_LOOPS=on`, a phone build.
