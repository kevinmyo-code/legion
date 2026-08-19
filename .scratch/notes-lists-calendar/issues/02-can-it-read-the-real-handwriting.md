---
map: notes-lists-calendar
ticket: 02
title: "Can the model actually read Kevin's real handwritten list?"
type: task
status: closed
status-detail: out of scope
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Can the model actually read Kevin's real handwritten list?

Closed 2026-08-07 as OUT OF SCOPE, not resolved. Kevin cut photo ingestion for lists mid-effort.
The probe harness was written and compiled but never run, so **nothing was learned about whether
the model can read the handwriting** - the question is untouched, not answered. If photo ingestion
ever returns, this ticket returns with it, as a fresh effort.

## Question

The whole photo-ingestion half of this map assumes Gemini vision can read a page of Kevin's
handwriting well enough to be worth confirming rather than retyping. **Nobody has tested this.**

Resolve it by running the REAL artifact, not a fixture: Kevin has the paper camping checklist in
hand right now. Photograph it, put it through the existing vision path (`ai/SubAgent.kt` already
takes an inline image part; `pantry/PantryReceiptAgent.kt` is the working precedent), and read what
comes back.

### Why this is a ticket and not an assumption

`memory/library/lessons.md` L14: a fixture built from the parser's own spec proves the parser matches
the spec, not reality. It has bitten this repo twice - the BofA card parser silently dropping four
interest rows, and the pantry receipt gate that could never pass a US receipt because it never asked
for tax. Both were caught only by running a real document.

The same trap is set here. A synthetic "handwritten list" fixture, or a photo of neat block capitals,
would prove nothing about a real biro-on-notepad camping list.

### What the answer must record

- The actual returned lines, verbatim, against what the paper says.
- The error rate and, more usefully, the error **shape**: whole lines missed, words garbled,
  quantities misread, ordering scrambled, bullet marks turned into text.
- Whether the model invents lines that are not on the page. This is the one failure that matters
  most, because charting decision 3 makes Kevin the gate, and a human skim-confirming a draft
  catches a garbled word far more reliably than a plausible invented one.
- Whether it copes with the things real paper has: crossings-out, arrows, marginal additions,
  two columns, a heading.

### Feeds

Ticket 06 (photo-to-draft flow) needs this answer to know how much correction the review screen has
to make easy. If the error shape turns out to be "invents plausible lines", charting decision 3
needs revisiting, because human confirmation is a weak gate against plausible fabrication.
