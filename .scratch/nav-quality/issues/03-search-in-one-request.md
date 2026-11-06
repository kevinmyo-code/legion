---
map: nav-quality
ticket: "03"
title: "Build: one request for text search, five results, ETA per result"
type: build
status: built
status-detail: "2026-11-06 on feat/nav-audit: forward, batched-select fallback, drive ETA for nearest; needs the phone"
blockers: ["01"]
blocked-by: ["[[01-baseline]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: one request for text search, five results, ETA per result

**Traced:** `MapboxPlaceSearch.textSearch` calls `search()` (suggest), then `select()` on at most
three suggestions **one after another** (`MAX_SELECTS = 3`), although the query asks for five. A
text search is four round trips, and never more than three places.

## Change

- Use `SearchEngine.forward(query, ForwardSearchOptions, callback)` (tested: present in Search SDK
  2.32.0). One request, full results with coordinates, up to `limit`. Proximity is still always the
  live fix.
- Ask for the drive-time ETA per result (`navigationOptions` / `eta_type=navigation`) when the user
  asked for "nearest". "Nearest" then means nearest by drive, not by line. This is billed as Matrix
  elements; 100,000 a month are free.
- Fallback if `forward` returns nothing a suggest would have found: one batched
  `select(List<SearchSuggestion>, ...)`, never sequential selects.
- `search` stays one resolver source, so its `tried=` entry still times it.

## Billing (decision D3, ruled 2026-11-06: `forward`)

Per request: 25,000 free a month, against 2,500 sessions (Mapbox pricing, 2026-11-06). Two users are
far inside either.

## Proven by NAV_AUDIT

- `tried` entries for `search`: p50 and p95 down against ticket 01's baseline. Expect roughly one
  round trip instead of four (reasoned).
- `results` on `search`-answered lines: mean up from at most 3 toward 5.
- Ambiguous rate: does not rise. More candidates must not mean more questions.

Verify on the phone: a typed "Starbucks", a spoken "nearest gas station", an address.

## Built (2026-11-06, `feat/nav-audit`)

Kevin's "all of it" (2026-11-06) ordered the build ahead of ticket 01's baseline. **Record ticket 01's
baseline on a build without this change before merging it**, or the "before" these numbers are judged
against is lost.

- `MapboxPlaceSearch.textSearch`: one `SearchEngine.forward` with proximity from the live fix and the
  query's limit (five). On an empty answer only: one suggest, then ONE `select(List<SearchSuggestion>)`
  over the batch-resolvable suggestions. A transport failure returns as before, no fallback.
- "Nearest": `SearchQuery.nearest` asks for a drive-time ETA. Text: `navigationOptions(DRIVING,
  EtaType.NAVIGATION)` plus `origin`. Category: `origin`, `navigationProfile(DRIVING)` and
  `unsafeParameters(eta_type=navigation)`, because the category builder has no ETA type (reasoned from
  the Search Box reference; not observed). `SearchAmbiguity.byDriveTime` orders hits by ETA, those
  without one last. `Candidate.etaMinutes`; `top=` carries `eta_min`.
- Tests (tested): the nearest ask sets `nearest`, ETA order wins over line distance, a plain search
  keeps Mapbox's order; `eta_min` in `top=`. The SDK path itself needs its native library, so no unit
  test constructs `MapboxPlaceSearch`.

### Owed on the phone

- A typed "Starbucks": the `search` line's `search` entry in `tried=` near one round trip (well under
  the old four), `results=5` where five exist.
- A spoken "nearest gas station": `eta_min` on the `top=` entries. **If it is missing, the category
  `eta_type` pass-through did not take**; the text path's ETA is SDK-native.
- An address: resolves as before, one request.
