---
map: engine-mcp
ticket: "06"
title: "Which tools are exposed, read before write, and the honesty contract"
type: decision
status: resolved
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-one-source-for-tool-definitions]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Which tools are exposed, and the honesty contract

## Question

What is the first tool list, which of it may write, and what must every result say - given that a
third-party model is not bound by LEGION's prompt layer at all?

## Sub-questions

1. **Read first.** Recommendation: v1 is read-only. Candidates: what is due (events + tasks),
   checklists, places, ledger transactions and statements with their provenance and anchors, pantry
   receipts, fleet vehicles and service history, body logs, goals.
2. **Memory.** `memories` and `companion_memories` are the most personal tables. Should an external
   model read what the companion remembers? Recommendation: not in v1; Kevin's call.
3. **Writes, when they come.** Only through the same Django serializers the REST views use, only on
   a `write`-scoped token (05), annotated `destructiveHint` where they delete. The five gated tables
   are never writable by a tool (they answer 405 today), and that does not change here; document
   ingestion is 07.
4. **The honesty contract, in the result.** `CANNOT_CLAUSE` lives in `AriaBrain.kt` and reaches only
   Gemini Live. For any other client the only lever is the tool result, so every result must:
   - on failure, set `isError` and say in words **what did not happen** ("nothing was written");
   - never report an outcome the server did not commit;
   - carry provenance in words: an `UNRECONCILED` row says so in the text, not only in a field
     (§4 rule 7: said in words on every surface - an MCP result is a surface);
   - label estimates (pantry macros) as estimates (§4 rule 5);
   - distinguish **unreadable from empty** (§1): "the engine could not read X" is never "you have
     no X".
5. **Mail and third-party content.** Never on the server, so no tool can expose it. Recommendation:
   state it as a registry rule with a test, so a future "summarise my inbox" tool cannot be added by
   accident.
6. **Annotations.** `readOnlyHint` on every v1 tool, so a client can auto-approve reads.

## Resolution

Kevin approves the v1 list and rules on 2. The contract in 4 becomes a test over the registry (a
failure result with no words fails the suite), like `AriaBrainHonestyClauseTest` but on the server.

**Ruled 2026-10-02 (Kevin): read AND write in v1**, against the read-only recommendation. Sub-question 3's conditions therefore bind v1, not a later version: writes go only through the same Django serializers the REST views use, only on a `write`-scoped token (05), with `destructiveHint` on deletes; the gated tables stay unwritable (they answer 405 today) and document ingestion stays with 07. The honesty contract in 4 applies to every write: a write result names what was committed, and a failed write sets `isError` and says nothing was written. Sub-question 2 (memory tables) **ruled 2026-10-02 (Kevin: *"go with your recs"*): excluded by default.** No MCP tool reads or writes `memories`, `companion_memories` or `memory_audit`; a later ruling may add them, one tool at a time. Sub-question 5 (mail) stands as a registry rule with a test.
