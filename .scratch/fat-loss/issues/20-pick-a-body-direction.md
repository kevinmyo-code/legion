---
map: fat-loss
ticket: "20"
title: "Kevin picks a Body screen direction"
type: decision
status: resolved
status-detail: "Kevin picked direction A, the single scroll of cards, 2026-11-05 (relayed by the orchestrator). Ticket 21 builds it"
blockers: ["19"]
blocked-by: ["[[19-body-screen-prototypes]]"]
tags: [ticket]
---
# Kevin picks a Body screen direction

Kevin looks at ticket 19's PNGs and picks one, or a mix. Resolving this opens ticket 21 to build.

The three directions (PNGs held outside the repo, synthetic numbers only):

| Direction | Idea | Trade-off |
|---|---|---|
| A, one scroll of cards | Closest to the Money view: four log buttons on top, then Trend (chart, band meter, milestone bar), Today (kcal and protein bars), Energy (with the proposal inside), This week (training, waist), a short More list | Everything is there; about 2.2 screens of scroll |
| B, big trend chart plus tabs | The chart leads, with a dashed milestone line and range chips; the proposal sits above Today / Energy / Week tabs; a fixed log bar | Most chart-like; detail split across tabs, and the fixed bars cost about 150 px |
| C, today first | Thick kcal and protein bars, four big log buttons and saved-meal chips first; a "Needs your answer" proposal card; small trend card; Energy as one sentence | Best for "what do I log and eat now"; the trend is small |

All three: Money's budget bar for kcal and protein (no pace marker, "estimate" in words), band
status in words, Accept and Dismiss at 48 dp, the checklist reduced to one "Today's plan" row.

What confuses on today's Body tab (read from code; the phone was locked): the weight panel is the
latest reading against the previous one with an unscaled, unsmoothed line and no rate, band or
milestone; calories are a figure and a sentence, not a bar, and protein is not on the main view;
Intake and Sleep tiles carry two buttons each; the checklist is fully open mid-screen with its
completion dates; training has no panel of its own; goals show an all-caps NOW -> TARGET line; there
is nowhere for maintenance, a proposal, waist or saved meals; each panel opens its own chart in a
different format.

## Resolution

**Kevin, 2026-11-05: direction A**, the single scroll of cards (quick log, Trend, Today, Energy,
This week, More). Ticket 21 builds it.
