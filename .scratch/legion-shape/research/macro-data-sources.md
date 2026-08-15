# Is there a keyless food/macro data source LEGION could use?

Research for ticket `12-macro-data-source.md`. Charted 2026-08-07.

## Recommendation

**Use the LLM estimate, labelled as an estimate — and bundle a small trimmed USDA subset only if
ticket 09 later shows the LLM is not good enough for repeat generic foods.** No option clears all
of LEGION's constraints better than what already exists. USDA FoodData Central's *API* is
disqualified outright: a data.gov key is mandatory on every request and the keyless `DEMO_KEY`
path is capped at 50 requests per IP per day, so it fails clone-and-run exactly the way Firebase
did. USDA's *bulk data* is genuinely clean — public domain, CC0 1.0, no permission needed — and a
generic-foods subset (SR Legacy + FNDDS, macros only) is plausibly ~1-2 MB in `assets/`, which is
noise against a 70 MB debug APK. But it covers commodity and survey foods, not US grocery brands;
the branded coverage lives in the Branded Foods set at 428 MB zipped / 2.9 GB unzipped, which is
not shippable, and it is keyed to barcodes in practice, which would reintroduce a scanner LEGION
deliberately dropped. Open Food Facts is keyless for reads but is ODbL: bundling a trimmed copy in
a public repo is *publishing a Derivative Database*, which triggers share-alike plus an obligation
to offer recipients a machine-readable copy. That is compliable, not free. Against all that, the
LLM route has zero new dependencies, already ships in `pantry/PantryReceiptAgent.kt`, already
carries the §4 rule-5 "estimate" labelling, and works from a free-text food description — which is
what ticket 09 actually has, since a logged meal has no barcode.

## Comparison

| Option | Keyless? | Licence | Bundleable in APK | Size | Generic/home-cooked | US brands | Needs barcode |
|---|---|---|---|---|---|---|---|
| **LLM (Gemini, existing)** | n/a — user's own BYO key | n/a | n/a | 0 | Yes | Yes (by name) | No |
| USDA FDC **API** | **No** — data.gov key required; DEMO_KEY 50/day/IP | CC0 1.0 | n/a | 0 | Yes | Yes | No |
| USDA FDC **bulk, generic subset** (SR Legacy + FNDDS) | n/a (offline) | CC0 1.0, public domain | Yes, no obligations | SR Legacy CSV 6.7M zip / 54M raw; FNDDS CSV 200M zip / 1.6G raw; trimmed est. ~1-2 MB | **Yes, strong** | No | No |
| USDA FDC **Branded** | n/a (offline) | CC0 1.0 | Not realistically | 428M zip / 2.9G raw | No | Yes | In practice yes |
| Open Food Facts **API** | **Yes** (User-Agent only) | ODbL / DbCL | n/a | 0 | Weak | Partial (crowdsourced) | Search exists, but barcode is the strong path |
| Open Food Facts **bulk** | n/a (offline) | ODbL — share-alike + supply-a-copy | Legally yes, with obligations | CSV ~0.9 GB gz / ~9 GB raw; parquet 4.73M rows | Weak | Partial | Mostly |
| OpenNutrition dataset | n/a (offline) | ODbL + modified DbCL, attribution required | Yes, with obligations | TSV in a ZIP, size not published | Yes | Yes | No |

## Detail

### 1. USDA FoodData Central — API

- **A key is mandatory.** "However, a data.gov API key must be incorporated into each API request."
  — <http://fdc.nal.usda.gov/api-guide/>
- **The keyless fallback is `DEMO_KEY`, and it is unusable for a shipped app:** "Hourly Limit: 30
  requests per IP address per hour", "Daily Limit: 50 requests per IP address per day".
  — <http://fdc.nal.usda.gov/api-guide/>
- A signed-up key gets "1,000 requests per hour per IP address", 429 on overage.
  — <http://fdc.nal.usda.gov/api-guide/>
- api.data.gov keys require email verification ("The API key supplied has not been verified yet.
  Please check your e-mail to verify the API key") and the developer manual says keys "Should be
  kept private and should not be shared." — <https://api.data.gov/docs/developer-manual/>
- Verified live: `GET https://api.nal.usda.gov/fdc/v1/foods/search?query=chicken&pageSize=1&api_key=DEMO_KEY`
  returns HTTP 200 with `totalHits: 19803` (Branded 18,956 / Survey 445 / SR Legacy 392 /
  Foundation 10). So `DEMO_KEY` works — at 50 calls per day per IP.

**Verdict: fails clone-and-run.** Baking Kevin's key into a public repo is a shared secret and a
rate-limit shared across every installer. `DEMO_KEY` is a demo, not a runtime.

### 2. USDA FoodData Central — bulk download

- **Licence is the cleanest available anywhere.** "USDA FoodData Central data are in the public
  domain and they are not copyrighted. They are published under CC0 1.0 Universal (CC0 1.0)" and
  "No permission is needed for their use, but we request that users list FoodData Central as the
  source of the data, and when possible, notify us of the product that uses the data".
  — <https://fdc.nal.usda.gov/index.html>, repeated at <http://fdc.nal.usda.gov/api-guide/>
- Suggested citation: "U.S. Department of Agriculture, Agricultural Research Service, Beltsville
  Human Nutrition Research Center. FoodData Central. [Internet]. [cited (enter date)]. Available
  from https://fdc.nal.usda.gov/." — <https://fdc.nal.usda.gov/index.html>
- **Sizes, from the publisher's own download table** — <http://fdc.nal.usda.gov/download-datasets/>:

  | Dataset | Release | Format | Zipped | Unzipped |
  |---|---|---|---|---|
  | Foundation Foods | 04/2026 | JSON | 459K | 6.5M |
  | Foundation Foods | 04/2026 | CSV | 3.7M | 32M |
  | SR Legacy | 04/2018 | JSON | 12.3M | 205M |
  | SR Legacy | 04/2018 | CSV | 6.7M | 54M |
  | FNDDS (Survey) | 10/2024 | CSV | 200M | 1.6G |
  | Branded | 04/2026 | CSV | 428M | 2.9G |
  | Branded | 04/2026 | JSON | 195M | 3.1G |
  | All data types | 12/2025 | CSV | 460M | 3.1G |

- **What each type is for** — <http://fdc.nal.usda.gov/data-documentation/>:
  - Foundation Foods: "individual samples of commodity/commodity-derived minimally processed foods".
  - SR Legacy: "Historic data on food components including nutrients" — final release 04/2018, frozen.
  - FNDDS / Survey: "nutrients and portion weights for foods and beverages reported in What We Eat
    in America, NHANES" — this is the home-cooked/composite-dish set, with portion weights, which
    is precisely what a logged meal needs.
  - Branded: "Data from labels of national and international branded foods".
- Because it is CC0, **bundling any subset in a public GitHub repo and in an APK carries no licence
  obligations at all** beyond the requested (not required) attribution. This is the only option
  where the answer to question 4 is "nothing to comply with".

**Verdict: viable as a bundled asset, for generic foods only.** Branded is out on size.

### 3. Open Food Facts

- **Reads are genuinely keyless.** "READ operations (getting info about a product, etc...) do not
  require authentication other than the custom User-Agent." Format required:
  `AppName/Version (ContactEmail)`. Rate limits: "15 req/min/IP address for all read product
  queries" and "10 req/min/IP address for all search queries"; the limits "apply per user if
  requests originate from end users (like in a mobile app)".
  — <https://openfoodfacts.github.io/openfoodfacts-server/api/>
- **Licence is split three ways**, and this is the load-bearing fact:
  - Database structure: Open Database License (ODbL).
  - Database contents: Database Contents License (DbCL) 1.0.
  - Product images: CC BY-SA 3.0.
  — <https://world.openfoodfacts.org/data>
- Re-users must "mention the licence and to attribute the authorship to Open Food Facts with a link
  to https://openfoodfacts.org"; "Derivative works must be shared under the same conditions"; and
  third-party rights (trademark, image rights, packaging design copyright) are explicitly *not*
  covered — "It is the responsibility of individuals and entities who wish to re-use the
  information, data and/or photos to verify by themselves the rights that may apply."
  — <https://world.openfoodfacts.org/terms-of-use>
- **ODbL, read against its own text** — <https://opendatacommons.org/licenses/odbl/1-0/>:
  - §4.4a: "Any Derivative Database that You Publicly Use must be only under the terms of: This
    License; A later version of this License...; or A compatible license."
  - §4.6: you must offer recipients of a publicly used Derivative Database "a copy in a machine
    readable form" of either the whole derivative database or a file of all alterations made.
  - §4.5b: producing a Produced Work by querying the database "does not create a Derivative
    Database", so a *runtime API call* only triggers the §4.3 attribution notice, not share-alike.
  - §4.7a: no technological measures that "alter or restrict the terms of this License".
  - **Consequence for LEGION:** calling the API at runtime is cheap compliance (a credit line).
    Trimming the dump into a bundled SQLite and publishing that repo/APK is publishing a Derivative
    Database — ODbL on that file, plus a machine-readable copy offered to users. Compliable in a
    public repo, but it is a standing obligation, not a free lunch.
- **Bulk sizes** — <https://world.openfoodfacts.org/data>: CSV export "~0.9 GB compressed; ~9 GB
  uncompressed"; MongoDB dump `openfoodfacts-mongodbdump.gz`; JSONL
  `openfoodfacts-products.jsonl.gz`; parquet on Hugging Face at 4.73M rows (4.66M food) with 150+
  columns, licensed `odbl` / `agpl-3.0` — <https://huggingface.co/datasets/openfoodfacts/product-database>.
- **Coverage:** the US site's own search box reports "947,875 products"
  — <https://us.openfoodfacts.org/>. Crowdsourced, so completeness and nutrient-field fill rate
  vary per product. It is a *packaged-product* database — it is indexed by barcode and has
  essentially nothing for "grilled chicken thigh, two of them".

### 4. OpenNutrition

- Aggregates USDA, CNF, FRIDA, AUSNUT and Open Food Facts into one TSV
  (`opennutrition_foods.tsv`, in a ZIP) — <https://www.opennutrition.app/download>.
- Licence: "Open Database License (ODbL)" with "a modified Database Contents License (DbCL)".
  Attribution to "OpenNutrition" with a link is required "in every display interface, app store
  listings, websites, and legal sections", plus "(c) Open Food Facts contributors" attribution for
  the OFF-derived portion. Derivative databases must use identical terms.
  — <https://www.opennutrition.app/download>
- **File size and food count are not published on the download page.** Third-party listings claim
  300k-ish foods; not verified against a primary source, so not relied on here.
- It carries OFF's ODbL obligations without OFF's governance, and its USDA-derived portion is
  available directly from USDA under CC0 with no obligations at all. No reason to prefer it.

### 5. Barcode question

A barcode is what makes a branded database usable, and the branded databases are the only ones that
need one. Open Food Facts' strong path is `GET /api/v2/product/{barcode}`; its search endpoint
exists but at 10 req/min and against crowdsourced product names. USDA Branded is likewise
GTIN-indexed.

If a scan were ever wanted, **ZXing is not the only route** — `com.google.android.gms:play-services-code-scanner:16.1.0`
is "a complete solution for scanning code without requiring your app to request camera permission",
delegating to Play services with an unbundled library — <https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner>.
So reintroducing barcodes would not literally resurrect the dropped dependency. But it *would*
resurrect the workflow: point the phone at a package. Ticket 09 logs meals, and a meal is not a
package. The barcode path answers a different question than the one 09 asks.

### 6. The LLM route

- Already built and shipping: `app/src/main/java/com/kevin/legion/pantry/PantryReceiptAgent.kt`
  prompts for `caloriesKcal` / `proteinG` / `carbsG` / `fatG` per line item, each marked "estimate"
  in the schema string, with the file's own doc comment stating "Per-item macro estimates are never
  reconciled". The §4 rule-5 labelling discipline is already implemented, not merely planned.
- Zero new dependencies, zero new assets, no licence surface, no attribution obligation, and it
  runs on the user's own BYO Gemini key — same shape as every other LLM call in the app.
- It takes free-text ("two eggs and a slice of sourdough"), which is the actual input shape of a
  logged meal. Every database option needs either an exact food name match or a barcode.
- It degrades offline by failing, not by lying — the macros come back null, same as an illegible
  receipt line today.
- Its weakness is real: it is a guess, unreproducible run to run, and worse on ambiguous portions
  than a table lookup. That is exactly why §4 rule 5 bars it from the reconciliation gate and
  requires the "estimate" label. Ticket 09 should carry the same rule.

**If 09 finds the LLM too loose in practice**, the cheap upgrade is not a swap — it is a lookup
layer in front of it: bundle a trimmed SR Legacy + FNDDS macros table (CC0, no obligations), hit it
first on a normalized food-name match, fall through to the LLM when it misses, and tag provenance
the same way ledger tags `DETERMINISTIC` vs `LLM_RECONCILED`. That is the §4 rule-1 pattern applied
to macros, and it is the only database option whose licence costs nothing.

## What I could not verify

- **Trimmed asset size (~1-2 MB) is a calculation, not a measurement.** Reasoned from ~7,800 SR
  Legacy foods plus ~7,000 FNDDS survey foods at description + 5 numeric fields per row. Nobody has
  built the extract. Verify by actually building it before committing to a number.
- **Food counts per FDC data type are not published** on `/data-documentation/`. The counts above
  for SR Legacy and FNDDS are commonly cited but I found no USDA page stating them. The only counts
  I confirmed first-hand are from a live `DEMO_KEY` query for "chicken".
- **OpenNutrition's file size and food count** are absent from its own download page. Third-party
  numbers exist; not treated as fact here.
- **`947,875 products` on us.openfoodfacts.org** is the figure the US-facing search box displays. I
  did not confirm from a primary source whether that is products sold in the US or a filtered
  subset, nor what fraction have complete macro fields.
- **Open Food Facts CSV/JSONL byte sizes** are as stated on `/data`; I did not download to confirm.
  The parquet row count (4.73M) is from the Hugging Face dataset card, which is OFF-published.
- **Whether USDA would object to a bundled APK subset** — not asked. CC0 means no permission is
  needed, so the question is courtesy only; USDA asks to be notified of products using the data.
- **The Google code scanner's cost/key requirements** are not stated on its own doc page. Moot
  unless the barcode route is revived.
