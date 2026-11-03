---
map: procedures
ticket: "08"
title: "Distillation: when a repeated trace becomes a proposed procedure"
type: grilling
status: resolved
status-detail: "resolved 2026-11-03, Kevin: approve all 9 as recommended"
blockers: ["06"]
blocked-by: ["[[06-trace-content-and-retention]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Distillation: when a repeated trace becomes a proposed procedure

## Question

The talk distils new procedures from successful repeated traces. What qualifies, who sees it,
and what makes it live?

## Recommendation

- **Proposals only. Never auto-activated.** A proposal is a `procedure_versions` row with
  `source = distilled`, `approved_at = NULL`, inactive.
- **Source**: tool-call sequences in conversation records (`DeviceLog` reason `conversation`,
  phone, head unit and web all share the LIVE_AUDIT line format) plus procedure runs.
- **Qualifies when**: the same ordered set of read tools, with the same argument shape, succeeded
  in at least 3 conversations over at least 2 separate days, and no turn in them touched a
  read-through tool (mail). Writes never distil into a procedure without an `ask` step in front.
- **Never from recruiter visits** (other people's sessions), never from Mia's conversations into
  Kevin's procedures or the reverse unless the procedure only reads shared rows.
- **Kevin approves in the inspector**: sees the traces it came from, the YAML, a dry run on
  today's data; approve, edit, or dismiss. Approval writes `approved_by`, `approved_at` and
  makes it the active version. Dismissed shapes are not proposed again.
- **Proposer is code first** (sequence mining over parsed lines). A model may NAME a proposal and
  write its description; it does not choose its steps.

## Answer

**Accepted as recommended.** Kevin, 2026-11-03: *"approve all 9 as recommended"*.

- Proposals only, never auto-activated: a `procedure_versions` row with `source = distilled`, unapproved, inactive.
- Qualifies at 3+ matching successful conversations over 2+ separate days; never from recruiter visits, never from turns that touched a read-through (mail) tool; writes never distil without an `ask` in front.
- Kevin approves, edits or dismisses in the inspector; dismissed shapes are not proposed again. The proposer is code; a model may only name and describe a proposal.
- ADR 0070. Build: ticket 20.
