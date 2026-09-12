---
map: web-surface
title: "What the web app is FOR: the horizon is wrong, the desktop is empty, and nine deadlines look like one"
charted: 2026-09-12
charted-by: "Kevin + Opus"
effort: "`.scratch/web-surface/`"
tickets: 7
open: 7
status: open
tags: [map]
---

# What the web app is for

**Kevin, 2026-09-12, after Today finally rendered real data:** *"lets do a proper frontend design.
what is the page trying to show, how can we improve it."*

Charted against the live engine, not from memory. Every number below was read from
`/api/changes` in Kevin's own browser on 2026-09-12.

## What is actually there

| | |
|---|---|
| Live events | 312, of which **92 are in the past** |
| Checklists | 9, of which 5 archived. `bio` is DAILY with 5 items; `Groceries` has 15 and no schedule |
| Ticks today | 1 |
| Desktop viewport | 1707 x 898, content rendered into `max-w-lg` (~512px) |

The next fourteen days, by day offset from today (Sat 12 Sep):

```
today  +1   +2   +3   +4   +5   +6   +7   +8   +9  +10  +11  +12  +13
 1e    9t    -   3e   1t   3e   2t    -   4t    -  3e   2t   3e   1t
```

`e` = events (classes, birthdays), `t` = tasks (coursework deadlines). **The shape is a rhythm**:
lecture days carry three events, deadlines land in clusters, and tomorrow carries nine at once.

## The four things wrong, in order of how badly they mislead

### 1. The horizon is wrong, and today it is wrong by luck

Today renders **today and tomorrow only**. That comes from `docs/design/today.md`'s Cozi research,
which found Cozi's flagship screen caps at two days - good research, **wrong user**. Cozi is a family
calendar. Kevin's data is coursework with deadline cliffs.

Right now the screen looks fine because tomorrow IS the cliff. **On Monday those same nine deadlines
will have passed and the next four are six days out - invisible.** The screen will be honest and
useless at the same time.

The fix is not "render more rows". Nine identical `11:59 PM` rows is not information either. It is a
**horizon with shape**: what is due in the next seven days, grouped so a cliff reads as a cliff.

### 2. The desktop shows nothing and wastes everything

`max-w-lg` inside a 1707px window. About 70% of the screen is empty, and the left rail holds two
items. This is the surface Kevin called **his workbench** - review and edit records, ledger
ingestion - and it currently cannot do any of that.

**The phone and the desktop are not one page reflowed.** Same data, same account, different job:
Mia glances and ticks; Kevin needs density, history and write access. Designing one responsive column
for both is what produced an empty desktop.

### 3. Nine deadlines look like nine unrelated rows

Every one says `11:59 PM`, every one is a separate row, nothing says "nine", nothing groups by course,
nothing distinguishes the quiz you have not started from the one already submitted. The data carries
`done` and the course name is in the title. **The load is in the data and not on the screen.**

### 4. Ninety-two past events have no surface at all

One-today ticket 09's own words: *"i can look back and see what i did."* There is no look-back
anywhere on the web. 92 past events and every historical tick are unreachable.

## What the page is trying to show, stated plainly

Because the answer differs by surface, and that is the whole finding:

**Phone / Mia** - one question: *what do I need to do or know today?* Ticks, today's events, nothing
else. She should never see a table, a provenance tag, or an ingestion control.

**Desktop / Kevin** - three questions at once: *what is coming at me, what needs a decision, and
where do I go to fix a record?* That is a dashboard plus a way in, not a single column.

## What this map does NOT reopen

- **The visual language.** Family-first, warm, light - settled 2026-09-12 and drawn in
  `docs/design/canvas/`. This map is about WHAT is on the page and for whom; that one is about how
  it looks.
- **`web-and-households` 05 and 06.** 05 built the first slice and is `built`; 06 owns the aspect
  screens. This map supersedes neither - it re-scopes what 06 should contain now that the shape of
  the real data is known, and ticket 07 below hands 06 its brief.
- **Server report endpoints** (`web-and-households` 11). Today and Lists read the whole table and
  filter client-side; at 312 events that is correct and cheap. Ticket 02 below names the trigger at
  which it stops being correct, rather than pre-emptively building an endpoint.

## The rule this map must not break

**Empty, unreachable and stale are three different sentences, and only two exist today.** The
`isError` branch is written. **Stale is not**: TanStack Query serves the last good response when a
refetch fails, so the screen can show yesterday's day with no indication. Ticket 06 owns that, and
it is not cosmetic - a day view that is quietly a day old is the same class of error as the
`/api/changes` bug that made the calendar read empty for weeks.

## The tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | decision | The horizon: what Today shows beyond tomorrow, and how a cliff reads as a cliff | - |
| 02 | build | Today, rebuilt: the day, then the horizon with shape. Grouped by day, load stated in words | 01 |
| 03 | decision | Desktop is not the phone reflowed: what the workbench shows that the PWA never does | - |
| 04 | build | The desktop shell: real width, a dashboard, and the rail that reaches the records | 03 |
| 05 | build | Look-back: past events and tick history, the half of one-today 09 the web never got | - |
| 06 | build | Stale is a third sentence. Say when the data was last read, and when a refetch failed | - |
| 07 | task | Re-brief `web-and-households` 06 with what the real data turned out to look like | 01, 03 |

**Order.** 01 and 03 are Kevin's calls and can be taken together - they are the two halves of the
same question. 06 is independent and small; it can go first and should, because every other ticket
renders data whose freshness is currently unstated. 05 is independent. 07 last.

**Execution.** All in `server/frontend/`. Nothing here needs a server change except where ticket 02
hits the trigger it names.
