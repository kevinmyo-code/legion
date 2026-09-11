---
map: one-home
ticket: "09"
title: "Decide whether feed subscriptions sync, and build the leg if they do"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Do feed subscriptions sync?

Opened 2026-09-10 at build time, because [[06-news-sources-and-what-may-be-kept]] resolved that a
subscription is *"persisted, synced like any other record"* and [[07-the-news-surface]] built it
persisted and **not** synced. The gap is recorded in 06's own amendment; this is the ticket that
stops it being a sentence nobody rechecks.

**CLAUDE.md §12 is why this exists as a ticket rather than a note.** Resolving a decision makes it
vanish from the wiki, so a fully-decided, entirely unbuilt thing looks exactly like finished work -
which happened here in miniature within one session.

## The state today

`data/local/FeedSubscription.kt`: `id`, `url`, `title`, `addedAtMs`. A unique index on `url`. No
`syncId`, no `serverId`, no `deleted` tombstone column, no Django model, no endpoint. Local to the
device that typed it.

## The question

**Is a feed list device-local config, or is it household content?**

**Device-local** is what the build assumed, and the precedent is real: `SitrepModuleSetting` and
`SitrepSchedule` are Kevin's own settings and do not sync. On this reading the phone and the web app
each keep their own list and nothing is owed.

**Household content** is the other reading, and three things argue for it. The web client is where
reading is comfortable and it will want the list. A second household member cannot see his feeds at
all today. And "the list of what to read is his" (06's own words) is an argument about OWNERSHIP,
which is what tenancy scopes - not an argument that it should live on one handset.

## If it syncs, what it costs

Not a bolt-on, and this is the part worth knowing before choosing:

1. **A Django model, endpoint and migration first** (ADR 0044: a write path is a Django endpoint
   first and a Kotlin caller second, never the reverse).
2. **`household_id`, `TENANT_TABLES`, and the tenancy leak test** (ADR 0045, and the feature-add
   checklist's own line). A new table that skips the choke point is exactly what that test exists to
   catch.
3. **The synced-row shape the other aspects already use** - `syncId`, `serverId`, a `deleted`
   tombstone rather than a hard delete, and the since-feed watermark behaviour `test_synced_contract`
   pins. A Room migration to add those columns, additive, with a migration test.
4. **A duplicate-URL rule that survives two devices.** Today a unique index on `url` is enough
   because there is one writer. With two, the same feed added on both is a conflict the server has
   to answer for.

## Not in scope either way

The ITEMS. `06`'s point 1 is untouched: feed items and mail are read-through, never stored. There is
no items table and this ticket does not propose one.

## Resolution

Kevin picks device-local (in which case 06's wording is corrected and this closes with no code) or
household content (in which case this leaves a build ticket behind, per §12).
