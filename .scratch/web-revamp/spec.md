# Web revamp: one household client, a workbench and a family view

Spec, 2026-10-03. Interviewed: Kevin. Drafted: Opus. Map: `.scratch/web-revamp/map.md`.
Branch: `feat/web-revamp-spec` off `origin/dev` `ec11c7a`.

Every fact about the engine below was traced in source on `origin/dev` on 2026-10-03, file and line
in the scout reports summarised under "Ground truth". Nothing was read from live data. Where a claim
is reasoned rather than traced, it says so.

---

## Problem Statement

The household web app exists and nobody can really live in it.

- **For Mia** it is a login screen, a Today page and a Lists page in a stock grey theme that has
  never been looked at on a phone. She cannot add an event, cannot see what the household is
  spending, gets no nudge when Kevin adds to the grocery list, and sees Kevin's coursework deadlines
  mixed into her day because nothing in the engine can be private.
- **For Kevin** the desktop is the phone's two screens stretched wide. The engine holds ledger,
  pantry, body, fleet, places and voice notes, and the web can show none of them. Categorising a
  transaction, editing a budget, fixing a repeating event: all of it means picking up the phone.
- **The design language drawn on 2026-09-12 was never applied**, and the PWA manifest still wears
  the phone's black-and-orange mission control, the opposite of what was decided.
- **There is no way to invite anyone through the web.** The accounts backend is built with zero UI;
  Mia's own password was set with `manage.py changepassword`.

## Solution

One web client, two surfaces, split by viewport.

- **Narrow (the PWA on Mia's iPhone): the family view.** What is on today, what needs doing,
  the shared lists, a calendar she can add to, and what the two BofA accounts have spent this month.
  Push notifications when a shared list changes, before a shared event, and once in the morning for
  shared tasks due. No tables, no ingestion, no provenance tags beyond the word "unverified".
- **Wide (Kevin's desktop browser): the workbench.** A dashboard of what is coming and what needs a
  decision, and one screen per aspect for reviewing and editing records: Money first, then
  Calendar, then Pantry and Body, then Fleet, Places and Notes.
- **Private rows.** The engine learns that a row can belong to one member. Canvas coursework and
  class schedules are Kevin's alone; anything either of them makes by hand is shared by default and
  carries a pink "Shared" marker. Privacy is enforced by the engine, not hidden by the client.
- **A new look, picked from three clickable prototypes**, with light and dark following the system.

---

## Ground truth (what exists on `origin/dev`, 2026-10-03)

| Area | Fact |
|---|---|
| Web routes | `/login`, `/` (Today), `/lists`. Shell: rail at `md`, bottom bar below. `server/frontend/src/` is 36 files |
| Web stack | React 19, Vite 8, TanStack Router + Query, Tailwind 4, shadcn/radix, openapi-fetch from `server/openapi.yaml`, vite-plugin-pwa with `/api` NetworkOnly. Ruled in web-and-households 04 |
| Theme | Stock shadcn neutral, Geist only. Manifest `#000000` / `#FF5330` |
| Events | `events` table: `kind` reminder/event/task, plain repeat fields (`repeat_kind` DAILY/WEEKLY/MONTHLY_ON_DATE/YEARLY, `repeat_every`, `repeat_days_of_week`, `repeat_day`, `repeat_month`, `repeat_end_*`). **No owner column, no reminder lead time.** `event_skips` exists with **no endpoint** |
| Canvas | Server poller, `canvas_poll` every 30 min via `deploy/crontab`, writes through SQL `public.upsert_canvas_task`. Rows: `kind='task'`, `origin_guid='canvas:<aid>'`, `structured_meta.canvas_*` |
| Class schedule | No table. Google-imported `events` with a `structured_meta` `LEGION::v1` block carrying `course` (reasoned from phone code, not live data) |
| Checklists | `checklists` / `checklist_items` / `checklist_ticks`. **No owner, pinned or icon column.** Tick is optimistic on web, delete is not (decisions 2026-09-16) |
| Tenancy | `household/tenancy.py` `scoped()` / `household_of()`; `TENANT_TABLES`; leak test `tests/test_tenancy.py`. **No test that two members of one household see different rows** |
| Ledger | `ledger_transactions` immutable (`forbid_mutation_of_facts`), GET only, `verification_note` "Unverified..." on UNRECONCILED rows. Categories set through `PUT/DELETE /api/ledger/transaction_categories/<txn_id>/` (person outranks rule). `budget_targets`, `categories` (`excluded_from_spend`), `category_rules` CRUD |
| Ledger source | `connect_session.py bofa` pulls BofA activity CSVs daily to Drive; `drive_statements` watcher every 6 h: current window provisional (rule 7), closed period gated. Has run against the real BofA site (Kevin, 2026-10-03; backend-etl 09's frontmatter still says owed) |
| Spend | **No server spend figure.** The phone computes it in `LedgerBudget.operatingExpenses` with transfer pairing, excluded categories and a Housing early-charge month shift |
| Account identity | Key is `account_last4`. `account_nickname` holds a label for server-ingested rows and the phone's raw account id for phone-uploaded rows, so keying on nickname splits one card |
| Users | `User.first_name` holds the display name. `GET /api/households/me` lists members with `name` |
| Jobs | `deploy/crontab` is the one schedule, run as Cloud Run Jobs via Cloud Scheduler. Cloud Run `--min-instances 0` |
| Push | Nothing. No VAPID, no pywebpush, no service-worker push code |
| Phone JSON | Every Django backend on the phone uses `ignoreUnknownKeys = true`, so new wire fields are safe |
| ADRs | Highest on dev 0051. 0048 is taken on an unmerged branch. **Next free: 0052** |

---

## User Stories

### Mia, on the PWA

1. As Mia, I want to install LEGION to my iPhone home screen with a walkthrough, so that it opens like an app and can send me notifications.
2. As Mia, I want to sign up from an invite link Kevin sends me, so that I never need him to set a password from a terminal.
3. As Mia, I want Home to show today's date and what is on today, so that I know my day in one look.
4. As Mia, I want Home to show what needs doing today with a checkbox on each, so that I can tick things off without opening anything.
5. As Mia, I want overdue shared tasks to sit in their own short section above today's, so that nothing slips silently.
6. As Mia, I want the lists I care about (Groceries) pinned on Home with their first three open items and a count of the rest, so that I can see the list without opening it.
7. As Mia, I want to pin and unpin lists on my own Home, so that my Home is mine without changing Kevin's.
8. As Mia, I want Home to show what each of the two BofA accounts has spent this month, so that I know where we stand.
9. As Mia, I want that spend figure to say "unverified" in words when it includes transactions the bank has not closed yet, so that I never mistake a provisional number for a final one.
10. As Mia, I want the spend card to say when the numbers were last updated, in words, so that I know how fresh they are.
11. As Mia, I want the spend card to say plainly when it could not reach the engine, so that "could not load" never looks like "spent nothing".
12. As Mia, I want to open any shared list and tick, untick, add and remove items, so that we can shop from one list.
13. As Mia, I want ticked items to drop into a "Ticked" section instead of vanishing, so that I can undo a mis-tap.
14. As Mia, I want to see when Kevin has ticked or added something, within about half a minute and without refreshing, so that we do not both buy eggs.
15. As Mia, I want to create a new list, so that we can start a packing list or a party list.
16. As Mia, I want to see a calendar of the month with how busy each day is, and an agenda for the day I tap, so that I can plan ahead.
17. As Mia, I want to add an event with a title, date, time or all-day, location and notes, so that the household calendar has it.
18. As Mia, I want to make an event repeat daily, weekly on chosen days, monthly or yearly, with an end, so that I enter a recurring thing once.
19. As Mia, I want editing or deleting a repeating event to ask "Just this one" or "All of them", so that cancelling one swim lesson does not cancel the term.
20. As Mia, I want to set a reminder lead time on an event, so that my phone tells me before it starts.
21. As Mia, I want new events to be shared by default with a clear "Shared" marker, so that Kevin sees what I add.
22. As Mia, I want to mark an event or list "Only me", so that my private things stay mine.
23. As Mia, I want shared things to carry a pink marker and the word "Shared" or a two-person glyph, so that I can tell at a glance what Kevin also sees.
24. As Mia, I never want to see Kevin's coursework deadlines or class schedule, so that my day is about the household.
25. As Mia, I want a push when Kevin adds to a shared list, batched into one message, so that I know without being pinged per item.
26. As Mia, I want a push before a shared event, at the lead time set on it, so that I am not late.
27. As Mia, I want one morning push listing shared tasks due today, so that the day starts with what matters.
28. As Mia, I want every push to have a way to turn that kind off in one tap, so that I am never nagged.
29. As Mia, I want dark mode that follows my phone, with a manual override, so that it is comfortable at night.
30. As Mia, I want every tappable thing to be at least 44 px, so that I do not mis-tap.
31. As Mia, I want any desktop-only page I reach by a link to tell me it is made for a bigger screen and take me home, so that I never land in a table.
32. As Mia, I want a failed write to say in words what did not happen, so that I never think a tick saved when it did not.

### Kevin, at the desktop

33. As Kevin, I want the desktop to use the full width with a left rail listing every area, so that the screen is a workbench, not a stretched phone.
34. As Kevin, I want a dashboard with today and the next seven days, so that I see what is coming at me.
35. As Kevin, I want the dashboard to list what needs a decision (uncategorised transactions, quarantined files, overdue tasks), each linking to where I fix it, so that I clear the queue.
36. As Kevin, I want the dashboard to show this month's spend per account with a weekly bar, so that money is in view.
37. As Kevin, I want my Canvas tasks and class schedule private to me and shown with a lock and "Only you", so that I know Mia does not see them.
38. As Kevin, I want existing Canvas rows and class blocks made private in one backfill, so that I do not toggle 145 rows by hand.
39. As Kevin, I want to toggle any event or list I can see between shared and private, so that I can fix a wrong default.
40. As Kevin, I want a Money screen listing every transaction in a dense table with date, description, account, amount and category, so that I review the ledger in one place.
41. As Kevin, I want to filter that table by account, month, category, "needs a category" and "unverified", so that I can work one slice at a time.
42. As Kevin, I want to set or change a transaction's category inline, so that categorising is one click and a choice.
43. As Kevin, I want to clear my category choice and see what the rule or stored category was, so that I can undo an override.
44. As Kevin, I want each row to say where its category came from (you, a rule, the bank file), so that I trust what I see.
45. As Kevin, I want unverified rows to say "unverified" in words in a status column, and every total containing one to say so, so that provisional money never reads as final.
46. As Kevin, I want to see this month's spend per category against its budget target as a meter, so that I see overspend.
47. As Kevin, I want to create, edit and end budget targets, so that budgets live in one place.
48. As Kevin, I want to manage categories, including marking one "not spending" like Transfers, so that the spend figure is right.
49. As Kevin, I want to manage category rules (text contains X gets category Y) and see how many rows each would match, so that categorising is mostly automatic.
50. As Kevin, I want to see every ingested file with its state and the quarantine reason in words, so that I know when the daily pull failed.
51. As Kevin, I want the web spend figure to equal the phone's exactly, so that two screens never disagree about money.
52. As Kevin, I want a Calendar screen with week and month views, so that I can plan the term.
53. As Kevin, I want to create and edit events on the desktop with the same sheet as the phone (repeats, this one or all, reminder, privacy), so that both paths behave the same.
54. As Kevin, I want Canvas tasks to show what Canvas says (submitted, missing, late, grade), so that I know what is actually done.
55. As Kevin, I want to mark a task done or not done from the calendar, so that I do not need the phone.
56. As Kevin, I want a Lists screen on the desktop with every list side by side, so that I can manage them at once.
57. As Kevin, I want a Pantry screen with grocery staples (add, edit, remove) and receipts with their line items, so that I can review purchases.
58. As Kevin, I want receipt macros labelled "estimate" and any unaccounted amount shown as "unaccounted", never folded into tax, so that the pantry tells the truth.
59. As Kevin, I want a Body screen with weight, sleep, meals against targets and workout plans, editable, so that I can log and review from the desk.
60. As Kevin, I want a Fleet screen with vehicles, service history, maintenance schedules and recent drives, so that car upkeep is reviewable on a big screen.
61. As Kevin, I want a Places screen listing tagged places, editable, so that I can fix a label or radius.
62. As Kevin, I want a Notes screen listing voice notes with transcript and summary, saying plainly that audio is not available on the web, so that I can read a meeting back.
63. As Kevin, I want a household settings page to rename the household, see members, create and revoke invite links and remove a member, so that I administer it without a terminal.
64. As Kevin, I want a devices page listing every signed-in phone and token with a revoke button, so that a lost phone can be cut off.
65. As Kevin, I want an account page to change my name and password, so that I control my own login.
66. As Kevin, I want a notifications page to subscribe this browser to push and choose which kinds, so that I can opt in on my own devices too.
67. As Kevin, I want the web to keep refreshing while it is open, so that a row the daily pull added shows up without a reload.

### Both

68. As either of us, I want a write that the engine refused to say what did not happen, in words, so that §7's outcome-verb rule holds on the web too.
69. As either of us, I want empty, could-not-reach and stale to be three different sentences on every screen, so that the app never lies by omission.
70. As either of us, I want no streaks, scores or completion percentages anywhere, so that the app serves us, not engagement.

---

## Implementation Decisions

### D1. Surfaces split by viewport, rendered not hidden

- One hook, `useSurface(): 'family' | 'workbench'`, from `matchMedia('(min-width: 1024px)')`.
  Below 1024 px is `family`; 1024 px and up is `workbench`. It re-evaluates on resize.
- **The two surfaces render different component trees.** A workbench-only affordance is absent from
  the DOM at family width, never `display:none` (web-surface 04's rule). Tests assert absence.
- ADR 0045 allows no roles, so the split is viewport only. Mia on a laptop gets the workbench; Kevin
  on a narrow window gets the family view. Both are accepted, not bugs.
- **Family shell:** top bar (household name, theme toggle), bottom tab bar with four tabs: Home `/`,
  Lists `/lists`, Calendar `/calendar`, Settings `/settings`. Safe-area insets respected
  (`env(safe-area-inset-bottom)`). Tab targets at least 44 px tall.
- **Workbench shell:** left rail 232 px: Home `/`, Calendar `/calendar`, Lists `/lists`, Money
  `/money`, Pantry `/pantry`, Body `/body`, Fleet `/fleet`, Places `/places`, Notes `/notes`, a
  divider, Settings `/settings`. **A rail item appears only once its screen is built** (web-surface 04:
  "no item that opens an empty page"). Content max width 1440 px.
- **Workbench-only routes at family width** (`/money`, `/pantry`, `/body`, `/fleet`, `/places`,
  `/notes`) render one card: "This page is made for a bigger screen." and a button "Go to Home".
  Except: Mia's spend glance lives on Home, not on `/money`.
- `/` renders `FamilyHome` or `WorkbenchHome` by surface. `/calendar` and `/lists` render a family
  or workbench variant by surface; both variants call the same query and mutation hooks.

### D2. Design language: picked from three prototypes

- Three clickable directions live at `.scratch/web-revamp/research/prototypes/`: A family warm (the
  2026-09-12 drawing, rendered), B calm Apple, C soft Material light. Same five artboards each.
  **Kevin picked C, soft Material light (2026-10-03). ADR 0053 holds the tokens, shape and type;
  the font is Roboto Flex via `@fontsource-variable/roboto-flex`.**
- These hold on top of ADR 0053:
  - **Tokens live in `server/frontend/src/index.css`** as CSS variables under `:root` and
    `.dark`, mapped into Tailwind through the existing `@theme inline` block. Components never use a
    raw colour.
  - **Semantic tokens added beyond shadcn's set:** `--shared` / `--shared-foreground` (pink, used
    only for the shared marker), `--done` (ticked), `--unverified-bg` / `--unverified-fg`,
    `--estimate-fg`. No other component may reuse `--shared` for decoration.
  - **Light and dark.** Default follows `prefers-color-scheme`. A toggle in the top bar (family) and
    rail footer (workbench) cycles System, Light, Dark, stored in `localStorage` key
    `legion.theme.v1` inside try/catch; unreadable storage means System. The `dark` class goes on
    `<html>` before first paint via an inline script in `index.html`.
  - **Manifest** `theme_color` and `background_color` become the chosen direction's light ground;
    `index.html` gets two `theme-color` metas with `media` for light and dark. `scripts/make_icons.py`
    stops reading the phone's `Color.kt` and draws from the chosen palette.
  - Numbers use tabular figures. Money is formatted from `Long` cents with `Intl.NumberFormat`, never
    via floating-point arithmetic on totals (sum cents as integers, divide only to display).
- ADR 0053 also records the two-surfaces-by-viewport rule, closing the gap the scout found: that
  ruling (2026-09-12) has no ADR and no `decisions.md` entry.

### D3. Private rows on the engine (ADR 0052, amends ADR 0045)

- **Ruling (Kevin, 2026-10-03):** *"canvas and my class schedules should be only mine"*, chosen
  server-enforced. ADR 0045's "a member sees everything in their household" becomes "a member sees
  everything shared in their household, and their own private rows". Tenancy stays by household; a
  private row is still in the household and still dies with it.
- **Schema (Django migrations, additive, nullable):**
  - `events.owner_user_id` null FK to `household.User`, ON DELETE RESTRICT (a user owning private
    rows cannot be hard-deleted; SET NULL would make them shared). Null means shared.
  - `checklists.owner_user_id`, same.
  - `events.created_by_id`, `checklists.created_by_id`, `checklist_items.created_by_id`: nullable
    FK, ON DELETE SET NULL (attribution only), set server-side from `request.user` on create, never
    accepted from the body, never on the wire. Used for push attribution and for D3's
    who-may-make-private rule.
  - Items, ticks and event skips inherit their parent's visibility. No column on them.
  - When a member is removed from the household, their private rows are deleted (tombstoned) in the
    same transaction. A shared row they created stays shared.
- **Wire:** events and checklists gain `visibility: "shared" | "private"`, read/write, default
  `"shared"` on create. No user id ever goes on the wire (the tenancy test already forbids
  `household_id`; the new test forbids `owner_user_id` and `created_by_id`).
- **Who may change it:** the owner may make a private row shared. A shared row may be made private
  by its creator, or by any member if `created_by` is null (legacy rows). Otherwise 403 with a
  sentence: "Only the person who added this can make it private."
- **Enforcement at the choke point:** new `visible(model, request)` in `household/tenancy.py`:
  `scoped(model, request).filter(Q(owner_user__isnull=True) | Q(owner_user=request.user))`, with the
  item/tick/skip variants joining through the parent. Every read and write path for these five tables
  switches from `scoped` to `visible`: `api/events.py`, `checklists/views.py`, `api/changes.py`,
  `engine_mcp/tools.py`, and the routed views `engine_mcp/dispatch.py` reaches. Another member's
  private row is a 404 on detail, PATCH and DELETE, the same shape as a cross-household row.
- **Turning a row private must reach replicas that already hold it.** A client that saw the row while
  shared still has it. So `api/events.py` list, `checklists` list and `api/changes.py` emit, for a
  row private to someone else with `updated_at >= since`, a **redacted tombstone**: `{id,
  deleted_at: <updated_at>, updated_at, redacted: true}` and no other field. The phone and the web
  already drop a row with `deleted_at` set. It reveals that an id changed and when, nothing else.
- **Canvas:** `upsert_canvas_task` gains a `p_owner_user uuid` parameter; `ingest/canvas.py` passes
  the user who stored the household's Canvas credential. If `source_credentials` has no user column,
  the migration adds `user_id` null and backfills it to the household's single `owner` member.
  Canvas rows created from then on are private to that user.
- **Backfill:** `manage.py make_private --household <id> --user-email <email> (--origin-prefix
  canvas: | --structured-meta-key course) [--dry-run]`. Idempotent; prints the count it would change
  and changed. Kevin runs it twice on live: once for `canvas:`, once for `course`.
- **What the phone owes:** nothing to stay correct (it ignores the new fields and honours the
  tombstone). To let Kevin set privacy from the phone, the Android agent adds the toggle later; see
  ticket "Phone follow-ups for the Android agent".

### D4. Repeat exceptions on the engine

- New routes in `api/urls.py`: `GET /api/events/<id>/skips`, `POST /api/events/<id>/skips` body
  `{skip_date: "YYYY-MM-DD"}` (idempotent on the unique pair, 200 if it exists, 201 if new),
  `DELETE /api/events/<id>/skips/<YYYY-MM-DD>` (204, idempotent). Read through `visible()` on the
  parent event.
- `event_skips` gets `updated_at` and `deleted_at` (additive) so it can travel the changes feed;
  DELETE tombstones. `/api/changes?aspects=events` returns `event_skips` beside `events`.
- **"Just this one" on edit:** POST a skip for that date, then POST a new one-off event copying the
  series' fields with the edits applied, repeat fields null, `origin_guid =
  "<series id>:<YYYY-MM-DD>"` so a retry does not duplicate. **"Just this one" on delete:** POST the
  skip. **"All of them":** PATCH or DELETE the series. No "this and following" (not asked for).
- **Recurrence expansion is one algorithm with one set of test vectors.** It lives in the web as
  `src/lib/recurrence.ts` (today's logic in `lib/day.ts` and `lib/horizon.ts` moves there) and on the
  server as `server/api/recurrence.py` (D7 needs it for reminders). Both read
  `server/tests/fixtures/recurrence_vectors.json` (series + skips + window → expected local dates);
  vitest and pytest each fail on any vector the other passes. The phone's expansion is the reference
  the vectors are first drawn from.

### D5. Spend is computed once, on the engine

- **CLAUDE.md §7 checklist: "a business rule lives in Django once."** Porting the phone's
  `LedgerBudget` into TypeScript would be a third implementation. So the engine gets it.
- New `GET /api/ledger/spend?month=YYYY-MM` (default: current month in the caller's
  `tz` query param, IANA name from the browser; falls back to UTC). Implemented in
  `server/api/spend.py` as a port of `LedgerBudget.operatingExpenses`, `LedgerTransfers.analyzeTransfers`
  (5-day pairing window, own-account reference, keyword fallback) and `BudgetMonth` (Housing outflow
  in a month's last 3 days counts next month), reading categories' `excluded_from_spend` and the
  effective category (person > rule > stored).
- Response:

  ```json
  {
    "month": "2026-10",
    "currency": "USD",
    "accounts": [
      {"account_last4": "7823", "label": "BofA card", "spend_cents": 64255,
       "unverified": true, "unverified_cents": 21000, "latest_row_at": "2026-10-03T09:12:00Z"}
    ],
    "categories": [
      {"category": "Groceries", "spend_cents": 38110, "target_cents": 50000, "unverified": true}
    ],
    "uncategorised_cents": 4120,
    "excluded": {"not_spending_cents": 120000, "own_account_moves_cents": 50000,
                 "early_charges_moved_cents": 180000},
    "complete": true
  }
  ```

- Accounts key on `account_last4` (scout: nickname splits one card). `label` is the most recent
  server-ingested `account_nickname` for that last4, falling back to "Card ending 7823".
- `unverified` is true when any contributing row is `UNRECONCILED`. The client renders the word.
- `complete` is true only when every account active in the month has gated statements covering
  every day of it, so the current month (provisional activity) is never complete (accepted 2026-10-03).
- **Parity is a test, not a hope.** `server/tests/test_spend_parity.py` replays the phone's
  `LedgerBudget` / `LedgerTransfers` / `BudgetMonth` unit-test cases as fixtures and must produce the
  same cents. The phone switching to this endpoint is an Android follow-up; until then both compute
  and the parity test is what keeps them equal.
- web-and-households ticket 11 blocked report endpoints on RLS (02b). This endpoint reads through
  `scoped()` exactly like every existing ledger route, so it is **not** blocked on 02b. That overrides
  ticket 11 for this one endpoint; flagged in the map for Kevin.

### D6. Family Home

- Sections, in order, each with its own empty, unreachable and stale sentence:
  1. Greeting line with the date ("Saturday, 3 October"). No assistant name.
  2. **On today:** shared events plus the viewer's own private ones, time-ordered, all-day first.
  3. **Still not done:** overdue tasks, max 3 rows plus "and N more" linking to `/calendar`.
  4. **To do today:** due tasks and due scheduled checklist items, tickable (existing optimistic tick).
  5. **Pinned lists:** up to 3 cards, each the first 3 open items plus "and N more". Pins are
     per-device in `localStorage` key `legion.pins.v1` (a per-viewer convenience; Mia's pins never
     change Kevin's). With nothing pinned: the unscheduled lists with open items, most recently
     updated first, max 2. A pin button on each list header in `/lists`.
  6. **Spent this month:** one row per account from D5: label, amount, "unverified" in the same
     font beside the amount when true, and "Updated <relative time>" from `latest_row_at`. A tap
     opens a sheet with the category lines (spend vs target as a meter). No chart on the card.
- A floating "+" (56 px) opens the event sheet (D8).

### D7. Push notifications

- **Server app `server/push/`:**
  - `PushSubscription`: id, household, user, endpoint (unique), p256dh, auth, user_agent, tz (IANA,
    from the browser), created_at, last_ok_at, failure_count. In `TENANT_TABLES` and the leak test.
  - `PushPreference` (one per user): `list_changes`, `event_reminders`, `task_due_morning` (bools,
    default true when the user first subscribes), `morning_time` (default 07:30).
  - `PushSent`: dedupe ledger, unique on (user, kind, key). Keys: `list:<checklist id>:<batch end>`,
    `event:<event id>:<occurrence date>`, `morning:<local date>`.
  - Routes: `GET /api/push/vapid-public-key`, `POST /api/push/subscriptions`, `DELETE
    /api/push/subscriptions/<id>`, `GET/PUT /api/push/preferences`, `POST /api/push/preferences/off`
    body `{kind}` (the one-tap silence, D7 compulsion (d)).
  - `pywebpush` pinned in `requirements.txt`. Keys from env `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`,
    `VAPID_SUBJECT`. **Absent keys: push is off,** the notifications page says "Notifications are
    not set up on this server." (clone-and-run: a stranger's engine works without them).
  - `manage.py push_dispatch`, added to `deploy/crontab` as `*/5 * * * *`, runs through
    `JobCommand` like every other job. Each run:
    - **List changes:** items created in a shared list by member X more than 2 minutes ago and not
      yet in `PushSent`, grouped per list per creator, sent to every other member with
      `list_changes` on. Text: "Kevin added oat milk, eggs and 2 more to Groceries."
    - **Event reminders:** for each event the recipient can see with `remind_minutes_before` set,
      occurrences (D4 expansion) whose `starts_at - lead` falls in the last 5 minutes. Text: "Dentist
      at 3:00 pm, in 30 minutes." Private events remind only their owner.
    - **Morning tasks:** at the first run at or after `morning_time` in the subscription's `tz`,
      shared tasks and the recipient's own tasks due that local day and not done. One message: "3
      things due today: HW 4, rent, return library books." Nothing due means nothing sent.
    - A 404/410 from the push service deletes the subscription; other failures bump
      `failure_count`; 5 consecutive failures delete it.
- **New column:** `events.remind_minutes_before` int null, CHECK in (0, 5, 10, 15, 30, 60, 120,
  1440). On the wire. The phone ignores it until the Android agent adopts it.
- **Client:** a custom service worker via vite-plugin-pwa `injectManifest` (keeping today's
  `NetworkOnly` for `/api` and the `skipWaiting`/`clientsClaim` deploy fix from commit `206af39`),
  handling `push` (show notification with an action "Turn these off") and `notificationclick` (open
  the relevant route, or POST `/api/push/preferences/off` for the action).
- **iPhone:** push needs iOS 16.4+ and the PWA installed. `/settings/notifications` detects
  `navigator.standalone !== true` on iOS and shows a three-step install card (Share, Add to Home
  Screen, open from the icon) instead of the subscribe button.
- **Compulsion test (CLAUDE.md §7):** (a) every push names a fact the user can verify: a list item,
  an event time, a due date. (b) Each is actionable now. (c) None references absence, streaks or app
  use; the copy strings are fixed in `server/push/copy.py` and a unit test greps them for
  "haven't", "miss", "streak", "days since", "come back". (d) Every notification carries "Turn these
  off", one tap, and the settings page has a master off.

### D8. The event sheet (one component, both surfaces)

- `src/components/event-sheet.tsx`: a bottom sheet at family width, a right-side panel at workbench
  width. Fields: Title (required), All day toggle, Date, Start and end time, Repeats (Never, Daily,
  Weekly with weekday chips, Monthly on this date, Yearly; Ends: never, on date, after N), Reminder
  (None, At start, 5, 10, 15, 30 min, 1 h, 2 h, 1 day), Location, Notes, and a segmented control
  **Shared / Only me** (default Shared).
- Editing or deleting an occurrence of a series asks "Just this one" or "All of them" (D4).
- Save is **not optimistic** (an event write can be refused by validation); the button shows
  "Saving" and the sheet stays open on failure with the server's sentence.
- Validation mirrors the server's: title non-blank, end after start, weekday chips required for
  Weekly. The server remains the authority and its 400 sentence is shown verbatim.

### D9. Money workbench (`/money`)

- **Tabs:** Transactions, Budgets, Categories, Rules, Files.
- **Transactions:** fetched with `GET /api/ledger/transactions/` paged to `next: null` (a partial
  fetch renders "Still loading N of ...", never a total). Table columns: Date, Description, Account
  (label), Amount (right aligned, tabular), Category (inline combobox), Source ("You", "Rule", "Bank
  file", "None"), Status ("unverified" in words for UNRECONCILED rows, blank otherwise). Filter chips:
  account, month, category, "Needs a category", "Unverified". Virtualised rows (TanStack Virtual, one
  new dependency) because the full ledger is thousands of rows.
- Setting a category: `PUT /api/ledger/transaction_categories/<id>/` `{category, transaction_id}`;
  clearing: DELETE. Optimistic, rolled back with a sentence on failure (a category is a person's
  choice, as cheap as a tick).
- Row detail panel: all fields, `verification_note` verbatim, the category history line ("Rule said
  Groceries; you chose Household").
- **Budgets:** this month from D5, one row per category: spend, target, a horizontal meter, "unverified"
  where true. Edit target inline (PUT `budget_targets` with `effective_from_month` = this month).
- **Categories:** CRUD on `categories`, including the "Not spending" switch (`excluded_from_spend`).
- **Rules:** CRUD on `category_rules`; each row shows how many loaded transactions its text matches
  (client-side uppercase-contains over the loaded rows, labelled "matches in loaded rows").
- **Files:** `GET /api/ingest/files/`: name, state in words (Ingested, Could not be read, Held for
  review, Duplicate), quarantine reason verbatim, first seen, last attempt. Read only. No upload on
  the web: ingestion is the daily BofA pull.

### D10. Calendar (`/calendar`)

- **Family:** month grid with density dots (existing `MonthCalendar` vocabulary) above the tapped
  day's agenda; "+" opens D8.
- **Workbench:** Week view (7 columns, hour rows 6:00 to 23:00, all-day lane on top) and Month view,
  toggle in the header, "Today" and prev/next buttons. Click a slot to open D8 prefilled; click an
  event to edit. Canvas tasks render with their Canvas status line (existing `lib/canvas.ts`) and a
  done checkbox (PATCH `done`).
- Private rows show a lock and "Only you"; shared rows the pink marker and "Shared", on both
  surfaces.

### D11. Lists (`/lists`)

- Family: today's lists screen, restyled; ticked items fall into a collapsed "Ticked" section.
- Workbench: lists as columns (masonry, min 280 px), same item operations, plus rename, privacy toggle
  and the existing delete (not optimistic, D-copy "The list goes. What you ticked off it is kept.").
- Wording: "ticked", never "bought" (ADR 0049).

### D12. Settings (`/settings/*`), both surfaces

- `/settings/household`: name (rename, owner only, otherwise read only with "Only the owner can
  rename"), members with role and joined date, invites (create with max uses and expiry, copy link,
  revoke), remove member (owner, confirm sentence naming what goes: their private rows).
- `/settings/devices`: `GET /api/auth/devices`, revoke with confirm.
- `/settings/account`: name and password. **Server gap:** there is no endpoint for either. New
  `PATCH /api/auth/me` `{name}` and `POST /api/auth/password` `{current_password, new_password}`
  (session auth, CSRF, `login` throttle on the password route).
- `/settings/notifications`: D7.
- `/settings/appearance`: System / Light / Dark.
- `/join/<code>`: invite preview (`GET /api/auth/invite/<code>`), then signup (`POST
  /api/auth/signup`). `/signup`: a code field for when the link was typed by hand.
  Layout follows `docs/design/join-invite.md` (Apple Family Sharing / Life360 research).

### D13. Data freshness and polling

- Events and checklists: the existing `/api/changes` query gets `refetchInterval: 30_000` while
  `document.visibilityState === 'visible'`, plus refetch on focus and reconnect. User story 14's
  half-minute bound comes from this number.
- Ledger and spend: refetch on focus and every 5 minutes while visible (the source changes every 6 h).
- No offline write queue (not chosen). A failed write says what did not happen. The last good data
  stays on screen with the existing `Freshness` line ("as of 9:12") once a refetch fails.

### D14. Copy register

- Plain and warm (web-and-households 05). Never hardcode an assistant name. "unverified", never
  "UNRECONCILED". "estimate" on macros. "ticked", never "bought". Empty, unreachable and stale are
  three sentences, written per section.

---

## Testing Decisions

- **A good test drives what a person sees and does,** against the API boundary, not internals: render
  a route, answer HTTP from a fake engine, click, assert text. This is the existing pattern
  (`routes/-lists.test.tsx`, `routes/-login.test.tsx`): vitest + Testing Library + jsdom, fetch
  answered over real `Request`/`Response`, TZ pinned to `America/Chicago`.
- **Seams, highest first:**
  1. **The HTTP boundary** for every web screen. One fake-engine helper,
     `src/test/engine.ts`, grows route handlers per ticket. No mocking of hooks or components.
  2. **The choke point** for privacy: `server/tests/test_visibility.py`, two members of one
     household, every route and MCP tool for the five tables: list, detail (404), PATCH/DELETE (404),
     changes feed (redacted tombstone only), plus "no `owner_user_id` or `created_by_id` in any
     response or OpenAPI component".
  3. **Shared vectors** for recurrence (D4) and **phone-derived fixtures** for spend (D5).
  4. **`push_dispatch` as a function of (database, clock)** with a fake sender: pytest asserts the
     exact messages, the dedupe, and the copy-string grep.
- **Every screen gets one smoke test per surface** (render at family and workbench via a stubbed
  `matchMedia`) plus a test per behaviour its ticket names. Workbench-only affordances are asserted
  absent at family width.
- **Seeing it, not inferring it.** MEMORY 2026-09-11: the phone-width layout was "inferred from
  Tailwind classes, never seen". Each screen ticket ends with Playwright screenshots against `npm run
  build && npm run preview` with the fake engine, at 390x844 and 1440x900, light and dark, saved
  (not deleted) under `.scratch/web-revamp/research/shots/<ticket>/`. These are evidence for review,
  not golden tests (web-and-households 04: screenshot tests out of scope).
- Server tests follow `memory`: one server test DB at a time, no `--reuse-db`, `uv run --no-sync`.
- `npm run check:api` must pass after every server change that touches `openapi.yaml`.

## Out of Scope

- Offline writes on the web (not chosen 2026-10-03).
- Ingestion uploads on the web. Statements are gone; BofA activity arrives by the daily pull.
- "This and following" for repeating events.
- Spend alerts by push (not chosen).
- Roles inside a household beyond `owner` (ADR 0045 stands on that).
- Email delivery (web-and-households 07), CI/CD, hosting changes. Cloud Run stays.
- Postgres RLS (web-and-households 02b). Privacy is enforced at the Django choke point, and RLS is a
  belt that would later cover `owner_user_id` too.
- The phone app. Android-side follow-ups are listed, not built here.
- Voice-note audio on the web (no durable store, ADR 0041).
- Screenshot regression tests.

## Supersedes

Recorded here; the tickets themselves get a pointer line when the map lands.

| Ticket | What happens |
|---|---|
| web-and-households 05 (phase 1 screens) | Absorbed by "Join, signup and settings" and "The shell split by viewport" |
| web-and-households 06 (phase 2 screens) | Absorbed by the Money, Calendar, Pantry and body, Fleet places and notes tickets. Its trust rules carry over verbatim |
| web-and-households 11 (report endpoints) | Spend slice taken by "Spend on the engine"; the rest stays open there |
| web-surface 05 (look-back), 07 (re-brief) | 07 absorbed. 05 stays open there, not in this map |
| ADR 0040 | Its "one codebase, responsive" line is refined by ADR 0053 (rendered surfaces, not reflow) |
| ADR 0045 | Amended by ADR 0052 (private rows) |
| `docs/design/today.md` | Superseded where it says "no multi-column dashboard"; a note is added |

## Verification

Per ticket, in the ticket. For the map as a whole, done means all of:

1. `cd server/frontend && npm run build && npm test` green, test count read from vitest's JSON
   reporter, not the console line.
2. `npm run check:api` clean.
3. Server: `uv run --no-sync pytest` green by JUnit XML, including `test_visibility.py`,
   `test_tenancy.py`, `test_spend_parity.py`, the recurrence vectors and `push_dispatch` tests.
4. Playwright shots for every screen at 390x844 and 1440x900, light and dark, kept.
5. On live, by Kevin, owed and named in the map, never claimed by an agent:
   - Mia installs the PWA on her iPhone from an invite link, signs up, sees Home.
   - `make_private` run for `canvas:` and `course`; Mia's Home shows no coursework.
   - Mia's spend card for the two accounts equals the phone's Money figure to the cent.
   - Kevin adds to Groceries on the phone; Mia gets one batched push within about 7 minutes (2-minute
     batch window plus the 5-minute cron).
   - A shared event with a 30-minute reminder pushes to Mia's phone.
   - Kevin categorises a transaction on the web; the phone shows the same category after its next
     mirror.
