---
map: backend-etl
ticket: "09"
title: "connect_session.py bofa: log in daily, the script pulls new statements and mid-month activity"
type: build
status: open
status-detail: "Server half split out and built 2026-09-28 as ticket 13. Still OPEN: the laptop login script (tools/connect_session.py bofa), the one-button shortcut, and freshness source bofa. The script needs a live sitting with Kevin to capture BofA's page selectors."
blockers: ["02", "06"]
blocked-by: ["[[02-session-vault-and-login-handover]]", "[[06-drive-statements-watcher]]"]
tags: [ticket]
---

# BofA pull: statements and mid-month activity

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
