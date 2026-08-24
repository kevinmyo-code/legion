---
map: aspect-engine
ticket: "17"
title: "Build the voice surface"
type: task
status: built
status-detail: "Built 2026-08-23, senior-approved, merged to dev. Nine meta-tools + clerk + schema generator live in LiveToolbox; 104-tool inventory written. Owes: a real voice round-trip on the A25."
blockers: ["07", "16"]
blocked-by: ["[[07-aspect-clerk-prototype]]", "[[16-build-engine-core]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build the voice surface

## Question

Build what ticket 06 locked, informed by the clerk prototype (ticket 07):

1. The nine meta-tools: list_aspects, describe_aspect, query_records, create_record,
   update_record, delete_record, aspect_clerk, create_aspect, update_aspect. Descriptions carry
   the estimate rule and outcome rule; PromptRoleNamingTest passes.
2. The aspect clerk (executor SubAgent, bounded meta-tool loop, reports rows written/failed in
   words) and the schema generator subagent (Pro-tier, drafts definitions, confirm before
   commit).
3. Delete rails: unconfirmed single deletes into trash with spoken receipt; bulk deletes confirm
   with count.
4. **Owed from ticket 06 (L11):** the inventory of the 97 existing LiveToolbox tools - which die
   into meta-tools, which survive as plugin verbs, which need a call. Produce the table before
   deleting anything.
5. **Owed from ticket 06:** voice_guide.py + copy rethink for the meta-tool world, and the README
   voice-surface block.
