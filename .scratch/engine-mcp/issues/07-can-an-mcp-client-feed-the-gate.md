---
map: engine-mcp
ticket: "07"
title: "Can an MCP client hand a document to the gate"
type: decision
status: open
status-detail: ""
blockers: ["06"]
blocked-by: ["[[06-which-tools-and-the-honesty-contract]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Can an MCP client hand a document to the gate

## Question

Should there be an MCP tool that submits a statement or receipt to `api/ingest/statement` /
`api/ingest/receipt`, and on what terms?

## Why it is tempting, and why it is dangerous

§4 rule 1's 2026-08-25 amendment already points here: a statement run **through the user's own
LLM**, which masks it and emits a CSV in a LEGION-defined format with **three** anchors (printed
total, opening, closing balance), tagged `LLM_RECONCILED`. An MCP client is exactly "the user's own
LLM". But an MCP client produces the lines and the anchors in one nondeterministic process, which is
the self-consistent-hallucination shape the three-anchor rule exists for (rule 6).

## Options

| | |
|---|---|
| A | **No ingestion over MCP.** Documents come in through the phone and the web app only |
| B | **One tool that calls the existing gate endpoint unchanged**: same quarantine, same provenance, anchors persisted (rule 8), only after `.scratch/backend-erp/issues/03-the-gate-server-side.md`'s CSV format is built. A statement with fewer than three anchors is refused in words, never stored provisionally, because rule 7's provisional tier requires deterministic extraction |
| C | B, plus rule 7 provisional rows from an MCP client | Forbidden by §4 rule 7 condition 1 as written. Listed only to rule it out |

## Recommendation

**A until the CSV format exists, then B.** The tool must not have a code path of its own: it calls
the gate, returns the gate's verdict verbatim, and its failure says nothing was written. Rule 8's
anchors are stored by the gate already, so B adds no new storage.

## Resolution

Kevin picks A or B.
