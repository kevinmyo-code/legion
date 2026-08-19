---
status: accepted
decided: 2026-08-02
decided-by: Kevin
source: "decisions.md 2026-08-02"
tags: [adr]
---

# 16. The spend gate runs after deterministic parsing, not before

## Standing

ACCEPTED.

## Context

The gate has to quote a cost before any paid extraction runs. Placed after staging, it can only quote a worst case, and a worst case that is usually wrong trains the driver to dismiss it.

## Decision

`StatementDispatcher` splits into `dispatchDeterministic`, which never touches Gemini, and `runLlm`. The gate sits between them, counting only the files no parser recognised. If every file is recognised, the driver is never prompted at all.

## Consequences

- Prompt frequency tracks actual spend rather than activity, which is what keeps the prompt meaningful.
- Declined: remembering the answer per folder. That would be one permanent disarm on the folder that holds every future statement.
- No pricing constant was adopted. `ai/SubAgent.kt` discards `usageMetadata`, so cost figures are reasoned estimates, not measured.
