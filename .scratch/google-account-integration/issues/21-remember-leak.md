---
map: google-account-integration
ticket: 21
title: "Mail can still reach permanent memory through `remember`"
type: grilling
status: resolved
status-detail: "2026-08-16, remember refuses; Q5 memory audit left open"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Mail can still reach permanent memory through `remember`

## Question

Found 2026-08-16 while verifying [the two Gmail tools](15-gmail-tools.md) were built. They were.
This is the hole the verification found on the way past.

### The rule

The mail read-through rule (ticket 07): mail is **read, used, dropped**. Nothing is stored, and
nothing reaches `EpisodicTurn` or `CompanionMemory`. The map calls it "precedent for every new
sense", and `.scratch/hands-and-senses/map.md` inherits it explicitly.

### How it is enforced today, and where the enforcement stops

**The episodic path is closed properly**, and deliberately so. `GeminiLiveSession` sets
`mailToolCalledThisTurn` when a mail tool call **arrives off the socket** (`:863`) rather than after
dispatch returns, because `turnComplete` can land first. `captureEpisodicTurn` then drops the
**entire turn**, both halves (`:694`), and the KDoc states the reasoning: "the guarantee this rule
exists to give is that mail was never stored - not that something remembered to scrub it."

**The `remember` tool is not gated on that flag at all.** `remember` is declared at
`LiveToolbox.kt:964`, dispatched at `:1489`, and writes `MemoryEntry` rows directly through
`AriaBrain.kt:158-169`. A grep of `mailToolCalledThisTurn` finds six sites, **none of them in
dispatch** (`traced`).

So: Alfred reads an email, the driver says "remember that", and the mail content is written to
permanent memory. The turn it happened in is correctly dropped from `EpisodicTurn` - which means
**the write survives and the record of where it came from does not.**

### Why this is not just ticket 15 unfinished

Ticket 15's point 5 said "no durable memories from mail". Its episodic half shipped and is tested.
This is a second, independent route into the same store that the ticket did not anticipate, and it
is reachable today. Filing it separately so it is not closed by association.

Decide:

1. **What should `remember` do in a turn that touched mail?** Refuse in words; accept but strip; or
   accept and record provenance so it can be found and removed later. Refusing is the only option
   consistent with "mail was never stored" as an absolute - but it makes Alfred unable to remember
   a fact the driver just learned from an email, which may be exactly what the driver wanted.
2. **Is "the driver explicitly asked" a valid exception?** `remember` is never model-initiated - it
   fires because a person said so. The garage precedent treats an explicit confirm as sufficient
   authority for a destructive act. Does the same logic apply to a durable one?
3. **What about the other writers?** `remember` is the one found. Check `MemoryConsolidator`,
   `ReflectionEngine`, and any advisor path for a second route before deciding, or this recurs.
4. **Does the same hole exist for the other excluded tools?** `EPISODIC_EXCLUDED_TOOLS` currently
   holds `search_mail` and `read_mail` only. Whatever is decided here becomes the rule for every
   future sense that joins that set - notifications, the document vault, anything read-through.
5. **What is already in memory?** If anything was captured this way it is sitting in
   `CompanionMemory`/`MemoryEntry` now. Say whether that needs auditing or clearing, and how the
   driver would even know.

**Related, same ticket 15 verification, much smaller:** point 3 required Alfred to always say the
search query he ran. The query rides the payload (`LiveToolbox.kt:1773`) with a comment saying "so
Alfred always has it to say", but **no prompt text anywhere instructs him to say it** - grep across
`app/src` is empty and neither tool description carries the wording. The guardrail is offered to the
model, never asked for. Fix it here or fold it into whatever wording this ticket produces.

## Answer

**Closed 2026-08-16. `remember` REFUSES in a turn that touched a read-through tool.**

Decision taken by Stark under Kevin's "build everything that's not blocked" instruction, on the
grounds that the rule already on the books is absolute - "the guarantee this rule exists to give is
that mail was never stored, not that something remembered to scrub it" - and refusing is the only
implementation consistent with it.

**The wording:** "I don't keep anything from mail - that's on purpose, not a slip. Tell me the fact
yourself, in your own words, and I'll remember that."

**The cost, stated rather than hidden:** Alfred cannot remember a fact the driver just learned from
an email, even asked directly. Q2 of this ticket - whether "the driver explicitly asked" is a valid
exception, per the garage precedent - **is deliberately answered NO for now**, and reversing it is a
one-line change if it annoys Kevin in use.

**Gated on set membership, never on the two literal tool names.** `EPISODIC_EXCLUDED_TOOLS` will
grow - the hands-and-senses map makes it the precedent for every future read-through sense. Anything
that joins it inherits this protection automatically. Proven by a test that adds a fake name to the
set and asserts it also blocks.

**Q3 answered: there is no second route.** Checked in full, `traced`, not grepped:
- `MemoryConsolidator.consolidatePending` reads **only** `EpisodicTurn` rows - and `captureEpisodicTurn`
  already drops the entire turn before any row exists to consolidate from.
- `ReflectionEngine` reads **only** memories already tagged `CONSOLIDATED` - one hop further
  downstream of the same scrubbed input.
- `CompanionReset` touches these tables for deletion only.
- No other file under `app/src/main` writes `MemoryEntry` or `CompanionMemory`.

**Q5 (what is already in memory) is NOT answered** and is left open deliberately: if anything was
captured this way before today it is sitting in `CompanionMemory` now, and nobody has audited it.
That needs Kevin, since only he can recognise a memory that came from an email.

**The smaller sibling is fixed too.** `search_mail`'s description now instructs Alfred to say the
query back when he translated Kevin's words into it himself, "so a bad translation is visible rather
than a confident wrong answer". The guardrail was previously offered in the payload and never asked
for.

**Not verified on-device** - saying "remember that" right after a mail read on the phone has not
been tried.
