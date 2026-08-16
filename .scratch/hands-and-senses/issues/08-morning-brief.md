# Morning brief: a configurable skill, not a feed

Type: grilling
Status: open
Blocked by: -

## Question

A brief composed from modules Kevin toggles and configures: calendar (built), weather (built),
fleet status (maintenance due, open DTCs, recalls - built as reads), ledger anomalies, pantry
lows, and news from Kevin's AI newsletters via the existing Gmail tool (settled decision 4;
`GmailToolLogic` already passes a `q` query through, so `from:(...) newer_than:1d` is nearly
free). The new work is a module registry, per-module config, one composed summarization on
Kevin's key, and a delivery surface. Decide:

1. **Delivery, and the compulsion line.** Pull-only ("morning brief" spoken/tapped), or one
   scheduled notification at a time Kevin sets? A single user-scheduled digest serves the user; a
   streak or a "you missed yesterday" serves retention and is banned. Where exactly is the line -
   is a notification that says more than "brief ready" already a raise?
2. **The google-account boundary.** That map ruled "any background or proactive Gmail fetch" out
   of scope, returnable only as a fresh effort - this is that effort, for the newsletter module
   only. If the brief is pull-only, the fetch happens on demand and the old rule survives intact;
   if scheduled, a background fetch exists. Decide with eyes open and record which way the
   google-account decision is being amended, if at all.
3. **Module registry shape.** Config lives where (Room, DataStore, synced to Drive appDataFolder
   like other settings)? Newsletter sender list curated how?
4. **Composition.** One Flash sub-agent call over module outputs, or each module pre-formats
   deterministically and the LLM only summarizes the news module? A model choosing what to omit
   from fleet/ledger facts is a model deciding what Kevin does not hear (the briefing precedent
   from google-account ticket 05) - deterministic sections, LLM only where the source is prose?
5. **Read-through.** Newsletter bodies: read, summarized, dropped. Nothing stored, nothing in
   CompanionMemory. Confirm the mail rule holds for the brief.
6. **Tool budget.** Is the brief a tool at all, or a surface outside the live session that CAN be
   asked for by voice?
