---
map: generated-ui
ticket: "05"
title: "The phone shell: what home is, and what happens when voice fails"
type: grilling
status: open
status-detail: "Point 1 decided by Kevin 2026-09-01: home is the month calendar, not push-to-talk. Points 2-3 answered by the shipped modal (over the current screen; nothing persists). Points 4-5 (wake word may render; the no-key/no-network failure path) still open."
blockers: ["04"]
blocked-by: ["[[04-adr-0035-amendment]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The phone shell: what home is, and what happens when voice fails

## Question

1. **Is push-to-talk the home screen?** This is the widget-pager bet again in different clothes, and
   the evidence is one day old: Kevin field-tested the pager as home overnight on 2026-08-24 and
   ruled *"kill it, revert everything to classic"*, so `LegionRoute.TODAY` is home again.
   **The difference is real and worth naming rather than dismissing:** the pager showed a fixed
   dashboard of what someone guessed you would want, while this shows what you just asked for. That
   is why it may work where the pager did not. But it is the same category of change, it was
   rejected once on real use, and it should be field-tested the same way before it is home.
   Recommend: build it as a route, live with it for a few days, then decide - not decide first.
2. **Where does a generated answer go?** A full screen, a sheet over the current screen, or a card
   in a running transcript. A transcript is the only one that lets you look back at the answer to
   the question before last.
3. **What persists?** Recommend nothing by default. A generated view is a rendering of a query, not
   a record; keeping it invites treating it as one. Anything worth keeping should be a record in
   the ERP, not a saved screenshot of a card.
4. **Wake word plus PTT.** `WakeWordEngine` is live and Vosk-based. Decide whether wake word alone
   can raise a generated view, or whether rendering always requires a deliberate press.
5. **The failure path, which is the whole reason the classic screens stay.** No key, no network,
   socket closed, mic deaf, model wrong. Each needs a worded state and a way through by hand.
