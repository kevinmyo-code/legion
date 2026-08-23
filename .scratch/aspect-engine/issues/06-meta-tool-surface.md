---
map: aspect-engine
ticket: "06"
title: "The meta-tool surface"
type: grilling
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-engine-schema]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The meta-tool surface

## Question

Charter decision 7: ~8 generic tools replace per-aspect CRUD. Specify them exactly:

1. **Signatures.** list_aspects, describe_aspect, query_records (filters? sort? limit? date
   ranges? aggregation or is that computed-fields' job?), create_record, update_record,
   delete_record, aspect_clerk, plus aspect-definition CRUD by voice (create_aspect, add_field -
   in v1 or hands-only at first?). Kevin asked for full CRUD parity by voice; decide whether
   *schema* editing is voice-reachable in v1 or UI-first.
2. **Descriptions.** These are read every turn; they must state the estimate rule, the outcome
   rule (a create that failed says so), and never say "driver". PromptRoleNamingTest applies.
3. **Safety rails.** delete_record by voice: confirm-first? Bulk update limits? What does the
   model see when the gate would quarantine a write?
4. **What survives of the 97.** Inventory LiveToolbox: which existing tools are CRUD (die into
   meta-tools), which are native verbs (survive under their plugin - OBD, music, comms, timers),
   which are neither and need a call. The answer includes that inventory table.
5. **voice_guide.py fallout.** The generator and its copy file assume a fixed tool list; decide
   what the user guide lists in a meta-tool world (probably aspects + verbs, not tools).
