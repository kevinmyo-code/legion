---
map: one-home
ticket: "06"
title: "News feed sources, and what a feed is allowed to keep"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# What a feed is allowed to keep

**Kevin, 2026-09-10:** *"A news feed page > pulled from my gmail or just rss feeds."*

*"or"* is doing real work in that sentence: the two sources are governed by different rules, and one
of those rules does not exist yet.

## Gmail: already ruled, already built, already compliant

`NewsDigestCard` (`ui/MetersScreen.kt:877`) calls `SitrepBuilder.build(context, setOf(SitrepModule.NEWS))`.
Built by command-center ticket 12: a no-config Gmail query for newsletter-shaped mail, bodies folded
into one sub-agent prompt, summarized, dropped. *"No Room row, no cache file, not even the summary."*

That is CLAUDE.md §7's third-party bullet enforced at the write sites via
`LiveToolbox.EPISODIC_EXCLUDED_TOOLS`, and §7 is explicit that the guarantee is *"that it was never
stored, not that something remembered to exclude it"*. **Nothing about Gmail is reopened here.**

Note the standing debt: command-center 12 is `built` and still owes a tap on the real phone.

## RSS: nothing exists, and no rule covers it

Grep across `app/src/main/java` for `rss`, `atom`, `feed`, `FeedItem`, `NewsApi` returns **zero
hits**. There is no feed reader, no subscription table, no fetcher.

**The question is not how to parse a feed. It is whether an item may be stored.**

§7's rule is written about *"anything other people wrote TO Kevin"* - mail first, *"and anything of
that shape later"*. Two honest readings:

**The line is provenance.** §7's own carve-out for voice notes (ADR 0041, 2026-09-01) turned on
exactly this: mail arrives unasked, a recording exists because Kevin pressed a button. A feed Kevin
subscribed to is chosen, not received. On this reading a feed item may be stored, synced and looked
back at like any other record.

**The line is authorship.** A headline is still someone else's writing, published to everyone, and
nothing about subscribing makes it Kevin's. On this reading a feed is read-through like mail:
fetched, rendered, dropped.

**They give opposite answers and the difference is visible in the product**: whether the page can
show you what you read last week, whether it works offline, and whether a feed item can ever end up
in episodic memory or a sitrep.

## What the decision must state

1. **Persisted or read-through.** If persisted: which table, whether it syncs to the engine, whether
   it is excluded from episodic memory regardless, and how deletion works.
2. **Subscriptions are different from items.** A list of feed URLs Kevin typed is unambiguously his
   own data and is storable under either reading. Say so explicitly so it does not get swept into
   whatever items get.
3. **Whether RSS is in scope at all right now.** *"gmail or just rss feeds"* permits shipping the
   Gmail half alone. Ticket 07 is smaller and unblocked if the answer is "Gmail now, RSS later".
4. **Whether the feed is a tab.** Ticket 01 option B would give NEWS the second slot. That is a shell
   decision, but it should not be made accidentally by this ticket shipping a page that needs an
   address.

## The one thing that binds either way

§4's numeric gate has no purchase on prose - a headline states no total, so there is nothing to
reconcile against. That is **not** permission to treat what a feed says as fact. Nothing read from a
feed or from mail may become a ledger row, a macro, a maintenance due date or any asserted figure
without going through that aspect's own ingestion path. If the answer to (1) is "persisted", the
stored item is a copy of what someone published, never a source of record for anything numeric.

## Resolution

Kevin rules on 1-4. If (1) is "persisted", this decision changes a CLAUDE.md §7 boundary and must be
filed to `library/decisions.md` **and** applied to CLAUDE.md in the same commit, with an ADR if it
stands (the §13 test). If (1) is "read-through", §7 is unchanged and no ADR is needed.
