---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# The PC removes the constraint that retired the deterministic parsers

**Opened 2026-08-28 alongside [[0040-pc-is-the-primary-surface-phone-is-voice-first]].**

## What was decided, and on what grounds

Ticket 03 ruling 3 (Kevin's own proposal) retired the deterministic statement parsers in favour of
running a statement through the user's own LLM, which masks sensitive data and emits a CSV in a
format LEGION defines. It amended CLAUDE.md section 4 rule 1. Three reasons were given:

1. **PdfBox cannot run server-side** (Deno, on Supabase Edge Functions).
2. The parsers only ever covered DBS and BofA, so every other bank already fell through to the LLM.
3. Masking before upload matters more now that the truth lives in a cloud Postgres.

## What changed

**Ledger ingestion moves to the PC** (ADR 0040). Reason 1 evaporates there: `pdfplumber` runs
natively in Python, and Project Andromeda already built exactly this - the DBS parser's whole design
depends on `extract_words` x-positions, which is why `PdfWords` exists on the phone at all.

Reasons 2 and 3 are untouched and still bind. A parser LEGION does not have is still no parser, and
masking is still worth having.

## The question

**Should deterministic parsing come back for the formats LEGION can parse, with the LLM CSV as the
fallback rather than the primary?**

That is what CLAUDE.md section 4 rule 1 asks for on its own terms - *"deterministic first where a
deterministic path exists"* - and on the PC one demonstrably does. Ruling 3's amendment said the
clause "simply stops applying there" because no deterministic path existed server-side. On the PC
that premise is false.

## What this would mean, stated so the cost is visible

- **Three anchors versus two.** Ticket 12 already ruled that a DETERMINISTIC statement qualifies on
  two READ anchors plus `closing - opening == sum(lines)`, because no bank prints a combined total.
  That ruling was written for the phone's parsers and applies unchanged to a PC parser. The LLM CSV
  path keeps needing all three. So the two paths have different bars, deliberately, and the code
  must not blur them.
- **The gate stays in SQL.** Whatever parses, `commit_statement` is still the one gate
  (ticket 03 ruling 2). A PC parser produces rows and anchors; it does not produce a verdict.
- **A shared corpus is not optional.** Two extraction paths feeding one gate is precisely the
  situation the gate corpus exists for. If a Python parser lands, it owes cases in the same corpus
  the Kotlin pre-check and the SQL gate already share.
- **The Kotlin parsers may still die.** This ticket is not "un-retire the parsers" - it is "should
  the PC have deterministic parsing." The answer could be yes in Python and yes-still-delete in
  Kotlin, since the phone is no longer ingesting statements at all.

## Sequencing

Decide this **before** building the PC ingestion path, not after. Choosing the LLM CSV first and
adding a parser later means two ingestion paths written at different times against the same gate,
which is how they drift.
