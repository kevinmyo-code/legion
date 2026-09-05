---
map: architecture
title: "Hilt on KSP, constructor injection, a ViewModel per screen"
charted: 2026-09-05
status: open
tags: [map]
---

# Hilt on KSP, constructor injection, a ViewModel per screen

Charted 2026-09-05 from Kevin's ruling *"go with hilt, rewrite section 8"*, after the framework
question was decided "no framework" in the morning, reopened by Kevin the same afternoon (*"no
framework, manual di etc is outdated... we can re-decide anything"*), and re-argued on merit by the
everything-claude-code survey (`tmp/ecc_proposal.md`, architecture section). CLAUDE.md §8 holds the
rule; this map holds the order.

The order matters more than any single ticket. Each one leaves the build green and the app
installable; none is a rewrite. The code stays device-verified throughout.

| # | Ticket | Why here |
|---|---|---|
| 01 | Room kapt to KSP | Before Hilt, so there is one processor, not two |
| 02 | Hilt plugin, `@HiltAndroidApp`, entry points, `@EntryPoint` shim | The shim is what lets 33 `object` controllers keep compiling while they convert |
| 03 | Bind the backend interfaces | The pattern exists (`LastAspectsBackend`); make it the rule |
| 04 | Calendar, ledger, pantry: ViewModels, injected controllers | The three screens where "the list flashed empty" bites |
| 05 | detekt with a baseline; 1000-line ceiling | Kevin: "1000 lines, add detekt". Red only on new debt |
| 06 | Convert-as-touched rule and the shim's retirement trigger | The long tail, written down so it does not become "later" |
