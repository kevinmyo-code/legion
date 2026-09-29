---
map: backend-etl
ticket: "14"
title: "Ledger rows reach the phone: categorised at insert, mirrored whole, deletions honoured"
type: build
status: built
status-detail: "Decision resolved 2026-09-28 (Kevin: \"yes 2\"): category overrides table, built 2026-09-29 on feat/ledger-category-overrides with the Transfers not-spending flag. Not merged, not deployed. Owed: deploy (server BEFORE the APK), the live apply_category_rules backfill (dry run first), and the phone checks below."
blockers: ["06", "13"]
blocked-by: ["[[06-drive-statements-watcher]]", "[[13-bofa-activity-csv-server-side]]"]
tags: [ticket]
---

# Ledger rows reach the phone

## The problem (traced 2026-09-28)

The server held about 850 BofA rows (checking 3119: 208 DETERMINISTIC + 20 UNRECONCILED; card 4146:
27 DETERMINISTIC + 622 UNRECONCILED) and the Money tile read "$0 of $1,300". Four causes, all traced
from code:

1. **Nothing pulled them.** `LedgerTransactionsSync.maybeAutoPull` had a Django branch and no
   caller. The only caller of `pull` was `LedgerTransactionsRealtime`, which is Supabase-only.
2. **Every row arrived uncategorised**, and budget spend counts only categorised rows.
3. **Deletions never reached the phone.** `pull` is insert-if-absent; a rule-7 supersession
   deletes UNRECONCILED rows server-side and the phone kept them. That is now the daily path, so it
   would double-count spend.
4. **The list could not be read whole.** Gated rows page by `created_at`, and one gate commit
   writes every line with one `created_at`. More than 500 lines on one timestamp made `next`
   repeat and the client stopped early, silently.

## Built (2026-09-28, `feat/phone-ledger-pull`)

- **Server: categorised at the INSERT** (`server/ingest/category_rules.py`), in both writers: the
  gate commit (`ingest/views.py`) and the rule-7 provisional writer (`ingest/provisional.py`).
  Household-scoped. Semantics ported from `LedgerController.applyCategoryRules`: active rules
  oldest first by `created_at_client`, case-insensitive substring, oldest match wins. A category
  the payload states is kept.
- **Server: keyset paging** (`api/sync.paginate_keyset`). `next` unchanged, plus `next_after` (the
  last row's id) and `?after=`. A client sending only `since` gets exactly the old behaviour, so
  installed APKs are unaffected. `openapi.yaml` and the web's `schema.d.ts` regenerated.
- **The deletion contract: option (a), the full list is the set.** Documented on
  `LedgerTransactionViewSet`. The phone fetches the whole list and deletes a server-origin row the
  list does not contain, only when the read reached `next: null`.
- **Phone: `LedgerTransactionsSync.mirror`** on the Django transport, wired into
  `MainActivity.onResume` and SYNC NOW. Inserts, fills a category where the phone has none, deletes
  rows the engine no longer lists. Never deletes a row whose `sourceFile` is not `synced` (voice
  pending, anything parsed or minted on the phone), enforced again in the DAO's SQL. Deletes
  nothing off an incomplete or empty list. A failed read writes nothing.
- **Phone: its own rules run after the mirror.** It is the existing `applyCategoryRules`, not a
  second copy. This is the only thing that categorises the rows stored before this ticket (see the
  decision below).
- **Phone: `categoryPending` is not copied verbatim.** The server's flag means "not categorised
  yet", the phone's means "unconfirmed AI guess". Copied, every server row would read "category
  guessed, not confirmed".
- **Server down, said in words.** `LedgerMirrorStatus`: "Not synced - this phone's last copy" leads
  the Money tile's disclosure, and the full sentence sits under the Money screen's title. Home and
  the Money screen both refresh when the mirror lands, since the mirror is fire-and-forget from the
  same resume.
- Unverified in words was already true on every Money surface: tile ("unverified"), budget section
  ("includes pending transactions not yet on a statement"), rows ("pending, not verified").

## Decision: option 2 (Kevin, 2026-09-28: "yes 2")

Resolved. Built 2026-09-29 on `feat/ledger-category-overrides`, in this ticket rather than a new
one because the brief put it here.

- **Server: `ledger_transaction_categories`** (`server/ingest/category_overrides.py`, DDL in
  `ingest/migrations/0007`). One row per transaction; `source` in {`rule`, `person`}; `deleted_at`
  tombstone; household must equal the transaction's (composite FK); `rule` never replaces a live
  `person` row (API refusal in words and a SQL trigger); cascades with a rule-7-superseded row. No
  FK to `categories`, reasons in `create_sql`'s docstring. In `TENANT_TABLES`, leak-tested.
- **Server: the effective category.** `GET /api/ledger/transactions/` and `/api/changes` serve
  `category` = override if live, else stored; plus `stored_category` and `category_source`
  (`person` / `rule` / `stored` / null). Installed APKs get the effective category with no change.
- **Server: `PUT|DELETE /api/ledger/transaction_categories/<transaction_id>/`**, synced like the
  other config routes.
- **Server: `manage.py apply_category_rules [--dry-run] [--household <uuid>]`.** Writes `rule`
  overrides for rows whose effective category is empty. Idempotent, never touches a `person` row,
  leaves a deliberately deleted override alone. There is no server-side budget or report figure to
  change: the server serves rows, not totals (grepped).
- **Phone:** a category set by hand (`recategorize`, `setCategory`, `confirmCategoryGuess`) goes to
  the engine as a `person` override, queued in the outbox when the engine is down and said so in
  words (voice result, Money screen line). The mirror's precedence is written once,
  `backend/LedgerTransactionsMirror.kt` `mirroredCategoryFill`. The phone's own rules after the
  mirror are now the offline fallback only.

### Added 2026-09-29: Transfers is not spending

Kevin, 2026-09-29: "ignore zelle for spending. its just transfer between here and there."
`categories.excluded_from_spend` (server migration `0008`, Room v71 `excludedFromSpend`) is the one
definition. Every phone spend figure leaves those rows out and says so in words ("N transactions in
Transfers (USD X) excluded from spend"; HOME: "Excludes USD X in Transfers"). `Transfers` is seeded
on the phone with the flag on and reaches the server through the category backfill. Card-payment
pairing (`analyzeTransfers`) is unchanged. The rules `ZELLE PAYMENT TO` and
`ONLINE BANKING TRANSFER TO SAV` -> Transfers are to be added on live after deploy, not here.

## Decision owed: the rows already stored (Kevin) - RESOLVED above, kept for the reasoning

`forbid_mutation_of_facts` refuses every UPDATE on `ledger_transactions`, category included. So the
one-shot `apply_category_rules` backfill the plan asked for **cannot be built** without touching the
trigger, and was not. Stopped and reported instead.

- New rows (every ingest from deploy on) carry a category from insert.
- The ~850 stored rows stay `category NULL` on the server forever. The phone categorises them
  locally, so the Money tile is right, but the web app and anything else reading the server sees
  them uncategorised.
- The 642 UNRECONCILED rows are transient anyway: supersession replaces them. The 235
  DETERMINISTIC ones are permanent.

Options, for Kevin: (1) accept it (phone-local categories for the old rows); (2) an authored
`ledger_transaction_categories` table (txn id, category, source, tombstone) that overlays the
gated row's `category` on read. It keeps the trigger, fits the authored/gated split `api/ledger.py`
already draws, and would also let a hand-set category on the phone reach the server, which today it
cannot; (3) re-ingest the source statements after deleting their rows, which the trigger also
refuses for gated rows. Recommendation: (2), as its own ticket.

## Verification

- [x] Server suite green by JUnit XML: 1169 tests, 1126 passed, 43 skipped, 0 failures, 0 errors.
- [x] Android `compileDebugKotlin -Pnokey`, `testDebugUnitTest -Pnokey` green by JUnit XML: 3737
      tests, 0 failures, 0 errors, 0 skipped (400 suites).
- [x] Room schema JSON unchanged (DAO query only, no migration).
- [ ] **Deploy** Cloud Run. Until then the phone mirror reads an engine without `next_after`: it
      inserts but deletes nothing whenever a page stalls, and says so in SYNC NOW.
- [ ] **On the A25**, after installing the APK:
  1. Open the app, wait a few seconds on HOME. The Money tile shows a real September figure
     against USD 1,300.00, not $0, with "Excludes USD X uncategorized" if any remain, and
     "unverified". **If the figure looks roughly double**, the phone still holds rows it parsed
     from files itself before statement ingestion left the phone: those are phone-origin, the
     mirror never deletes them, and they duplicate the server's. Not handled here; say so and it
     gets its own ticket.
  2. Setup -> SYNC NOW. The "Money:" line reads pulled N new, N removed (not "none removed").
  3. Money screen: card 4146 rows show "pending, not verified". No uncategorised row says
     "category guessed".
  4. Airplane mode, SYNC NOW, back to HOME: the tile says "Not synced - this phone's last copy"
     and the figures are unchanged. Airplane off, SYNC NOW: the line goes.
  5. A voice-logged pending charge ("log a $5 coffee") survives a SYNC NOW.
- [x] ~~Kevin runs nothing extra on the live DB~~ superseded by option 2: there IS a backfill now.

### Option 2 + Transfers (2026-09-29)

- [ ] **Deploy the server BEFORE installing the new APK.** The new APK sends
      `excluded_from_spend` on every category write, and an engine without the column refuses an
      unknown field with a 400; the override route does not exist before deploy either.
- [ ] **Live backfill**, dry run first, then for real (commands in the build report).
- [ ] On the A25:
  1. Money: a row set by hand (tap a row, pick a category) survives SYNC NOW; the web list or
     `GET /api/ledger/transactions/` shows it with `category_source: person`.
  2. Airplane mode, set a category, back to Money: the line under the title says it is saved on this
     phone and not yet on the server. Airplane off, SYNC NOW: the "Money:" line reports "sent 1
     category choices", and the line under the title goes.
  3. Settings/categories include `Transfers`. After the live rules are added and a mirror runs, a
     Zelle row shows under Transfers, the Money tile says "Excludes USD X in Transfers", the SPEND
     figure drops by that amount, and card payments are still in the own-account disclosure.
  4. Ask by voice for this month's spend: the answer says the Transfers amount was excluded.
