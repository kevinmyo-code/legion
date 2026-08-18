---
map: ledger-drive-ingestion
ticket: 06
title: "How is LLM spend estimated and approved before a batch?"
type: grilling
status: resolved
status-detail: ""
blockers: ["05"]
blocked-by: ["[[05-batch-ingestion-mechanics]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# How is LLM spend estimated and approved before a batch?

## Question

Kevin's call: deterministic parsers run free, but if N files fall through to
`LedgerStatementAgent`, show the count and a rough cost and wait for a go-ahead before any Gemini
call. Protects against a sixty-file first sync quietly spending real money on his own key.

Decide:

1. **When the count is known.** Layout recognition happens inside `StatementDispatcher`, which
   currently parses and falls through in one step. Getting a count without doing the work needs a
   cheap recognize-only pass. Does one exist, and what does it cost to add?
2. **The cost model.** Statement PDFs vary hugely in page count. What is the input-token estimate
   per file, and what is the output estimate? Dispatch `analyst` for the arithmetic rather than
   guessing, and state the model and its pricing class explicitly.
3. **Honesty of the number.** CLAUDE.md §4 rule five: anything not stated by a source must be
   surfaced as an estimate. A cost projection is an estimate by definition and must be labelled
   one. Decide the wording.
4. **The approval surface.** A dialog before the batch starts, or a two-phase run where
   deterministic files import immediately and the LLM set waits behind a prompt? The second is
   better UX and more complex.
5. **Persistence of the choice.** Does "yes, run the LLM" stick for the session, for the folder,
   or is it asked every batch? An always-ask that fires on every rescan will train Kevin to click
   through it, which defeats the gate.
6. **Failure accounting.** If an LLM call runs and the result still quarantines, the money is spent
   and nothing is gained. Is that surfaced?

---

## Resolution (2026-08-02, Kevin, 4 calls + one `analyst` dispatch)

### 1. When the count is known - FACT first

**No recognize-only pass exists.** `StatementDispatcher.dispatch` parses fully and discovers
fallthrough by catching `UnrecognizedLayoutException`. Recognition markers per producer:
`BofaStatementParser.kt:41,47,85` (account regex, balance summary, section markers),
`DbsStatementParser.kt:181,218` (account section, column headers).

**Adding a recognize-only pass buys almost nothing.** Both parsers need full PDF text/word
extraction first. Extraction is the dominant cost, not the parse logic. DBS only discovers an
unrecognised layout *after* iterating pages (`:181`).

**Ruling: split the dispatcher, do not add recognition.**

```
StatementDispatcher.dispatchDeterministic(fileName, bytes)
    -> Success | Quarantined | NeedsLlm
StatementDispatcher.runLlm(fileName, text)
    -> Success | Quarantined
```

Pipeline becomes:

```
phase 1   fetch + hash + stage             parallel 4
phase 2a  deterministic parse, ALL files   serial
            Success     -> COMMIT NOW
            Quarantined -> record, no LLM call
            NeedsLlm    -> set aside
          >>> GATE ASKS HERE. count is EXACT. cost so far: zero <<<
phase 2b  LLM for approved set             serial
```

Deterministic parsing never calls Gemini. So the exact fallthrough count is free.

**Amends ticket 05**, which placed `AwaitingApproval` immediately after staging. Moved to between
2a and 2b.

Bonus: sub-question 4's "better UX" two-phase run falls out for free. Recognised statements are
already committed and visible before the user is asked anything.

### 2. Cost model - dispatched to `analyst`, tags preserved verbatim

| Claim | Value | Tag |
|---|---|---|
| Model id | `gemini-3.5-flash-lite` (`SubAgent.DEFAULT_MODEL`, `SubAgent.kt:331`) | `traced` |
| Endpoint | `:generateContent`, non-streaming (`SubAgent.kt:260`) | `traced` |
| Ledger sends extracted TEXT, not PDF bytes, no image part | `StatementDispatcher.kt:42-43`, `PdfText.kt` | `traced` |
| Fixed prompt scaffold | 884 chars = ~221 tokens | `traced`, measured from literals |
| Input tokens/file, low / typical / high | ~970 / ~3,220 / ~7,720 | `reasoned` |
| Output tokens/file, low / typical / high | ~290 / ~915 / ~2,290 | `reasoned` |
| Cost/file and 60-file total | **UNVERIFIED. No dollar figure adopted** | see below |

**Pricing was NOT established.** No price data in the repo, no live access in that session. The
analyst explicitly declined to state a price as fact. Its order-of-magnitude reference (~$0.01-$0.10
for a 60-file worst case) came from training-era pricing for an *older* Flash-Lite generation and is
**not confirmed current, not confirmed to be this model's price.** Do not ship a constant derived
from it. Pull live pricing for `gemini-3.5-flash-lite` first.

**Open budget unknown:** nothing in `SubAgent.ask` sets `thinkingConfig` or `generationConfig`, so
whatever this model bills for implicit thinking applies unmodified. Not verifiable from the repo.

**Correction the analyst caught:** the 164-267 KB probe file sizes are DISK bytes (PDF structure,
embedded fonts, xref). **Unusable for token math** - would overstate input by roughly an order of
magnitude. They remain correct for ticket 05's ~16 MB staging figure, which is disk.

**Dominant sensitivity is N, the fallback file count**, not token estimation. N ranges 0-60 by
statement mix; token spread is 2-3x. Q1's split makes N exact, so the estimate's weakest input is
now its most certain one.

### 3. Honesty of the number

Count leads. Cost is secondary and labelled.

```
14 statements need AI reading
This uses your own Gemini key.

  Estimated cost: about $X
  Estimate only, based on gemini-3.5-flash-lite pricing
  checked <date>. Actual usage is billed by Google, not
  by LEGION.

             [ Not now ]   [ Read them ]
```

Rules:
- Price lives in ONE documented constant. Model id + checked-on date beside it.
- Never render a dollar figure without the "estimate only" line. CLAUDE.md §4 rule 5.
- Count is exact and verified. Cost is not. Do not give the unverified number visual priority over
  the verified one.

### 4. Approval surface - see §1

Two-phase, free. Deterministic imports land first, gate covers only the LLM set.

### 5. Persistence: ASK EVERY TIME. Never remember, at any scope.

Only defensible because of §1. Recognised BofA/DBS statements never reach the gate. Monthly rescan
of known banks = zero prompts. Prompt frequency tracks actual spend, not activity.

Rejected: per-folder memory (one click permanently disarms the gate on the folder that will hold
every future statement, and the user will not remember doing it); session scope (invisible
boundary, and §1 already killed the repeated-prompt problem it solves); budget threshold
(auto-spends real money on a number that is explicitly not a fact).

### 6. Failure accounting: parse `usageMetadata`

**FACT: `SubAgent` does not parse `usageMetadata`.** Gemini returns it on every `generateContent`
call and the code discards it.

Add parsing. Record per file:

| Column on `ingested_files` | Type |
|---|---|
| `llmAttempted` | INTEGER NOT NULL |
| `llmPromptTokens` | INTEGER, nullable |
| `llmResponseTokens` | INTEGER, nullable |

Scan summary states it plainly: `"3 read by AI, 1 still didn't reconcile. 9,140 tokens used."`

**Why this matters beyond sub-question 6:** it closes §3's honesty gap from the other end. After one
real batch the app holds *measured* token counts, so the estimate stops being a reasoned number
derived from a reasoned number. Feed measured averages back into the estimate.

**Scope note:** `SubAgent` is shared with pantry and the vehicle agents. This improves cost
visibility everywhere, which is slightly wider than ledger. Deliberate.

### 7. Declining: the fifth state (amendment 3 to ticket 03)

Ticket 03: "any existing record -> skip, zero cost, regardless of state."

Collision: a declined file with a record is skipped **forever** and can never be approved later. A
declined file with no record is forgotten entirely.

**Ruling: `NEEDS_LLM` is a fifth state, explicitly EXEMPT from the skip rule.** Re-offered at every
scan until approved, or until the file changes. Declining is always "not now", never "never".

### What this ticket does NOT settle

- The actual price constant. Needs a live pricing check before any figure ships.
- Whether implicit thinking tokens are billed for this model.
- The screens rendering the gate. **Ticket 08.**
