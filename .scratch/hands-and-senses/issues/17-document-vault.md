---
map: hands-and-senses
ticket: 17
title: "The document vault: LEGION reads the papers that run your life"
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - his own Drive folder, answered from the document, re-read every time"
blockers: ["16"]
blocked-by: ["[[16-vault-retrieval-research]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# The document vault: LEGION reads the papers that run your life

`.scratch/document-vault/` first, using this body as raw material;
[ticket 16's findings](16-vault-retrieval-research.md) become that map's resolved research, and its
ten spikes (S6 and S8 first) become that map's opening work. See map.md, "Efforts in disguise".

## Question

The largest genuine capability gap on the map. Nothing in LEGION holds insurance policies,
warranties, manuals, registration, or a resume - so "what's my deductible", "is the dishwasher
still under warranty", "when does registration expire" are unanswerable, and **wrench mode has no
source for a real torque spec** (its known honest weakness: with the car's service manual in the
vault, Alfred quotes and cites instead of guessing).

**Charting correction, verified 2026-08-16, and it changes the premise.** LEGION's Drive grant is
`drive.appdata` ONLY (`DriveAuth.DRIVE_APPDATA_SCOPE`) - a HIDDEN per-app folder Kevin cannot see
or drop files into. So "just use the Drive folder we already have" does not work as stated. The
mechanism that DOES work is already proven for ledger: SAF `ACTION_OPEN_DOCUMENT_TREE` against the
Drive DocumentsProvider, API 30+, **no new OAuth scope**
(`.scratch/ledger-drive-ingestion/issues/01-saf-drive-folder-feasibility.md`). Kevin picks a real
Drive folder once; anything he drops in it later is readable. `IngestScanner` already exists for
the ledger path - zoom it before designing anything new.

Decide:

1. **Where the vault lives.** One SAF-picked Drive folder (syncs from any device, matches how
   Kevin already thinks about documents), a local device folder, or both? What happens when the
   grant lapses or the folder is moved - and does the vault degrade offline (Drive-backed files
   may not be cached locally)?
2. **Retrieval architecture.** With [the retrieval facts](16-vault-retrieval-research.md) on the
   table: long-context whole-document calls, Files API handles, context caching, or a real
   embedding index. **Simple first** (the standing preference across every LEGION map). What is
   the cheapest thing that answers "what's my deductible" correctly, and what does it cost per
   query on Kevin's key?
3. **What the gate means here, because it mostly does not apply.** A policy has no printed total
   to reconcile against; §4's gate is for extraction that produces rows. The vault produces
   ANSWERS WITH CITATIONS, not stored facts - so the governing rule is §4 rule 5 and the
   library-adjacent posture: quote the document, name the document and page, never paraphrase into
   an unsourced assertion. **Decide explicitly whether ANY extracted value is ever persisted to
   Room** (e.g. "registration expires 2027-03-11" as a reminder). If yes, it needs a provenance
   tag and a re-read path when the source file changes; if no, every answer is live-read. Default
   to no.
4. **Document classes and privacy.** Insurance, warranties, manuals, resume, registration - and
   the sharp edge: this folder will eventually hold tax returns, medical records, IDs. Every query
   sends document text to Gemini on Kevin's key. Is there a class LEGION should refuse to read, or
   is a folder Kevin curates himself the whole control? Say the data-plane fact in words on the
   surface: "documents you put here are read by Gemini when you ask about them".
5. **Wrench-mode handoff.** How does [wrench mode](07-wrench-mode-shape.md) reach a service manual
   - same tool, or a fleet-scoped vault query? A manual bound to a specific vehicle is fleet data;
   a warranty is not. Decide whether vault documents carry an aspect tag.
6. **Tool budget.** One `search_documents` tool, or one per class? Write the description - and
   note it must promise citation, because that promise is the feature.
7. **Filing convention: a metadata sidecar per document.** Kevin (2026-08-16) wants documents filed
   with structured metadata rather than dumped in a folder, citing "Google's OKF". **That name was
   not recognised and is unresolved** - the closest real thing is the Open Knowledge Foundation's
   Frictionless Data spec (a small JSON descriptor filed beside each resource). **Ask Kevin what he
   meant before designing to a guess**; the two live readings are (a) an OKF/Frictionless-style
   sidecar descriptor, (b) Google Drive labels/Knowledge-Graph-style entity tagging. Either way the
   decision is the same shape: does each document carry a descriptor (type, subject, effective and
   expiry dates, owning aspect, source), where does that descriptor live (a sidecar file in the
   folder so it survives independently of the app, or a Room row so it is queryable), and who
   writes it - Kevin by hand, or an LLM pass at ingest whose output he confirms? An expiry date in
   the descriptor is what makes "registration expires next month" answerable without reading every
   document, so this is load-bearing, not decoration.

## Resolution - 2026-08-21 (Kevin, 2 calls)

### 1. It lives in a Drive folder Kevin curates - same shape as the ledger

*"i wanna put it in my google drive folder. same idea as ledger. that way i can curate and edit the
documents as i need in that folder."*

So this reuses the pattern `ledger-drive-ingestion` already established: a SAF folder the user picks,
his own Drive, no Kevin-hosted anything (CLAUDE.md §7). He adds, edits and removes documents in the
folder like any other folder, and the app follows.

### 2. Answers come FROM the document, with a citation, always

**Nothing is extracted and stored as truth.** Asked about his policy excess, it reads the paper and
answers *"per your State Farm policy dated March 2025, ..."*.

This is the reconciliation gate's posture applied to a source that **cannot** be gated. A bank
statement prints a total to reconcile line items against; **an insurance policy prints nothing.**
There is no anchor, so CLAUDE.md §4 rule 2 can never be satisfied - and rather than reach for rule
7's provisional-storage carve-out, this sidesteps the problem: **if every answer points at the
document it came from, nothing is ever asserted as a standalone fact.** The citation IS the anchor.

Storing extracted rows was rejected for the reason the ledger gate exists: a parse error becomes a
stored fact that outlives the mistake, and here there would be no printed total to catch it.

### 3. The folder is the truth, re-read on every question

No import step, no `ingested_files` row, no staleness. Kevin drops a renewed policy in and that is
simply what the assistant sees.

**Deliberately NOT the ledger's import-tracking**, even though it exists and works: that pattern
prevents double-processing of immutable statements, and these documents are **mutable by design** -
the whole point of the folder is that he curates it. A file he corrected would keep answering with
the old text until re-imported, which is the failure this avoids.

Costs a read per question. For a handful of documents that is nothing, and it can be revisited if
the folder ever grows large enough to matter.

### Still open for the build

- **How a document is chosen** when several could answer. Ask, or read filenames, or a cheap index?
  An index is a cache and caches drift from the folder - which is what call 3 just avoided.
- **What a scanned photo does.** A born-digital PDF has text; a photo of a policy needs OCR or
  vision, which is the pantry problem again.
- Whether it reuses the ledger's existing SAF folder or takes its own.
