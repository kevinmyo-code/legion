---
map: nav-quality
ticket: "08"
title: "Build: open now and rating in near-me ranking"
type: build
status: open
blockers: ["03"]
blocked-by: ["[[03-search-in-one-request]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: open now and rating in near-me ranking

"Tacos near me" at 23:00 should not offer a place that closed at 21:00. Mapbox returns
`open_hours`, `rating` and `price_level` "if available" (Search Box docs), and the SDK exposes
them on `SearchResultMetadata` (tested: `getOpenHours`, `getRating`, `getReviewCount`).

## Change

- For a "near me" or category search, drop results Mapbox says are closed now. Keep results with no
  hours, but rank them below the open ones. Say "open now" only when the hours say so. Unknown hours
  are said as unknown (CLAUDE.md sec 1).
- The read-back names the pick's hours when known: "Torchy's, 1.2 miles, open until 10".
- Spoken "open now" and "good" in the phrase become filters (`open_now`, `minimum_rating`,
  through `unsafeParameters` if the builder lacks them).
- `NavAudit.topJson` grows `"open":true|false|null`. That is a fact about the result, not its name
  (ticket 02 confirms).

## Proven by NAV_AUDIT

- Category searches (`tried` shows a category hit): `dismissed` rate and rank > 1 share go down.
- The share of top picks with `"open":false` goes to zero.
