---
map: command-center
ticket: "12"
title: "Newsletters summarize themselves, no setup required"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Newsletters summarize themselves, no setup required

Kevin, from the phone, 2026-08-22: *"newsletters > build. take from my gmail > summarize."*

## Why it looked unbuilt

The digest already exists (`SitrepBuilder`'s NEWS section, the Home `NewsDigestCard`) but it only
reads senders Kevin has CONFIGURED (`SitrepSettings.newsletterSenders`), and he has configured
none - so every tap honestly answers "not configured". Honest, and useless. The ruling makes
Gmail itself the source.

## Build

1. **A no-config default query.** When no senders are configured, find newsletter-shaped mail in
   the last day directly: Gmail search operators that identify newsletters - `list:` mail,
   `category:updates`/`category:promotions`, "unsubscribe" - tuned so a personal mail can
   never be swept in by accident. The query is deterministic and its text lives in one place with
   a test pinning it. **A configured sender list remains an override, not a casualty.**
2. **Summarize read-through**, exactly as the sitrep news section already does: bodies fetched,
   folded into ONE sub-agent prompt, summarized, dropped. No Room row, no cache file, not even the
   summary. CLAUDE.md §7's third-party bullet in full.
3. **Both consumers get it**: the Home `NewsDigestCard` (tap-to-fetch, staleness shown, unchanged
   posture) and the sitrep's NEWS module - one shared function, not two queries that drift.
4. **Empty and unreadable stay different sentences**: "no newsletters in the last day" / "could not
   reach Gmail" / "found N, summary failed" are three different answers.
5. **Cost stays tied to a real ask**: cap the messages fetched (the existing NEWS_MESSAGE_CAP
   precedent), never auto-poll, the tap is the demand.

## Verification

- Suite green both ways, one run fresh. Query-shape test; a test that the no-config path and the
  configured path share the summarize function; the three failure sentences distinct by test.
- Never touch `GmailAuth.authorize` from a unit test (hangs Robolectric - recorded hazard).
- On the phone: tap the card with zero configuration and get a real summary of a real newsletter.
