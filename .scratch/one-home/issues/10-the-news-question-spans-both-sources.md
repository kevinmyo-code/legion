---
map: one-home
ticket: "10"
title: "Asking for the news reaches both sources, not just the mailbox"
type: build
status: built
status-detail: >
  Built 2026-09-27. NEWS renders a mail half (summarized, label NEWS) and a
  feed half (verbatim headlines, label NEWS FEEDS, capped 5 per feed); either
  half failing leaves the other intact, and ticket 07's four feed outcomes
  stay four. get_sitrep's description now names "what's the news" so the
  model reaches for it. No migration, nothing stored. 3568 tests, 0 failures.
  Owes the device run: ask for the news and see whether Hacker News appears.
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# "What's the news for today"

**Kevin, 2026-09-16:** *"newsletters > i wanna ask alfred every morning > whats the news for today
etc. and i want to be given the news. kinda like how the sitrep works"* and, asked whether he wanted
it pushed or pulled: *"just pull only. and yes widen it."*

## What already exists, verified on the A25 on 2026-09-16

- **`SitrepModule.NEWS` works and is reachable by voice today.** It is `DEFAULT_ON`, `get_sitrep`
  dispatches it, and a real tap returned a real TLDR summary at 23:47 (the check command-center
  ticket 12 had owed since 2026-08-22). `senders` is already `tldrnewsletter.com`, so the
  "only tech related" curation landed.
- **`NEWS` is Gmail ONLY.** `newsSectionLive` resolves a Gmail query and summarizes the messages.
- **RSS exists and has no voice path at all.** Ticket 07 built `news/FeedFetcher.kt`,
  `news/FeedSubscriptionController.kt`, the `feed_subscriptions` table and `ui/news/NewsScreen.kt`.
  The device carries one subscription, `https://hnrss.org/frontpage`. **Nothing in `LiveToolbox`
  reads a feed** - grep for a news tool returns nothing.

**So this is ADR 0035 inverted**: a capability with a hands path and no voice path. The ADR's
reasoning still applies in reverse - one capability, both paths, calling the same controller.

## The decision this ticket makes: one section, two halves, and the halves stay distinguishable

**Widen `SitrepModule.NEWS` rather than adding a second tool.** One question gets one answer; two
tools for "what's the news" is the flattening ticket 09 on the chief-of-staff side already rejected,
and Kevin's own framing is *"kinda like how the sitrep works"*.

**The mail half keeps its LLM summary. The feed half stays deterministic.** Three reasons:

1. `SitrepBuilder`'s own class doc says *"Three of four sections are deterministic; only
   `SitrepModule.NEWS` ever reaches an LLM"*. Keeping the feed half deterministic preserves as much
   of that as is still possible instead of widening the LLM's reach on a whim.
2. **A headline IS the content.** A newsletter body is long prose that genuinely needs reducing; a
   list of titles does not. Summarizing them manufactures a new claim out of text that was already
   short enough to read.
3. §4 rule 5's posture: a model's paraphrase of a headline is not the headline. The feed half
   reports titles verbatim, so nothing in it can be wrong in a way the source was not.

**The section must say which half is which.** A reader hearing one NEWS block must be able to tell
the summarized prose from the verbatim headlines, because one is a model's reduction and the other
is not. This is the provenance posture §4 applies to rows, applied to two halves of one sentence.

## The failure rule, and it is the point of the ticket

**One half failing must never blank the other.** If Gmail's grant has lapsed, the feeds still
report. If a feed is unreachable, the newsletter summary still arrives. Today `newsSection` renders
a single `NewsOutcome`; after this it renders both halves and each carries its own outcome.

`NewsOutcome` already distinguishes `CouldNotCheck` / `Empty` / `SummaryFailed` / `Summarized`, and
`FeedFetchResult` already distinguishes `Success` / `Empty` / `Unreachable` / `Unparseable`. **Both
sealed types exist and neither needs inventing** - the work is composing them without collapsing
either.

**An unreadable feed and a quiet feed are different sentences** (CLAUDE.md §1). A feed that 404s
says so; a feed that fetched fine and had nothing new says that instead. Ticket 07 built four
distinct RSS outcome sentences with a test that they really are four - do not let composition
flatten them back into three.

## Scope

1. Widen the NEWS module to fetch every row from `FeedSubscriptionController` alongside the mail
   query, and render both halves under one `NEWS` block.
2. Cap the feed half the way the mail half is capped (`NEWS_MESSAGE_CAP = 5`). A status report, not
   a reader. Per feed or overall is the builder's call, stated in the code comment.
3. Nothing is stored. Feed items and mail are both read-through (ticket 06's ruling and §7). There
   is no new table and no new column - if this ticket produces a migration, something has gone
   wrong.

## Explicitly NOT in scope

**Any scheduled or proactive delivery.** Kevin, this ticket: *"just pull only."* That agrees with
his own ticket 32 ruling (*"sitreps stay tap only or via voice activation only"*), which deleted
`SitrepScheduler` and `SitrepAlarmReceiver` and left `SitrepSchedule.hour`/`minute` vestigial. **Do
not revive them.** A morning briefing that arrives unasked is a different feature, reverses a
standing ruling, and would have to clear §7's compulsion test first.

Also out: changing the Gmail query, the sender list, or `NO_CONFIG_NEWSLETTER_QUERY` (pinned by
test). And feed subscription sync, which is ticket 09.

## Verification

- `compileDebugKotlin`, full suite, totals from the JUnit XML under `app/build/test-results/`.
- Pure tests over the composition, since both halves are sealed types and this is exactly the
  testable shape: mail summarized + feeds fine; **mail failed + feeds fine (the feed half still
  renders)**; mail fine + one feed unreachable (the summary still renders and the dead feed is named);
  both empty; both failed. The second and third are the ticket's whole reason for existing.
- A test that the four RSS outcome sentences are still four after composition.
- `python tools/voice_guide.py` exits zero. If the tool's user-facing copy still says newsletters
  only, it is now wrong - update `tools/voice_guide_copy.py`.
- **On the phone**: ask for a sitrep and report what actually came back, including whether Hacker
  News appeared. Not claimable from a unit test (L11). The 2026-09-11 `read_calendar` bug was a tool
  that worked perfectly and was never reached.
