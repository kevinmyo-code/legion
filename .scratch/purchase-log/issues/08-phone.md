---
map: purchase-log
ticket: "08"
title: "Phone: voice tools, the log screen, last-bought label"
type: build
status: built
status-detail: "Built, suite green; owes a run on the phone (log by voice and by hand, ask, Groceries tick then same-day untick, server down)"
blockers: ["04", "05", "06"]
blocked-by: ["[[04-the-phones-half]]", "[[05-where-it-lives-on-screen]]", "[[06-server]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Phone: voice tools, the log screen, last-bought label

## Build

Per 04-06: phone voice tools (log a purchase, when did we last buy X, list recent) with honest
results, a hands path calling the same controller (ADR 0035), the Groceries list showing last bought.
`voice_guide_copy.py` updated. Gates per CLAUDE.md §6.

## Built (2026-10-04, feat/purchase-phone)

- **Controller:** `purchases/PurchasesController.kt` (a plain class over `PurchasesBackend`, fakeable),
  `purchases/PurchaseWording.kt` + `PurchaseFailures.kt` + `GroceriesLabel.kt` (every sentence, shared by
  voice, screens and the Groceries label),
  `backend/engine/DjangoPurchasesBackend.kt` (REST with the device token). Four outcomes, never an
  empty list for "could not read": ok, unreachable, refused, unconfirmed (a 2xx that did not decode).
  A write is reported done only from a 2xx. No Room table, no sync (ticket 04).
- **Voice:** ONE tool, `bought_log` (`service/PurchaseToolbox.kt`), `action` = log / last / recent,
  not three tools: the Live setup payload ceiling has ~8 tokens of headroom left (measured
  ~22,492 of 22,500; the tool cost ~213). `get_last_ticked`'s description now points "when did we
  last buy X" at `bought_log`.
- **Hands (ADR 0035), variant C:** a "Bought log" button in the Lists page's top bar opens
  `ui/bought/BoughtScreen.kt` (route `bought`): the "When did we last buy...?" search, which names the
  matched entry, lists several matches and says "no record" for none, plus the Log it form (item,
  date, store, price "entered by hand", note, private). "Logged by: not recorded" for backfilled rows.
  Unreachable engine: the sentence and a Retry, never an empty log.
- **Groceries label:** the item editor shows "Last bought Sep 20 (dot) Mia" on the list named
  Groceries and keeps "Last ticked" on every other list (`rememberLastItemLabel`).
- **Untick:** every untick sent to the engine carries `?today=<local epoch day>`; a queued untick keeps
  the day it was made in its outbox payload.
- Voice guide copy in `tools/voice_guide_copy.py`; `voice_guide.py` and `VoiceGuideDataTest` scan
  `PurchaseToolbox.kt` too.

Owed on the phone: log by voice and by hand, ask when last bought, a Groceries tick then a same-day
untick (the bought entry disappears), and the whole thing with the engine unreachable.
