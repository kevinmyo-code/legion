---
map: dev-aspect
ticket: "08"
title: "The voice surface and its hands path"
type: build
status: open
status-detail: ""
blockers: ["01", "04", "05", "07"]
blocked-by: ["[[01-seventh-aspect-on-the-engine]]", "[[04-does-the-summary-earn-its-place]]", "[[05-the-github-sync]]", "[[07-the-staleness-contract]]"]
open-blockers: 4
ready: false
tags: [ticket]
---
# The voice surface and its hands path

## Build

The thing Kevin actually asked for: *"i want to ask the voice ai what projects i have, whats pending
on which project."*

**If ticket 01 put the aspect on the engine, most of this is already built** - the engine's generic
meta-tools (aspect-engine ticket 06) and generated screens (10) cover CRUD and listing, and this
ticket shrinks to the queries the generic tools do not express well. Establish that first rather
than writing a parallel toolset; two implementations of one capability drift into disagreeing, which
is the reasoning behind ADR 0035.

**Queries that likely need naming explicitly:**

- what projects do I have (excluding archived)
- what is pending on {project}
- what is pending across everything, ordered by staleness or by count
- what have I not touched in a while - derived from `pushed_at`, a fact, not a judgement

**ADR 0035 binds: every voice capability has a hands path**, calling the same controller, not a
second implementation. Voice is the fastest way in and it is the one that fails - a loud room, a
wake word that does not fire, a closed socket. A dev aspect reachable only by voice is not finished.

The hands path also carries what voice cannot: sync status and failures (ticket 07), the item URLs,
and a manual re-sync trigger.

**Copy rules.** No assistant name hardcoded anywhere. If ticket 04 kept `summary_text`, its
estimate label appears in the tool description and in the user-facing string, and does not collapse
behind a HelpRow.

## Verification

- Every voice query has a hands equivalent reaching the same controller. Listed one by one, not
  asserted in general.
- The estimate labelling test from ticket 04 passes.
- Run on the real phone: ask the three main queries, confirm the staleness wording appears, confirm
  a project with no open items does not read as unsynced. Stated plainly as owed until done - a
  built ticket owing a phone run is `built`, not `resolved`.
