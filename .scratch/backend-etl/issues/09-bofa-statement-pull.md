---
map: backend-etl
ticket: "09"
title: "connect_session.py bofa: log in once a month, the script pulls new statements"
type: build
status: open
blockers: ["02", "06"]
blocked-by: ["[[02-session-vault-and-login-handover]]", "[[06-drive-statements-watcher]]"]
tags: [ticket]
---

# BofA statement pull

**Kevin, 2026-09-27:** *"yes that works instead of me manually navigating the page and putting it on
the drive folder."*

## Ruling (Kevin, 2026-09-27)

The pull happens **inside the login sitting, on Kevin's own machine**, never on the server:

- A BofA session is not stored anywhere. Idle timeout is minutes and BofA fingerprints the device,
  so a session replayed from Cloud Run would be dead or read as a hijack (reasoned, not tested).
  Nothing in this ticket may send a BofA cookie to the server.
- Statement PDFs only. BofA card CSV exports print no anchor and stay a rule 7 provisional path,
  never pulled by this script.

## Build

- `tools/connect_session.py bofa` (extends ticket 02's script): headed Chromium at BofA sign-in,
  Kevin logs in and clears 2FA; the script waits for the post-login signal, then for each account
  opens Statements & Documents and lists available statement PDFs.
- **Already have it?** The script asks the server (`GET /api/ingest/files?source=bofa`, returning
  known `(account_hint, period)` pairs and SHA-256s) and downloads only what is new. Server-side
  `ingested_files` sha256 idempotency is the backstop, so a re-download is harmless.
- Each new PDF is uploaded into the household's statement Drive folder (the `drive` credential,
  via the server or the same OAuth token on the laptop, whichever ticket 02 made simpler), named
  `bofa_<account-last4>_<YYYY-MM>.pdf`. Ticket 06's watcher takes it from there, gate and all.
- Selectors live in one small module with a comment naming the date they were last seen working.
  A page change fails loudly with "BofA's page changed; statements not pulled", never a partial
  silent pull.
- Browser profile directory persisted on the laptop only (so BofA recognises the device and 2FA
  stays light). Gitignored path under the user's home, never the repo.

## Freshness

`/api/freshness` gains source `bofa`: stale when the latest committed BofA statement period is more
than 40 days old. Sentence: "BofA statement for September not pulled yet: run
tools/connect_session.py bofa". Words only (ruling 6).

## Verification

- [ ] Kevin runs it once: every statement from the last 12 months appears in the Drive folder, and
      ticket 06 commits each with anchors persisted or quarantines it with a reason.
- [ ] Second run downloads nothing.
- [ ] grep the server's stored rows and logs: no BofA cookie anywhere.
