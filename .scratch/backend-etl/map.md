---
map: backend-etl
title: "The backend keeps itself current: scheduled pipelines into Django"
charted: 2026-09-27
charted-by: "Kevin + Opus"
effort: "`.scratch/backend-etl/`"
status: open
tags: [map]
---

# The backend keeps itself current

**Kevin, 2026-09-27:** *"right now the django backend feeds the native app. data is stale i think.
lets set up proper etl pipelines to keep the backend data up to date."*

## What was found (2026-09-27, measured against the live database)

**The server pulls from nothing.** Zero scheduled jobs: the worker runs `supercronic` over an empty
`/etc/crontab`, `deploy/crontab` does not exist, and `deploy/cloudrun/install_schedule.py` refuses
for that reason. Every table is filled by the phone pushing, and the phone pushes only from
`MainActivity.onResume` (no WorkManager, ruled out on Doze facts, `decisions.md`).

| Data | Last new row | Why |
|---|---|---|
| Canvas tasks (`events`, `kind=task`, `DETERMINISTIC`) | 2026-09-05 | Only ever fed by a hand-run script |
| WebAssign completion | never | Unbuilt, auth never ruled |
| `ledger_transactions` (7 rows), `statements` (0) | 2026-09-02 | Manual upload from the phone only |
| `receipts` (3) | 2026-08-26 | Manual only |
| `obd_samples` (20,796), `drives` | 2026-08-30 | Resume-only push; possibly no drives since |
| `conversation_audit` | 2026-09-10 | Broken: django-engine 17 |
| Backups | never | `backup_nightly` unbuilt |

## Rulings (Kevin, 2026-09-27, by interview)

1. **Jobs run as Cloud Run Jobs fired by Cloud Scheduler.** `deploy/crontab` stays the one source;
   `install_schedule.py` turns it into Scheduler entries, the compose worker reads the same file.
2. **Third-party sessions come from a login script.** Kevin logs in by hand in a real browser
   (SSO, captcha, 2FA all his to clear); the script hands the saved session to the server. Applies
   to Canvas and WebAssign. No password is ever stored.
3. **Bank data: the server watches a Drive folder, and Kevin drops RAW PDFs/CSVs there.** The
   server extracts. This amends CLAUDE.md §4 rule 1's 2026-08-25 amendment (user's own LLM masks
   before upload): extraction now runs server-side with the household's own Gemini key. The gate
   (three anchors, quarantine, provenance, anchors persisted) is unchanged and binds in full.
4. **Backups go to Kevin's Google Drive**, sharing the Drive credential with the statement watcher.
5. **`obd_samples`: roll up per drive, keep 90 days raw**, deletion only after a successful backup.
6. **A stale or failing feed is said in words** on the web and phone surfaces that use it. No
   notification, no email.
7. **BofA statements are pulled by the login script, in the login sitting, on Kevin's machine.**
   No BofA session ever reaches the server. Statement PDFs only; card CSVs stay rule 7 provisional.

## Tickets

| # | Ticket | Blocked by |
|---|---|---|
| 01 | Job runner, `ingest_runs`, freshness endpoint, `deploy/crontab` | - |
| 02 | Session vault + `tools/connect_session.py` login handover | 01 |
| 03 | `backup_nightly` / `restore_backup` to Drive | 01, 02 |
| 04 | `canvas_poll` | 01, 02 |
| 05 | `webassign_read` | 04 |
| 06 | `drive_statements` watcher: raw statement to gate | 02, 03 |
| 07 | OBD roll-up and 90-day retention | 03 |
| 08 | Freshness line on the web app | 01 |
| 09 | `connect_session.py bofa`: log in monthly, script pulls new statements to Drive | 02, 06 |
| 10 | Port `BofaStatementParser` to Python | 06 |

Out of this map, owned by the Android terminal: django-engine 17 (conversation audit), and the
phone's freshness line (Android reads ticket 01's endpoint).

Supersedes: django-engine 06 (worker), two-clients 03 and 05, chief-of-staff 06. Their binding
rules are carried into tickets 03-05 by reference and bind unchanged.
