---
map: legion-shape
ticket: 12
title: "Is there a keyless food/macro data source LEGION could use?"
type: research
status: resolved
status-detail: "2026-08-07, research"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Is there a keyless food/macro data source LEGION could use?

## Question

Ticket 09 needs calories and macros for logged meals. Two possible routes: an LLM estimate from the
food description, or a real food database. LEGION's constraints are unusually strict and rule most
options out, so this needs checking before 09 can be decided.

Constraints, from `CLAUDE.md` §7:
- **No Kevin-hosted anything.** No backend, no proxy, no server-side key holder.
- **Clone-and-run.** A stranger clones, sideloads, and it works - so an API needing a signup key is
  a problem in the same way Firebase was.
- **Assets are bundled**, never fetched at runtime, if they are static.
- **Network calls degrade gracefully offline.**
- Precedent: `weather/WeatherController` uses Open-Meteo specifically because it is keyless.

Find out:
1. Which food/nutrition databases are genuinely keyless, or offer a redistributable offline dataset
   that could ship in `assets/` (USDA FoodData Central, Open Food Facts, others).
2. Licence terms for bundling any such dataset in a public GitHub repo.
3. Rough size on disk - a dataset that doubles the APK is a different decision.
4. Coverage for the kind of food actually eaten (US grocery brands and home cooking), not just
   packaged barcodes.
5. Whether barcode scanning is required to make a database useful, which would reintroduce ZXing -
   deliberately dropped in the pivot.
6. How an LLM estimate compares in practice, given it is already the pantry precedent and needs no
   dependency at all.

Output a findings file at `.scratch/legion-shape/research/macro-data-sources.md` with a
recommendation and the licence facts stated as facts with sources.

---

## Resolution (2026-08-07)

**Use the LLM estimate, labelled as an estimate.** Findings and every source URL:
`.scratch/legion-shape/research/macro-data-sources.md`.

| Option | Verdict | Why |
|---|---|---|
| USDA FDC **API** | **Disqualified** | A data.gov key is mandatory per request. Keyless `DEMO_KEY` is capped at 50 requests per IP per day - verified by a live call. Fails clone-and-run exactly as Firebase did. |
| USDA **bulk** | Viable fallback only | CC0 1.0 public domain, "no permission is needed" - the only option with zero bundling obligations. But the shippable sets (SR Legacy 6.7M zip, FNDDS 200M zip) carry generic and home-cooked foods and **no US grocery brands**. Brands live only in the 428M zip / 2.9G raw Branded set, which cannot ship. |
| Open Food Facts | Rejected for bundling | Reads are genuinely keyless (custom User-Agent, 15 req/min). But the database is **ODbL**: bundling a trimmed dump in a public repo publishes a *Derivative Database*, triggering §4.4a share-alike and §4.6's duty to offer a machine-readable copy. Compliable, not free. Runtime API calls would be cheap compliance (§4.5b) if a network route were ever wanted. |
| OpenNutrition | Rejected | ODbL plus a modified DbCL with mandatory in-app and store-listing attribution. Its USDA portion is available direct from USDA under CC0 anyway. |
| **LLM estimate** | **Chosen** | Zero new dependencies, no licence surface, accepts free text rather than needing a barcode. |

**The deciding argument is that it already exists.** `pantry/PantryReceiptAgent.kt` already prompts
for kcal/protein/carbs/fat per line item, already tags each as an estimate in its schema, and its
doc comment already states they are never reconciled. CLAUDE.md §4 rule 5 already requires exactly
this labelling, and ticket 02's *reported* tier is the same idea generalised. Meals needs no new
machinery for macros - it needs the machinery pantry has, pointed at a meal instead of a receipt.

**Barcodes stay dropped.** Only branded databases need one, and a plate of food has no package. Noted
for accuracy: ZXing would not literally return (`play-services-code-scanner` exists), but the
scan-a-package workflow answers a different question than ticket 09 asks.

**Stated as unverified rather than inferred as fact:** the ~1-2 MB trimmed-asset figure is a
calculation, not a measurement; FDC publishes no per-type food counts; OpenNutrition publishes
neither file size nor food count; the 947,875 US Open Food Facts product count is what their search
box reports, scope unconfirmed.

**Consequence for ticket 09:** the macro question is answered, so 09 is no longer blocked by 12. It
remains blocked by 05 and 07.
