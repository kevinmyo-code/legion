---
map: backend-etl
ticket: "09"
title: "connect_session.py bofa: log in daily, the script pulls new statements and mid-month activity"
type: build
status: open
status-detail: "Reworked 2026-09-28 on feat/bofa-csv-only: transaction CSVs only, no statement PDFs (Kevin). Laptop script pulls Current transactions daily plus closed periods Drive lacks; server gates a checking closed period and holds every current file provisional. Unit and DB tests green; never run against the real site. OWED: the live --dry-run with Kevin, then one real run. Still unbuilt: freshness source bofa (server)."
blockers: ["02", "06"]
blocked-by: ["[[02-session-vault-and-login-handover]]", "[[06-drive-statements-watcher]]"]
tags: [ticket]
---

# BofA pull: statements and mid-month activity

## Decision 2026-09-28: transaction history only, no statement PDFs

**Kevin, 2026-09-28:** *"lets not use statements. only the transaction history. i just need to know
what im spending on."* This supersedes ruling 3's statement-PDF half and every statement step
below. The script never visits Statements & Documents, and no PDF is downloaded, ever.

**Two file kinds per account, told apart by NAME** (the content cannot tell them apart):

| File | What | Checking | Card |
|---|---|---|---|
| `bofa_<last4>_activity_<YYYY-MM-DD>.csv` | "Current transactions", pulled daily, dated today. Window still open. | provisional (rule 7) | provisional |
| `bofa_<last4>_period_<YYYY-MM-DD>.csv` | One closed period, dated by its END as BofA lists it. | **gated** (ruling 4), verified | provisional |

- Checking dropdown `#select_txnPeriod`: `Period ending 09/04/2026`. Card dropdown
  `#select_transaction`: `September 05, 2026`. Both mapped to ISO dates.
- The script pulls Current transactions every run, plus every closed period ending within the last
  13 months whose `_period_` name Drive does not already hold (the folder listing is the dedupe).
- **Why a checking current file is provisional although it prints balances:** its window is still
  open. Gated, every daily file became its own overlapping verified "statement", and the days
  between the last pull and the period's close were listed by none of them. The closed period is
  what gets verified: when it commits, `commit_statement` deletes the provisional rows in its
  window (rule 7 condition 4). The current file's arithmetic is still checked (a failure still
  quarantines); none of its balances is stored as an anchor. The period file's "Ending balance as
  of" must equal the date in its name, or it is refused.
- A checking CSV under any other name, BofA's own `currentTransaction_<last4>.csv` included,
  quarantines in words. The card keeps accepting `currentTransaction_<last4>.csv` as a current file.
- **Correction to the brief (tested 2026-09-28):** the brief reasoned that overlapping daily
  checking files would store every row twice. A test against the old code showed they did not:
  `resolve_dedup`'s strict pass absorbs exact repeats, so rows were stored once. What the old code
  did do was file one verified `statements` row per daily pull, overlapping each other, and leave
  the pre-close days unlisted. The rework fixes both; the double-store was never the failure.

**Built (branch `feat/bofa-csv-only`):** `server/ingest/parsers/bofa_activity.py` (routing by
name), `tools/bofa_pull.py` (statements step removed; period downloads added),
`tools/tests/test_bofa_pull.py`, and overlap tests in `server/tests/test_bofa_activity_ingest.py`:
`test_checking_current_day_1_then_day_2_then_the_closed_period_holds_each_row_once`,
`test_checking_current_file_after_a_closed_period_leaves_the_verified_rows_untouched`,
`test_card_current_then_closed_period_then_next_current_holds_each_row_once`,
`test_identical_bytes_twice_are_a_no_op` (three kinds).

**Owed, live:**

1. **A dry run with Kevin:** `python tools/connect_session.py bofa --dry-run`. Check: the checking
   dropdown really reads `Period ending MM/DD/YYYY` and the card's `Month DD, YYYY`; the card panel
   stays open between downloads (the script clicks its link only when the select is hidden); each
   period file's "Ending balance as of" date equals the date in its name (reasoned, not seen; a
   mismatch quarantines every period file in words); the downloaded CSVs are the layouts the
   server reads.
2. **Then one real run:** every closed period lands; each checking period commits with anchors or
   quarantines with a reason; card periods and current files land unverified; a second run the same
   day downloads no period and duplicates nothing.
3. Whether `drive.file` may create in a folder the app did not make (unchanged, below).

> Everything below this line predates the decision above. Where it talks about statement PDFs or
> Statements & Documents, it is history, not the plan.


> **Split 2026-09-28.** The server side (the `bofa_activity.py` readers, the rule 7 provisional
> writer, watcher routing, "unverified" on the API) is BUILT as [[13-bofa-activity-csv-server-side]]
> on `feat/bofa-activity`. **What stays open here, all of it on Kevin's laptop:**
>
> 1. **`tools/connect_session.py bofa`** (headed Chromium, login handover, statement and activity
>    download, Drive upload). **Not started, deliberately.** It needs a live sitting with Kevin to
>    capture BofA's real page selectors; no selector is written until they have been seen working.
> 2. **`tools/legion-daily.cmd` and `tools/install_daily_shortcut.py`** (the one button).
> 3. **`/api/freshness` source `bofa`** (activity stale after 36 h, statement after 40 days).
>
> The script must name files `bofa_<last4>_activity_<YYYY-MM-DD>.csv` (or keep BofA's
> `currentTransaction_<last4>.csv`): the server takes the account from the name, because neither
> export prints one. Ruling 4 is confirmed from the record (commit 2d188e7 read the real checking
> export's anchors), so checking CSVs are gated; box 5 is ticked on the first real pull.

## Built 2026-09-28 (laptop half, branch `feat/bofa-pull`)

Locators captured live the same day (checking 3119, card 4146) and written into ONE block at the top
of `tools/bofa_pull.py`, commented "last seen working 2026-09-28".

- **`python tools/connect_session.py bofa`**: headed Chromium on `~/.legion/browser-profiles/bofa`
  at bankofamerica.com. Kevin logs in; the script never touches the sign-in form. Logged-in signal:
  title contains "Accounts Overview" or the path is under `/myaccounts/` (not signin); Enter is the
  fallback. Timeout: "No login seen within N seconds. Nothing was downloaded and nothing was
  uploaded."
- **The subcommand has no `--server` and no `--token`**, so it has no way to address the LEGION
  server. `put_session` still refuses `bofa`; a test patches it to explode and runs the whole path,
  and an AST test checks neither `bofa_pull.py` nor `run_bofa` names the vault route.
- Per account (anchors with `target=acctDetails`, text `<name> - <last4>`): the current-activity CSV
  as `bofa_<last4>_activity_<YYYY-MM-DD>.csv` (checking via the "Download your data" dialog, card via
  the `download_transactions_top` panel), then Statements & Documents for this year and last,
  downloading only statements not already in Drive, as `bofa_<last4>_<YYYY-MM>.pdf`. Statement links
  are found by accessible name (the `#downloadPDFLink` id repeats); one that does not parse or names
  another account stops the run.
- **Drive**: laptop-only installed-app OAuth, scope `drive.file` only, same client id/secret as
  `drive`, token cached at `~/.legion/bofa-drive-token.json`. Folder from `--statements-folder` or
  `LEGION_STATEMENTS_FOLDER` (id or URL; `folder_id_from` copied from `server/ingest/folder_ids.py`,
  a test pins the copy to the original). Dedupe lists what this app created in the folder: a statement
  already there is never downloaded; a same-day CSV re-run PATCHes that file instead of adding a twin.
- **Nothing partial**: every download first, Drive only after every browser step succeeded. Any
  locator that finds nothing: "BofA's page changed at <step>; nothing was uploaded."
- **`--dry-run`**: everything in the browser, downloads to `--out` (default
  `~/.legion/bofa-pull/<date>/`), Drive not consulted at all, prints what would be uploaded.
- Summary per account: CSV name, statements found / already in Drive / uploaded. No balances, no
  transaction content.
- **`tools/legion-daily.cmd`**: reads `%USERPROFILE%\.legion\legion-daily.env.cmd` (the three env
  vars, laptop only), runs `connect_session.py bofa %*`, pauses on the result. Shortcut: right-click >
  Send to > Desktop. **`install_daily_shortcut.py` deliberately not built** (the right-click is one
  step; an installer is risk for nothing). The `~/.legion/config.env` + `setup` subcommand idea above
  is replaced by that env file.
- Tests: `tools/tests/test_bofa_pull.py`, 58, no network, no server DB. Run
  `python -m pytest tools/tests -p no:cacheprovider --junitxml=<file>`.

**Owed, all live, none of it done:**

1. **The dry run with Kevin**: `python tools/connect_session.py bofa --dry-run`. Nothing in this
   file has ever run against the real site; the fake only proves the flow.
2. **Does `drive.file` let the app create a file inside a folder it did not create?** Reasoned
   unknown. If Google answers 403/404 on the parent, the script stops with one sentence naming the
   two fixes (the app owns its own statements folder and the watcher points there, or widen the
   laptop scope to `drive`). Kevin's call; the scope was not widened.
3. **The card's Statements & Documents page was never seen.** It is assumed to share checking's
   `/mycomm-acc-stmts-docs/` layout; if the address differs the run stops saying so.
4. **Year switch**: after choosing a year the script waits for network idle and expands
   "Statements". Whether the list refreshes in place is unseen; check on the dry run that the two
   years' PDFs differ.
5. **Done 2026-09-28** (renamed `test_bofa_takes_no_server_and_no_token`). `server/tests/test_connect_session.py::test_bofa_is_not_a_subcommand_yet` still passed (bofa
   rejects `--server`, so argparse exits), but its docstring is now false. Server terminal: rename it
   to assert bofa takes no `--server`/`--token`.

**Kevin, 2026-09-27:** *"yes that works instead of me manually navigating the page and putting it on
the drive folder."* **2026-09-28:** *"it might also be mid month transaction history pulls no?
statements only come end of month"*, *"i want the ledger daily"*, and, offered a bank feed
(SimpleFIN) for zero-effort daily freshness, *"nvm i'll login daily."*

## Rulings

1. **The pull happens inside the login sitting, on Kevin's own machine**, never on the server. A
   BofA session is not stored anywhere: idle timeout is minutes and BofA fingerprints the device,
   so a replayed session would be dead or read as a hijack (reasoned, not tested). Nothing in this
   ticket may send a BofA cookie to the server.
2. **Daily cadence, by Kevin logging in.** No bank feed, no stored password, no unattended login.
3. **Two kinds of file per account, per run:**
   - **Statement PDFs** (monthly): through ticket 06's gate, three anchors, verified.
   - **Current-activity CSV** (mid-month, since the last statement): through §4 rule 7's
     provisional path (django-engine 13, resolved 2026-09-28 to option 2). All four conditions
     bind: **deterministic extraction only** (a Python CSV reader, never Gemini, 0 tokens); every row
     `UNRECONCILED`; every surface says "unverified" in words; rows deleted when a gated statement
     commits over the same account and window (supersession already built and tested:
     `test_a_gated_statement_supersedes_provisional_rows_in_its_window`).
4. **A checking-account CSV that prints its own beginning and ending balance may be gated**, not
   provisional: beginning + sum(lines) = ending is a real anchor pair stated by the document. This is
   reasoned from memory of BofA's export shape, NOT confirmed. Confirm on Kevin's first real file;
   until confirmed, every CSV is provisional. Never synthesise a balance the file does not print
   (§4 rule 8).

## Build

- `tools/connect_session.py bofa` (extends ticket 02's script): headed Chromium at BofA sign-in on
  the persisted profile (`~/.legion/browser-profiles/bofa`, laptop only, so 2FA stays light). Kevin
  logs in; the script waits for the post-login signal, then for each account:
  - **Statements & Documents**: list statement PDFs, download only what the server does not already
    hold (`GET /api/ingest/files?source=bofa` returns known `(account_hint, period)` pairs and
    SHA-256s; server-side `ingested_files` sha256 idempotency is the backstop).
  - **Activity download**: the CSV of transactions since the last statement.
- Files go into the household's statement Drive folder, named `bofa_<last4>_<YYYY-MM>.pdf` and
  `bofa_<last4>_activity_<YYYY-MM-DD>.csv`. Ticket 06's watcher routes by type: PDF to the gate,
  a recognised BofA activity CSV to the provisional path. An unrecognised CSV quarantines with a
  sentence; it is never sent to Gemini.
- **Server side** (BUILT, moved to [[13-bofa-activity-csv-server-side]]): `server/ingest/parsers/bofa_activity.py`, one deterministic
  reader per BofA CSV layout (card, checking), every line must parse or the file quarantines
  (§4 rule 6). The provisional write path the watcher calls in-process; the DB constraints in
  django-engine 13's evidence table already require `statement_id IS NULL` on these rows.
  Re-pulling the same day's CSV replaces that account's provisional rows in the window rather than
  duplicating them (match on date, amount, description, and position within same-day duplicates).
- Selectors live in one small module with a comment naming the date they were last seen working.
  A page change fails loudly ("BofA's page changed; nothing pulled"), never a partial silent pull.

## One button (Kevin, 2026-09-28: *"give me like a 1 button press script that i can run every day
## myself to keep ledger updated"*)

- **`tools/legion-daily.cmd`** plus a one-time `tools/install_daily_shortcut.py` that puts a
  "LEGION daily" shortcut on the Windows desktop pointing at it. Double-click is the whole routine:
  the browser opens on BofA, Kevin logs in (and clears 2FA if asked), everything else is automatic,
  and the window ends on a plain summary, e.g. "Pulled 1 activity file (Card 4821: 6 new
  transactions, unverified until the September statement). No new statements. Ledger current to
  today." It waits for a keypress before closing, so the result is read, not flashed.
- Settings come from **`~/.legion/config.env`** on the laptop (server URL, device token, Google
  client id/secret for the Drive upload), written once by
  `connect_session.py setup` (prompts, token input hidden). Never in the repo, never on the server.
  No BofA password is stored anywhere: typing it is Kevin's step.
- Every run also checks the server's freshness for the ledger and prints it, so a failed job or
  a quarantined statement is seen on the same screen rather than discovered later.
- Failure modes say what did NOT happen, in words (§7): "Nothing was uploaded: BofA login timed
  out after 5 minutes" / "BofA's page changed: nothing pulled, tell Claude".

## Freshness

`/api/freshness` gains source `bofa`:
- **Activity:** stale after 36 hours. "BofA last pulled 2 days ago: run tools/connect_session.py
  bofa".
- **Statement:** stale when the latest committed statement period is more than 40 days old.
Words only (ruling 6). No notification (compulsion test: anchored to a fact, silenceable).

## Verification

- [ ] Kevin runs it once: every statement from the last 12 months lands and ticket 06 commits each
      with anchors persisted or quarantines it with a reason.
- [ ] The same run lands each account's activity CSV; its rows appear `UNRECONCILED`, and the web
      and phone ledger say "unverified" in words.
- [ ] Second run the same day downloads no statements and duplicates no activity rows.
- [ ] When a statement commits over an activity window, the provisional rows in it are gone.
- [ ] Rule 4 settled on a real checking CSV: gated or provisional, recorded here.
- [ ] grep the server's stored rows and logs: no BofA cookie anywhere.
