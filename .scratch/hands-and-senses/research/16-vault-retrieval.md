# Does the document vault need retrieval machinery at all?

Answers ticket `issues/16-vault-retrieval-research.md`. Research date 2026-08-16.
Every claim tagged `traced` (read in a primary source or in this repo) / `reasoned` (inferred) /
`tested` (someone ran it). Citations at the end. On-device spikes listed separately at §8.

## VERDICT

**No. Do not build an embedding index.** A 300-page vault is 77,400 tokens of native-PDF input,
7.4% of Flash-Lite's 1,048,576-token window. The whole corpus fits in one call with room for 13x
more. The cheapest correct architecture is a **two-tool pull**: a Room-held catalog of titles and
one-line summaries the model reads first, then one whole document sent as native PDF. About
**$0.003 per query, $0.48 a month** at 5 queries a day. A full embedding pipeline saves
**$0.20 a month** over that and costs a chunker, a vector column, a re-embed-on-change path, a Room
migration, and an index that can silently drift from the files.

**Explicit context caching is the trap**, not the win. Kept warm it costs **$56/month** for this
vault, 15x the cost of the naive re-send-everything architecture it was supposed to optimize.

**Use Gemini's native PDF input, not PdfBox, on the answering path.** It is cheaper per page than
extracted text, reads scanned pages, preserves table layout, and gives page numbers to cite.

---

## 1. Long context: the sizing that decides everything

| Fact | Value | Tag |
|---|---|---|
| `gemini-3.5-flash-lite` input token limit | 1,048,576 | `traced` (model page) |
| `gemini-3.5-flash-lite` output token limit | 65,536 | `traced` |
| `gemini-3.7-flash` input / output | 1,048,576 / 65,536 | `traced` (model page) |
| PDF page cost | "Each document page is equivalent to 258 tokens" | `traced` (document-processing) |
| Native PDF text is NOT billed | "You are not charged for tokens originating from the extracted native text in PDFs" | `traced` |
| Per-request PDF ceiling | "up to 50MB or 1000 pages" | `traced` |
| Text token rate | "a token is equivalent to about 4 characters" | `traced` (tokens page) |

**The whole 300-page vault as native PDF = 300 x 258 = 77,400 tokens = 7.38% of the window.**
`traced` for both inputs, arithmetic for the result.

That single number is the answer to the ticket. Retrieval machinery exists to fit a corpus that does
not fit. This corpus fits thirteen times over. Even a 1000-page service manual alone is 258,000
tokens, still under a quarter of the window, though it hits the per-request 1000-page ceiling exactly.

Google's own long-context page: RAG techniques "remain valuable in specific scenarios, Gemini's
extensive context window invites a more direct approach: providing all relevant information upfront."
`traced`. It does not address corpus size directly, so this is corroboration, not authority.

**Latency, not cost, is the real reason to route to one document.** The long-context page states
"longer queries will have higher latency (time to first token)". `traced`. 77,400 tokens per voice
turn while Kevin waits mid-conversation is the objection, and it is a UX objection, not a budget one.

---

## 2. Files API

`traced`, files page:

| Property | Value |
|---|---|
| Reference by handle across calls | Yes, within the retention period |
| Retention | "Files are stored for 48 hours" |
| Per-file size | 2 GB general, **PDF capped at 50 MB** |
| Project storage | 20 GB concurrent |
| Cost | "The Files API is available at no cost in all regions where the Gemini API is available" |
| Required when | Total request size exceeds 100 MB |

**Free storage is not free inference.** `reasoned`, and this is the load-bearing correction to the
ticket's framing. Nothing in the files page or the pricing page says a handle-referenced file's
tokens are exempt from input billing, and the pricing page bills input tokens with no file carve-out.
So a Files API handle is a **bandwidth and re-upload optimization, not a token optimization**. Cost
per query is identical to sending the bytes inline. Spike S6 measures this directly rather than
trusting the inference.

**48 hours is short for a vault.** A policy read twice a year expires between every use. The handle
is a transient upload cache, not a store. Keeping 10 documents permanently resident costs
10 x (30 / 2) = 150 re-uploads a month, each of which is a SAF read plus an upload over mobile data.
`reasoned` arithmetic.

**Verdict: not the middle path the ticket hoped for.** Use it when a single document exceeds a
comfortable inline payload (a 30 MB service manual), skip it for a 2 MB policy.

---

## 3. Context caching

`traced`, caching pages:

| Fact | Value |
|---|---|
| Implicit caching | "enabled by default for all Gemini 2.5 and newer models", automatic, no config |
| Explicit caching | `generateContent` API only. Not supported in the Interactions API |
| Minimum tokens, 3.7 / 3.6 / 3.5 Flash and 3.1 Pro | 4,096 |
| Minimum tokens, 2.5 Flash / 2.5 Pro | 2,048 |
| Default TTL | "If not set, the TTL defaults to 1 hour" |
| Maximum TTL | not stated in the docs |
| Billing | cache token count at a reduced rate, **plus** storage billed on TTL duration |
| Savings signal | `usage.total_cached_tokens` in the response |

**No published minimum for Flash-Lite specifically.** The minimums table names Flash and Pro tiers.
Whether `gemini-3.5-flash-lite` is eligible at 4,096 or at all is **unstated**. Flagged, not assumed.

Pricing for `gemini-3.5-flash-lite`, `traced`: cached input **$0.03/1M**, cache storage
**$1.00/1M tokens per hour**.

**Storage is the killer, and the arithmetic is not close.** See §5c.

**Implicit caching is the part worth having.** It is on by default, costs nothing to adopt, and the
docs' own guidance is to "place large and common contents at the beginning of your prompt". `traced`.
Free upside if Kevin asks two questions about the same policy in a row. Design for it by putting the
document before the question in the prompt; do nothing else.

---

## 4. Embeddings

`traced`, embeddings page and pricing page:

| Fact | Value |
|---|---|
| Current model | `gemini-embedding-2`, "the first multimodal embedding model in the Gemini API" |
| Input token limit | 8,192 |
| Output dimensionality | 128-3072, recommended 768 / 1536 / 3072, **default 3072** |
| Text input price | $0.20/1M tokens |
| Image input price | $0.45/1M tokens |
| Prior text-only model | `gemini-embedding-001` still available, $0.15/1M |

**The 8,192-token input limit is itself a chunking requirement.** A 40-page policy at roughly 700
tokens a page is 28,000 tokens, so it cannot be embedded whole. Any embedding path forces a chunker
into existence. `traced` for the limit, `reasoned` for the page rate.

**The repo already has an embedding column and it is unpopulated.**
`app/src/main/java/com/kevin/legion/data/local/CompanionMemory.kt:52-54` carries
`embeddingVector: String?` and `embeddingModel: String?`, "null until semantic recall is wired", with
a doc comment citing a prior embeddings-feasibility research (companion-memory ticket 04) whose rule
is that stored and query vectors must share one model plus dimensionality. `traced`. That rule is the
maintenance cost the vault would inherit: **a model bump invalidates the whole index and forces a full
re-embed.** `reasoned`.

**Cost is not the objection.** Building the index is four cents (§5d). The objections are structural:
a chunker, a Room migration, a re-embed-on-file-change path that has to notice a changed file (SAF
exposes no content hash, §6), and an index that reads as authoritative while being stale.

---

## 5. The arithmetic, shown

**Model: `gemini-3.5-flash-lite`**, which is what LEGION already uses for sub-agents
(`ai/SubAgent.kt:392`, `DEFAULT_MODEL = "gemini-3.5-flash-lite"`, `traced`). Rates `traced` from the
pricing page: input **$0.30/1M**, output **$2.50/1M**, cached input **$0.03/1M**, cache storage
**$1.00/1M per hour**.

Vault and usage assumptions, all `reasoned`:

| Quantity | Value |
|---|---|
| Documents | 10 |
| Total pages | 300 (so 30 pages average per document) |
| Whole vault as native PDF | 300 x 258 = **77,400 tokens** |
| One average document as native PDF | 30 x 258 = **7,740 tokens** |
| Prompt overhead (system, question, tool scaffold) | 500 tokens |
| Answer length | 300 output tokens |
| Query rate | 5/day = **150/month** |
| Extracted-text page rate (for the embedding path only) | ~700 tokens/page |

### (a) Re-send the whole vault every query, inline

```
input   77,400 + 500 = 77,900 tok x $0.30/1M = $0.023370
output              300 tok x $2.50/1M       = $0.000750
                                       query = $0.024120
                                150 x query  = $3.618 / month
```

Routed variant, one document instead of ten:

```
input    7,740 + 500 =  8,240 tok x $0.30/1M = $0.002472
output              300 tok x $2.50/1M       = $0.000750
                                       query = $0.003222
                                150 x query  = $0.483 / month
```

### (b) Files API handle

Upload and storage $0.00 (`traced`). Token billing unchanged (`reasoned`, §2), so:

```
whole vault   = $0.024120 / query = $3.618 / month
routed        = $0.003222 / query = $0.483 / month
re-uploads    = 10 docs x (30 days / 2 day TTL) = 150 / month, $0.00, but 150 SAF reads + uploads
```

**Identical dollars to (a).** The Files API buys bandwidth, not tokens.

### (c) Explicit context caching, whole vault

```
cache size            77,400 tok = 0.0774 M
storage per hour      0.0774 x $1.00        = $0.0774 / hour
kept warm 24/7        $0.0774 x 24 x 30     = $55.728 / month
per query   cached    77,400 x $0.03/1M     = $0.002322
            uncached     500 x $0.30/1M     = $0.000150
            output       300 x $2.50/1M     = $0.000750
                                      query = $0.003222
                               150 x query  = $0.483 / month
                                      TOTAL = $56.211 / month
```

Burst variant, a fresh 1-hour cache around each of 5 query sessions a day:

```
storage   $0.0774 x 5 x 30 = $11.610 / month
queries                      $0.483 / month
                     TOTAL = $12.093 / month
```

**Break-even, the number that kills it:**

```
saving per query vs (a)  = $0.024120 - $0.003222 = $0.020898
one hour of storage      = $0.0774
queries needed in one TTL hour to break even = 0.0774 / 0.020898 = 3.70  ->  4
```

**Four queries against the same cached vault inside one hour, just to break even on that hour.**
"A few times a day" is not that shape. Caching is for a hot corpus hit continuously, not for a
document you ask about twice a year.

### (d) Embed once, retrieve chunks

```
build    300 pages x 700 tok = 210,000 tok x $0.20/1M  = $0.042  one-time
         (on gemini-embedding-001 at $0.15/1M          = $0.0315)
re-embed one new 40-page doc: 28,000 tok x $0.20/1M    = $0.0056
query embedding    ~20 tok x $0.20/1M                  = $0.000004   negligible
retrieval payload  8 chunks x 400 tok = 3,200 + 500    = 3,700 tok
input              3,700 x $0.30/1M                    = $0.001110
output               300 x $2.50/1M                    = $0.000750
                                                 query = $0.001864
                                          150 x query  = $0.280 / month
                                 first month incl build = $0.322
```

### The table

| # | Architecture | $/query | $/month (150 q) | Notes |
|---|---|---|---|---|
| a | Whole vault inline, every query | $0.02412 | **$3.62** | Zero machinery. Highest latency |
| a' | **Routed: one document inline** | $0.00322 | **$0.48** | Needs a catalog, no index |
| b | Files API handle, whole vault | $0.02412 | **$3.62** | Same tokens. 150 free re-uploads/mo |
| b' | Files API handle, routed | $0.00322 | **$0.48** | Use only for >20 MB documents |
| c | Explicit cache, warm 24/7 | $0.00322 + storage | **$56.21** | 15x worse than doing nothing |
| c' | Explicit cache, 1h bursts | | **$12.09** | Still 3.3x worse than (a) |
| d | Embeddings + chunk retrieval | $0.00186 | **$0.28** | +$0.04 build, + a whole pipeline |

**Delta (a') to (d) = $0.483 - $0.280 = $0.203 a month. $2.44 a year.** That is what the chunker,
the vector column, the Room migration, the re-embed trigger, and the drift risk are being bought for.

### Native PDF vs locally extracted text as the input

```
one 30-page document as native PDF   7,740 tok x $0.30/1M = $0.002322
same document as PdfBox text  30 x 700 = 21,000 tok       = $0.006300
```

**Native PDF is 2.7x cheaper in tokens than the text PdfBox would extract from it**, because pages
bill at a flat 258 and native text rides free. `traced` for both rates, arithmetic for the ratio.
The 700 tokens/page figure is `reasoned` and it is the soft spot: a sparse manual page at 200
tokens/page would flip the comparison. Spike S6 measures it on Kevin's real documents.

### Two cost facts that are not in the table

1. **The free tier is disqualifying for this feature.** The pricing page states most models offer
   free input/output on the free tier, with "content used to improve products". `traced`. A vault
   whose stated trajectory includes tax returns, medical records and IDs (ticket 17 item 4) must run
   on a billing-enabled key. **Say this in words on the vault's setup surface**, alongside ticket 17's
   required "documents you put here are read by Gemini when you ask about them".
2. **Nothing here is expensive.** Even the dumbest architecture is $3.62 a month. The decision is not
   about saving money; it is about not building machinery that has to be kept correct forever.

---

## 6. PDF handling: native input beats PdfBox on the answering path

### What is already wired in this repo

`traced` from the source:

| Item | Location |
|---|---|
| Dependency | `gradle/libs.versions.toml:36` `pdfboxAndroid = "2.0.27.0"`, `app/build.gradle.kts:155` |
| Plain text extraction | `ledger/parsers/PdfText.kt` - `PDDocument.load(input).use { PDFTextStripper().getText(doc) }` |
| Coordinate extraction | `ledger/parsers/PdfWords.kt` - subclasses `PDFTextStripper`, per-word x/y, page-aware |
| Mandatory init | `PdfWords.init(context)` calls `PDFBoxResourceLoader.init` - loads AFM fonts from Android assets |
| Test constraint | Robolectric required off-device to shadow `AssetManager` (`app/build.gradle.kts:183`, CLAUDE.md §3) |

So text extraction is a solved, shipped capability. The question is whether the vault should use it.

### Why not, for answering

| Axis | Native PDF to Gemini | PdfBox local extraction |
|---|---|---|
| Token cost, 30 pages | 7,740 (`traced`) | ~21,000 (`reasoned`) |
| Scanned / photographed pages | Read. "native vision to understand entire document contexts" (`traced`) | **Empty string.** No text layer, no OCR in PdfBox (`traced` from PDFBox's own scope) |
| Tables (a torque-spec grid) | Seen as an image, structure preserved (`traced`, pages rendered up to 3072x3072) | Linearized into a text stream. `PdfWords` exists in this repo precisely because `PDFTextStripper` loses columns (`traced`, its doc comment) |
| Page number for citation | Model sees discrete pages (`reasoned`) | `PdfText.extractText` returns one unsegmented string with no page markers. Per-page needs `setStartPage`/`setEndPage` or the `PdfWords` subclass (`traced`) |
| Robolectric constraint | None. No PdfBox on the path | Every vault test that touches extraction needs Robolectric |
| Known ugliness | none found | `DbsStatementParser.kt:244` documents a rotated watermark PdfBox emits as its own line; `tools/make_ledger_fixture.py:545` documents `Interest Earned 4 4 4 4 4` glyph duplication (`traced`) |

**A vault is exactly the corpus PdfBox is worst at.** Ledger PDFs are born-digital bank exports with
a clean text layer. A vault holds a photographed registration card, a scanned warranty, a service
manual full of torque tables. Three of the ticket's own example documents defeat text extraction.

### Where PdfBox still earns its place

**Catalog building, once per file, not per query.** `reasoned`:

- Extract page 1 text and the page count locally, free, offline, to build the router catalog entry.
- Fall back to a one-shot Flash-Lite title/summary call when the extraction comes back empty, which is
  itself the signal that the document is scanned.
- Cheap local keyword pre-filter if the catalog ever outgrows what fits in a router prompt.

### Limits to design against

- 50 MB / 1000 pages per request. `traced`. Check the real service manual against both before
  designing (spike S9). A 1200-page manual needs splitting, and splitting is a chunker by another name.
- Files API PDF cap is also 50 MB. `traced`. No escape hatch there.
- Pages scale to a max 3072x3072. `traced`. Fine print in a scanned manual may not survive; spike S7.

---

## 7. SAF re-read: what the vault inherits, proven and unproven

Sources: `.scratch/ledger-drive-ingestion/research/01-saf-drive-folder-findings.md` (including its
`## Device probe`, run 2026-08-02 on the Oppo A17K, Android 12), the resolved ticket 01, and
`app/src/main/java/com/kevin/legion/service/IngestScanner.kt`. All `traced` from those files;
the `tested` tags below are that probe's, carried forward per the relay rule.

### Proven on hardware, and it carries to the vault unchanged

| Claim | Tag |
|---|---|
| Drive is offered as a pickable tree root at API 31, ColorOS, Drive 2.26.297.3 | `tested` (D1) |
| `queryChildDocuments` enumerates the folder; byte reads work, `header=%PDF-` | `tested` (D1-D2) |
| **THE CRUX: a file uploaded later IS returned through the existing grant, no re-pick** | `tested` (D2) |
| No hash column exists; identity must be a LEGION-computed SHA-256 | `tested` (D5, null projection) |
| `last_modified` is per-file upload time, not the document's own date | `tested` (D6) |
| PDFs are non-virtual (`flags=455`, no `FLAG_VIRTUAL_DOCUMENT`) | `tested` (D7) |
| A null cursor is the provider refusing, not an empty folder | `tested` on 2026-08-06 per `IngestScanner.kt:144-167` |
| No new OAuth scope. `drive.appdata` untouched | `traced` |

**So the vault's core premise, "drop a file in the folder later and LEGION sees it", is settled YES
on a device.** That is the one thing the vault could not have worked without, and it is not a
`reasoned` claim.

### Unproven, and ranked by how much it hurts a vault specifically

1. **Offline reads: NEVER RUN.** `D11`, and sub-question 4 stays `traced`. This is the biggest gap and
   it is worse for the vault than for ledger. Ledger ingests once and the answer lives in Room
   forever. The vault's default posture (ticket 17 item 3, "default to no" persistence) is a **live
   read on every query**, so a Drive-backed file not marked available-offline means **no answer at
   all** in a parking garage. "What is my deductible" failing offline is a product decision the
   research cannot make: either persist a local copy of vault files (contradicting the default) or
   accept an online-only vault and say so.
2. **Non-PDF and Google-native documents: `reasoned` only (D8), and a resume is very likely a Google
   Doc.** Workspace-native files carry `FLAG_VIRTUAL_DOCUMENT` and `openInputStream` throws
   "File is virtual:"; they need `openTypedDocument` with an export MIME type. Never tested. Ledger
   never hit this because it filters to `application/pdf`. **The vault must not inherit that filter**
   (a photographed registration is `image/jpeg`, which Gemini reads natively) and must handle the
   virtual case explicitly rather than route it to UNREADABLE.
3. **Sync latency, measured and bad.** `tested` (D3): a newly uploaded file was **invisible for at
   least 2m36s** and appeared only after the Drive app was opened on the phone. D4 records that
   elapsed time alone was never isolated. `IngestScanner.kt:158-166` already ships copy for this
   ("Open the Drive app, look inside the folder so it loads, then scan again"). For the vault this is
   sharper than for ledger: the natural gesture is "I just added my policy, now ask about it", and
   that will fail for minutes with no useful signal.
4. **Reboot persistence of the grant: NEVER RUN.** `D11`. Ledger has shipped on this untested.
5. **`IngestScanner.listChildren` recurses exactly ONE level, and that cap is `reasoned`, not
   verified** (its own doc comment at `IngestScanner.kt:349-378` says so). Ledger's real layout is one
   folder deep. A vault will not be: `insurance/`, `manuals/car/`, `manuals/appliances/`,
   `warranties/2026/`. **A file two levels down is silently invisible.** This is a concrete existing
   limit the vault inherits verbatim if it reuses this class.
6. **API 30 gate against `minSdk = 24`.** `traced`. Same per-file `ACTION_OPEN_DOCUMENT` fallback
   ledger needed. Plus the runtime feature flag in Drive's `queryRoots` that Google could flip
   server-side, so tree support is a capability to probe, never assume.
7. **`acc=1` is a positional account index**, a hazard on a multi-account phone. `reasoned` (D9), one
   account was signed in during the probe.
8. **Read latency**: 637ms cached, 1248ms uncached per file. `tested`. Acceptable for a single routed
   document; not acceptable for reading ten files per query, which is a second reason to route.

### Reuse posture on `IngestScanner`

`traced`: it is ledger-specific end to end (`StatementDispatcher`, `IngestPipeline`,
`LedgerAccountMappingPreferences`, the LLM spend gate, the two-phase parse). What the vault should
lift is the **SAF listing layer only**: `queryChildDocuments`, the direct-`ContentResolver` cursor
with one binder call for all columns, the null-cursor-is-not-empty handling, `openBytes`, and the
`cacheDir/scan-*` spill-and-sweep discipline. `reasoned`: extract that into a shared helper rather
than reusing or forking `IngestScanner`.

---

## 8. On-device spikes (repo rule L10: a docs-clean result is not a done result)

None of §5's dollars and none of §6's PDF verdict has been run against Kevin's real documents or his
real phone. Listed in priority order.

| # | Spike | Settles | Why it cannot be answered from docs |
|---|---|---|---|
| S1 | Put nested subfolders 2+ deep in the vault folder, list it | The one-level recursion cap (§7 item 5) | The cap is `reasoned` in a doc comment, never run |
| S2 | Drop a Google Doc, a .docx, a .jpg and a .png in the folder. Record `mime_type` and `flags`; try `openInputStream` on the Google Doc; then try `openTypedDocument` with an export MIME | §7 item 2, the resume case | D8 is `reasoned`. Nobody has ever put a virtual document in a LEGION-visible folder |
| S3 | Airplane mode, `openInputStream` on a file never opened before. Record exception class and time to fail | §7 item 1, the offline answer | Never run. **Needs USB** - wireless ADB dies with the network |
| S4 | Reboot, then reuse the persisted tree URI | §7 item 4 | Never run. **Needs USB** |
| S5 | Upload a file from a laptop, do NOT touch the Drive app, poll the listing every 60s for 30 min | D4, whether elapsed time alone suffices | The 2026-08-02 run confounded elapsed time with opening the Drive app |
| S6 | Send one real 40-page policy and one real service manual to `gemini-3.5-flash-lite` as native PDF. Record `usageMetadata.promptTokenCount`. Send the same file twice by Files API handle and compare the two counts | Validates the 258/page assumption the ENTIRE §5 table rests on, and settles whether a Files API handle re-bills its tokens (§2, `reasoned`) | Google publishes 258/page but says nothing about handle billing |
| S7 | Same, with a phone-photo scan of a registration card and a scanned warranty | §6's scanned-page claim, and whether 3072x3072 downscaling eats fine print | "native vision" is a docs claim, not a measurement on Kevin's photos |
| S8 | Ask a real torque-spec question against the real service manual. Check the cited page number is actually right | Citation fidelity, which ticket 17 item 6 says IS the feature | An unverified citation is worse than no citation |
| S9 | `pdftk`-equivalent page count and byte size of every real vault candidate | Whether anything exceeds 50 MB / 1000 pages before the design assumes it does not | Kevin's actual manual size is unknown |
| S10 | Run `PdfText.extractText` over the real vault documents, count tokens, compare to 258/page | The `reasoned` 700 tok/page rate in §5d and §6 | If manual pages are sparse the native-PDF cost advantage narrows or inverts |

S6 and S8 are the two that can invalidate the recommendation. Run them first.

---

## 9. Recommendation, on simple-first grounds

**Two pull-based tools, no index, no cache, no chunker.** This satisfies CLAUDE.md §7's
"pull-based tools always" directly rather than pre-injecting a retrieval blob.

```
list_documents()                 -> titles + one-line summaries from Room, ~300 tokens
read_document(id, question)      -> whole file as native PDF/image inline, answer with page citation
```

- **Room: one additive table**, `vault_documents`, same shape as `ingested_files`: SAF document id,
  Drive file id, display name, mime type, size, last modified, LEGION-computed SHA-256, title,
  one-line summary, page count, optional aspect tag (ticket 17 item 5). **No embedding column, no
  chunk table.** That is a v21 -> v22 additive migration, verbatim generated SQL.
- **Extracted values are NOT persisted.** Ticket 17 item 3's default holds. The catalog stores file
  identity and a summary, which is an index, not an extracted fact. Nothing that could later read as
  an asserted figure goes in Room.
- **Implicit caching for free**: put the document before the question in the prompt, per Google's own
  ordering guidance. Do nothing else about caching.
- **Files API only above ~20 MB.** Below that, inline the bytes. Track the 48h expiry keyed by
  SHA-256 so a stale handle is re-uploaded, not reused.
- **PdfBox only at catalog time**, for page count and page-1 text. An empty extraction is the signal
  that the document is scanned, and it costs nothing to notice.
- **Same model as the rest of LEGION**: `gemini-3.5-flash-lite`. No reason to break the pattern.

**Cost: ~$0.0033 a query, ~$0.50 a month at five queries a day.** The router call adds roughly
400 tokens ($0.00012), which rounds away.

**Revisit embeddings only if two things become true at once:** the catalog stops fitting in a router
prompt (roughly 100+ documents), and a single document routinely exceeds the per-request page ceiling.
Neither is near. Until then the index is $2.44 a year of savings against a permanent correctness
obligation.

---

## Citations

| Source | URL | Read |
|---|---|---|
| Gemini API pricing | https://ai.google.dev/gemini-api/docs/pricing | 2026-08-16 |
| Gemini API models overview | https://ai.google.dev/gemini-api/docs/models | 2026-08-16 |
| Gemini 3.7 Flash model page (1,048,576 / 65,536) | https://ai.google.dev/gemini-api/docs/models/gemini-3.7-flash | 2026-08-16 |
| Gemini 2.5 Flash-Lite model page (1,048,576 / 65,536) | https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-lite | 2026-08-16 |
| Files API (48h, 50MB PDF, 20GB, free) | https://ai.google.dev/gemini-api/docs/files | 2026-08-16 |
| Context caching (implicit default, minimums) | https://ai.google.dev/gemini-api/docs/caching | 2026-08-16 |
| Explicit caching, TTL defaults to 1 hour | https://ai.google.dev/gemini-api/docs/generate-content/caching | 2026-08-16 |
| Embeddings (`gemini-embedding-2`, 8,192, 128-3072) | https://ai.google.dev/gemini-api/docs/embeddings | 2026-08-16 |
| Document understanding (258 tok/page, 50MB/1000 pages, native vision) | https://ai.google.dev/gemini-api/docs/document-processing | 2026-08-16 |
| Understand and count tokens (~4 chars/token) | https://ai.google.dev/gemini-api/docs/tokens | 2026-08-16 |
| Long context (RAG comparison, latency caveat) | https://ai.google.dev/gemini-api/docs/long-context | 2026-08-16 |
| SAF Drive-folder findings + device probe | `.scratch/ledger-drive-ingestion/research/01-saf-drive-folder-findings.md` | in repo |
| SAF feasibility ticket resolution | `.scratch/ledger-drive-ingestion/issues/01-saf-drive-folder-feasibility.md` | in repo |
| Shipped SAF scan implementation | `app/src/main/java/com/kevin/legion/service/IngestScanner.kt` | in repo |

**Not found, recorded so nobody re-walks it:**

- The models overview page does not carry a token-limit table. Per-model pages do. Fetch those.
- The rate-limits page no longer publishes free-tier RPM/TPM/RPD numbers; it defers to AI Studio.
  Free-tier headroom for a vault could not be established from docs.
- No page states a maximum explicit-cache TTL.
- No page states a minimum cache token count for **Flash-Lite** specifically; the table covers Flash
  and Pro tiers only.
- **No page states whether a Files API handle re-bills its tokens per call.** This is the single most
  load-bearing `reasoned` claim in the file. Spike S6.

---

## Assumptions ledger

| # | Claim | Tag |
|---|---|---|
| 1 | Flash-Lite 3.5 and Flash 3.7 both accept 1,048,576 input tokens | `traced` |
| 2 | A PDF page bills at 258 tokens and its native text is not charged | `traced` |
| 3 | A 300-page vault is 77,400 tokens, 7.38% of the window | arithmetic on 1 and 2 |
| 4 | Files API is free, 48h retention, 50MB per PDF, 20GB per project | `traced` |
| 5 | A Files API handle still bills its tokens as input on every call | **`reasoned`. Spike S6** |
| 6 | Explicit cache storage is $1.00/1M tokens/hour on Flash-Lite; TTL defaults to 1 hour | `traced` |
| 7 | Caching this vault warm costs $56.21/month, and needs 4 queries per TTL hour to break even | arithmetic on 6 |
| 8 | Whether Flash-Lite is eligible for explicit caching at all | **unstated in docs** |
| 9 | `gemini-embedding-2` is $0.20/1M text, 8,192 token input limit, default 3072 dims | `traced` |
| 10 | Building the index costs $0.042 and saves $0.203/month over routed long-context | arithmetic on 9, over `reasoned` page rates |
| 11 | Extracted text runs ~700 tokens/page, so native PDF is 2.7x cheaper | **`reasoned`. Spike S10** |
| 12 | Gemini reads scanned pages via native vision; PdfBox returns nothing for them | `traced` for Gemini, `traced` for PdfBox's scope |
| 13 | `PdfText.extractText` returns one unsegmented string with no page markers | `traced` (repo source) |
| 14 | LEGION already ships PdfBox 2.0.27.0 and needs Robolectric to test it off-device | `traced` (repo) |
| 15 | `CompanionMemory` already carries unpopulated embedding columns and a same-model/same-dims rule | `traced` (repo) |
| 16 | SAF returns files added after the grant, no re-pick | `tested` 2026-08-02 (D2) |
| 17 | A newly uploaded file was invisible for at least 2m36s and appeared only after the Drive app opened | `tested` (D3); whether time alone suffices is **unknown** (D4) |
| 18 | Offline read behaviour and reboot grant persistence | **never run** (D11). Spikes S3, S4 |
| 19 | Google-native documents are virtual and would fail `openInputStream` | **`reasoned`** (D8). Spike S2 |
| 20 | `IngestScanner` recurses exactly one level and that cap is unverified on-device | `traced` (its doc comment states both) |
| 21 | The free tier uses content to improve products, so the vault needs a billing-enabled key | `traced` (pricing page) |
| 22 | Recommended architecture costs ~$0.0033/query, ~$0.50/month | arithmetic on 1, 2 and the pricing page |
| 23 | Nothing in this file was run on a device or against Kevin's real documents | `tested` (no spike executed this session) |
