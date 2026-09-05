---
map: django-engine
ticket: "08"
title: "The web app: her limb, installable on an iPhone, the desk for both"
type: build
status: open
blockers: ["04"]
blocked-by: ["[[04-domain-api-and-changes-feed]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# The web app

The second adult has an iPhone and no app. This is her hands path (ADR 0035) and the desk surface
ADR 0040 named. It is server-rendered Django, not a JavaScript app, so it is one codebase with the
API it sits on and needs no build step.

## Stack

Django templates, HTMX for partial updates, one `static/app.css`. `manifest.webmanifest` and a
minimal service worker (cache the shell, never cache API responses) so Safari's "Add to Home Screen"
gives it an icon and a full-screen window. Session auth for the browser (Django's own), the same
`User` rows as the API. No voice in this ticket.

## Screens, in build order

| Screen | Reads | Writes | Why first |
|---|---|---|---|
| Today | `events` for today and the next seven days, checklist ticks | tick, skip, add an event | The thing she will open most |
| Lists | `item_lists`, `list_items` (live ones are `events`; see `LastAspectsBackend` doc) | add, check off | Groceries |
| Pantry | receipts, line items, unverified said in words | photo upload to ticket 05, which posts to the gate | The receipt she photographs |
| Ledger | transactions, categories, budget targets | category and rule edits | Read-mostly |
| Body | weight, sleep, meals | log entries | Read-mostly |
| Fleet | vehicles, service history, maintenance due | service entry | Read-mostly |

Every screen calls the same serializers and services ticket 04 built. A view that reaches past
them into the ORM to write is the two-implementations drift ADR 0035 forbids.

## Trust disclosures

MEMORY.md: an estimate or an `UNRECONCILED` line is never collapsed behind a help row. Pantry and
ledger screens print the word `unverified` beside the figure, in the same font, every time.

## Verification

- [ ] Installed to an iPhone home screen; opens full-screen; login persists across launches.
- [ ] A tick on the web app appears on the phone at its next poll; a voice-added event appears on
      the web app on refresh.
- [ ] Lighthouse PWA audit passes installability.
- [ ] Ledger screen with one `UNRECONCILED` row shows the word, not a colour.
