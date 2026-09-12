---
map: web-surface
ticket: "07"
title: "Re-brief web-and-households 06 with what the real data turned out to look like"
type: task
status: open
status-detail: ""
blockers: ["01", "03"]
blocked-by: ["[[01-the-horizon]]", "[[03-desktop-is-not-the-phone]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# Re-brief the aspect screens

`web-and-households` ticket 06 ("Web screens phase 2: Ledger, Pantry, Body, Fleet, Places, Voice
notes, and a glanceable home") was written on 2026-09-08, before anyone had seen the web render real
data. Three things learned since change its brief:

1. **The two surfaces have different jobs**, settled 2026-09-12. 06 says "web screens" as if there
   were one audience. Ledger, ingestion and every editable table are desktop-only; the PWA must not
   grow a route to them.
2. **The visual language exists now** - family-first, drawn in `docs/design/canvas/`. 06 predates it.
3. **The trust disclosures are the hard part, not the tables.** Every aspect 06 names carries
   figures the §4 gate touches, and `docs/design/today.md` warned on day one not to set a polish tone
   the ledger then has to break. The canvas has a worked example; 06 has no mention of it.

This ticket does not build anything. It updates 06's text so whoever picks it up starts from what is
true, and it closes when 06 reads correctly.

**Do not renumber or re-map 06.** It stays on `web-and-households`; this is an amendment, the same
shape as `one-home` 04's build-time correction.
