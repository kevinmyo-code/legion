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

## Where we stopped - 2026-09-28 (session 38e6b7d5, before a shutdown)

**Live on Cloud Run (deployed from `897fcb2`):** tickets 01-04. Scheduler: `heartbeat` and
`canvas_poll` every 30 min, `backup_nightly` 03:00 UTC. Vault key and Gemini key are in Secret
Manager (`LEGION_VAULT_KEY`, `LEGION_GEMINI_KEY`; the second is NOT yet in `SECRET_ENV_VARS`).
Drive connected with `--backup`; Canvas connected (uhv.instructure.com). First backup and restore
drill passed; Canvas wrote 7 new tasks. Google OAuth app published (privacy page on Pages).

**Merged to dev:** everything through `502ffba` (tickets 01-04, vault key). Not on `main`.

**On `feat/backend-etl`, not deployed:**
- `63a5425`, `6a5baa7`: tickets 11/12 charted; ticket 09 rewritten (daily BofA pull, one-click
  `LEGION daily` launcher, mid-month CSV via rule 7); django-engine 13 resolved to option 2.
- `004ac07` **WIP, untested, do not deploy.** A builder was stopped mid Part 2:
  1. Canvas title whitespace + data migration `ingest/0005` (drafted).
  2. Ticket 06 `drive_statements` (in progress: `statements.py`, `drive_statements` and
     `set_statements_folder` commands, `folder_ids.py`, edits to `drive.py`, `views.py`,
     `jobs.py`, `freshness.py`, `connect_session.py`). Folder id `19tqQKzPKZVm0zCVG-lt7zaERqstIPkNd`.
     Add `LEGION_GEMINI_KEY` to `SECRET_ENV_VARS`. CSVs never go to Gemini.
  3. **Not started:** Canvas never ticks (Kevin 2026-09-28: ticks are manual, Canvas is a double
     check). New migration replacing `upsert_canvas_task`: never set `done`, write
     `structured_meta.canvas_submitted`. Mark ticket 05 `kiv`. decisions.md entry.

**Next, in order:** finish and test 004ac07's three parts (full server suite, JUnit), deploy, run
`set_statements_folder`, ticket 11 (web "Canvas says submitted", frontend agent), ticket 09 (BofA
daily + launcher), ticket 10. Ticket 12 belongs to the Android terminal.
