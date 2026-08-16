# Does the document vault need retrieval machinery at all?

Type: research
Status: resolved
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

## Answer

**NO. The instinct is wrong. Do not build an embedding index.** Full findings with citations:
`../research/16-vault-retrieval.md`.

1. **Long context: the vault is not big.** `gemini-3.5-flash-lite` takes **1,048,576** input tokens;
   a PDF page bills at a flat **258 tokens** and its native text layer is **not charged**. So the
   whole 300-page vault is **77,400 tokens = 7.38% of the window**. It fits thirteen times over.
   Retrieval machinery exists to fit a corpus that does not fit. `traced` for both inputs.
   The real argument for routing to one document is **latency**, not budget.
2. **Files API: a middle path for bandwidth, not for tokens.** Upload once, reference by handle,
   **free**, but **48-hour retention**, 50 MB per PDF, 20 GB per project. Nothing in Google's docs
   exempts a handle-referenced file from input billing, so cost per query is identical to inlining
   the bytes. That inference is `reasoned` and is the most load-bearing unverified claim in the
   file - spike S6 measures it. Use it only above ~20 MB.
3. **Context caching is the trap, and it is not close.** Storage is **$1.00/1M tokens per hour** on
   Flash-Lite; TTL defaults to 1 hour. Kept warm, this vault costs **$56.21/month**, 15x the naive
   re-send-everything architecture it was meant to optimize. Break-even needs **4 queries inside a
   single TTL hour**. "A few times a day" is not that shape. **Implicit caching is free and on by
   default** - take it by putting the document before the question, and do nothing else.
4. **Embeddings: `gemini-embedding-2`, $0.20/1M text, 8,192-token input limit, 128-3072 dims
   (default 3072).** Building the index costs **four cents**. Cost is not the objection. The 8,192
   limit forces a chunker into existence, and `CompanionMemory.kt` already carries the rule that
   stored and query vectors must share one model plus dimensionality, so a model bump invalidates
   the whole index.
5. **PDF: use Gemini's native input, not PdfBox, on the answering path.** Native vision reads
   **scanned pages** (PdfBox returns an empty string), preserves **table layout** (a torque-spec
   grid), gives **page numbers to cite**, costs **2.7x fewer tokens** than the text PdfBox would
   extract, and removes the Robolectric constraint from every vault test. Limits: 50 MB / 1000 pages
   per request. PdfBox still earns a place at **catalog time** - page count plus page-1 text, where
   an empty extraction is itself the signal that the document is scanned.
6. **SAF: the vault's core premise is PROVEN; three vault-specific gaps are not.** A file uploaded
   after the grant IS returned with no re-pick - `tested` on the A17K 2026-08-02 (D2). What that
   leaves unproven, ranked by how much it hurts a vault:
   - **Offline reads: never run** (D11). Worse here than for ledger, because the vault's default
     posture is a **live read per query** with nothing in Room. "What is my deductible" in a parking
     garage is an open product decision, not a research finding.
   - **Google-native documents are `reasoned` only** (D8) - and **a resume is very likely a Google
     Doc**. Virtual, `openInputStream` throws, needs `openTypedDocument`. Ledger never hit this
     because it filters to `application/pdf`; **the vault must not inherit that filter** (a
     photographed registration is `image/jpeg`).
   - **`IngestScanner.listChildren` recurses exactly ONE level**, and its own doc comment says that
     cap is `reasoned`, not verified. A vault folder tree will be deeper than ledger's flat layout.
     Files two levels down are **silently invisible**.
   - Also outstanding: reboot grant persistence (never run), sync latency of **at least 2m36s**
     before a new file appears (`tested`, and time-alone was never isolated), API 30 gate against
     `minSdk = 24`, multi-account `acc=` prefix.

### Cost table (10 documents / 300 pages / 5 queries a day / `gemini-3.5-flash-lite`)

Rates `traced` from the pricing page: in $0.30/1M, out $2.50/1M, cached-in $0.03/1M, cache storage
$1.00/1M/hour. Working shown in the findings file §5.

| # | Architecture | $/query | $/month (150 q) |
|---|---|---|---|
| a | Re-send whole vault, inline | $0.02412 | **$3.62** |
| a' | **Routed: one document, inline** | $0.00322 | **$0.48** |
| b | Files API handle, whole vault | $0.02412 | **$3.62** |
| b' | Files API handle, routed | $0.00322 | **$0.48** |
| c | Explicit cache, warm 24/7 | + storage | **$56.21** |
| c' | Explicit cache, 1h bursts | + storage | **$12.09** |
| d | Embed once + retrieve chunks | $0.00186 | **$0.28** (+$0.04 build) |

**(a') to (d) is a $0.203/month saving. $2.44 a year.** That is what a chunker, a vector column, a
Room migration, a re-embed-on-change trigger, and a silently-driftable index would be bought for.

### Recommendation, simple first

**Two pull-based tools, no index, no explicit cache, no chunker.** Satisfies CLAUDE.md §7's
"pull-based tools always" directly.

```
list_documents()              -> titles + one-line summaries from Room, ~300 tokens
read_document(id, question)   -> whole file as native PDF/image, answer with a page citation
```

- One additive Room table `vault_documents` (v21 -> v22), same shape as `ingested_files`: SAF doc id,
  Drive file id, name, mime, size, mtime, LEGION-computed SHA-256, title, one-line summary, page
  count, optional aspect tag. **No embedding column, no chunk table.**
- **No extracted value is persisted** - ticket 17 item 3's default holds. The catalog is a file
  index, not an extracted fact.
- `gemini-3.5-flash-lite`, same model as the rest of LEGION. **~$0.0033/query, ~$0.50/month.**
- **The free tier is disqualifying**: Google's pricing page says free-tier content is "used to
  improve products", and this folder is headed for tax returns and medical records. The vault needs a
  billing-enabled key, and that fact must be said in words on the setup surface next to ticket 17
  item 4's required disclosure.
- Revisit embeddings only when the catalog stops fitting in a router prompt (~100+ documents) AND a
  single document routinely exceeds the 1000-page ceiling. Neither is near.

### On-device spikes (nothing below was run; repo rule L10)

| # | Spike | Settles |
|---|---|---|
| **S6** | Real 40-page policy + real service manual as native PDF; record `usageMetadata.promptTokenCount`; send the same file twice by handle and compare | The 258/page assumption the entire cost table rests on, and whether a Files API handle re-bills |
| **S8** | Real torque-spec question against the real service manual; verify the cited page is right | Citation fidelity, which ticket 17 item 6 says IS the feature |
| S2 | Drop a Google Doc, .docx, .jpg, .png in the folder; record mime/flags; try `openInputStream` then `openTypedDocument` | The resume case (D8) |
| S3 | Airplane mode, `openInputStream` on a never-opened file | The offline answer. **Needs USB** |
| S4 | Reboot, reuse the persisted tree URI | Grant persistence. **Needs USB** |
| S1 | Nested subfolders 2+ deep | The one-level recursion cap |
| S5 | Upload from laptop, never touch the Drive app, poll every 60s for 30 min | D4, whether elapsed time alone suffices |
| S7 | Phone-photo scan of a registration card | The scanned-page and 3072x3072 downscale claims |
| S9 | Page count and byte size of every real vault candidate | Whether anything breaches 50 MB / 1000 pages |
| S10 | `PdfText.extractText` over the real documents, count tokens | The `reasoned` 700 tok/page rate behind the 2.7x claim |

**S6 and S8 are the two that can invalidate this recommendation. Run them first.**
