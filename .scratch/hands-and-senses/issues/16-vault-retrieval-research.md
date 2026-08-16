# Does the document vault need retrieval machinery at all?

Type: research
Status: claimed
Blocked by: -

## Question

The vault holds Kevin's own documents: resume, insurance policies, warranties, owner's and service
manuals, appliance docs, registration. The instinct is "it's a RAG" - chunk, embed, vector store,
retrieve. That instinct may be wrong for a corpus this small, and the wrong answer costs a
recurring embedding bill, a chunking layer, an index that drifts from the files, and a Room schema
change. Surface the facts from Google's own docs (ai.google.dev) and Android's:

1. **Long-context alternative.** Current Gemini model context windows, and the cost of putting a
   whole document (a 40-page insurance policy, a 300-page service manual) into a Flash call.
   Token-count arithmetic in real dollars for a plausible vault: 10 documents, a few hundred pages
   total, queried a few times a day.
2. **Files API.** Does the Gemini API let a file be uploaded once and referenced by handle across
   calls? Retention period, size limits, cost. If yes, that is a middle path between "re-send every
   time" and "build a vector store".
3. **Context caching.** Explicit/implicit caching: pricing, minimum token thresholds, TTL. Does it
   make repeatedly querying the same manual cheap?
4. **Embeddings, if needed.** Current embedding model, price per token, dimensionality. What an
   on-device vector store would cost to build and keep fresh (re-embed on file change).
5. **PDF handling reality.** Native PDF input to Gemini (does it read scanned pages, is it OCR or
   text-layer only, page limits per request) vs extracting text locally with the PdfBox-Android
   dependency LEGION already ships for ledger. Note that PdfBox in LEGION needs Robolectric to be
   testable off-device (CLAUDE.md §3) - a constraint on any local-extraction path.
6. **SAF re-read.** `.scratch/ledger-drive-ingestion/issues/01-saf-drive-folder-feasibility.md`
   resolved SAF Drive-folder access: build on SAF, gate at API 30, no new OAuth scope, and THE
   CRUX (files added after the grant are visible) is traced but **never device-verified**. Confirm
   what that leaves unproven for a vault whose whole premise is "drop a file in the folder later".

Write findings to `research/16-vault-retrieval.md`, cite every claim, mark unverified items and
anything needing an on-device spike. Append the Answer here and set Status: resolved.
