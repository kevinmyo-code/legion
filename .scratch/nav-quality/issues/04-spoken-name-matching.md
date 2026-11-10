---
map: nav-quality
ticket: "04"
title: "Build: spoken-name matching (fuzzy, brand, near a place I know)"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-baseline]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: spoken-name matching (fuzzy, brand, near a place I know)

Google answers "the Starbucks near work". LEGION strips filler (`QueryText.normalize`) and stops.

## Change

1. **"X near Y" and "X by Y".** When Y resolves through a first-party source (a saved place, a
   contact's address, a calendar event), search X with proximity at Y, not the live fix. If Y does
   not resolve, say so: "I don't know where work is"; never a silent fallback to here.
2. **Brand.** A phrase whose term is a chain name goes through `brandSearch`. Chain detection uses the
   existing same-name run in `SearchAmbiguity`, which already treats a chain as unambiguous.
3. **Fuzzy.** `SearchOptions.fuzzyMatch(true)` on text search (tested: present in 2.32.0). Saved
   places and contacts get edit-distance matching for speech-to-text slips ("Jims" for "Jim's",
   "Kroger's"), with a threshold pinned by a test.
4. **Homophones.** Try a second normalisation before reporting nothing (numbers spoken as words,
   "saint" and "st"), and only then report nothing.

## Proven by NAV_AUDIT

- Zero-result rate (`verdict=not_found`) down. The baseline's zero-result phrases are the test list:
  replay each through the resolver in a unit test.
- `picked_rank` "abandoned" share down: fewer read-backs that never got a yes.
- `answered_by`: more first-party answers for phrases that name a known place.
