---
map: backend-etl
ticket: "02"
title: "Session vault and the login handover script"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-job-runner-and-freshness]]"]
tags: [ticket]
---

# Session vault and login handover

Ruling 2: Kevin logs in by hand in a real browser; a script hands the session to the server. No
password is ever stored.

## Build

- **Model `ingest.SourceCredential`** (tenant table): `household`, `source`, `kind`
  (`cookie_jar` / `oauth_refresh`), `ciphertext`, `captured_at`, `expires_hint`, `invalid_since`,
  `config` (JSON, non-secret, e.g. the Drive folder id, the Canvas base URL).
  Encrypted with Fernet under `LEGION_VAULT_KEY` (env; `.env.example` documents generating one).
  No key set: the vault refuses to store and says so; jobs record `needs_login`.
- **`PUT /api/ingest/sessions/<source>`**, owner role only. The response never echoes the secret.
  `GET` returns metadata only.
- **`tools/connect_session.py <canvas|webassign|drive> --server URL --token TOKEN`**, run on
  Kevin's laptop. canvas/webassign: Playwright opens a headed Chromium at the login URL, waits until
  a post-login signal appears, exports the domain's cookies, PUTs them. drive: Google installed-app
  OAuth flow (scopes `drive.readonly` + `drive.file`), PUTs the refresh token. Client id/secret are
  the household's own (BYO), from args or env.
- **A job that gets 401/403 or a login redirect** sets `invalid_since`, records `needs_login`, and
  freshness says "Canvas needs you to log in again: run tools/connect_session.py canvas".

## Known caveat, stated up front

A Google OAuth client left in **Testing** status issues refresh tokens that die after 7 days. The
household's client must be set to **In production** (unverified is fine for its own users). The
script prints this and the README says so.

## Verification

- [x] pytest: ciphertext round-trip; stored bytes never contain the plaintext.
- [x] pytest: non-owner PUT refused; GET never returns the secret.
- [ ] By hand, owed on Kevin's login: the script stores a canvas session and ticket 04 uses it.

## Built (2026-09-27, `feat/backend-etl`)

- `ingest.SourceCredential` (`source_credentials`, `public`, in `TENANT_TABLES` and
  `DJANGO_MANAGED_TENANT_TABLES`), `ingest/vault.py`, `ingest/sessions.py`,
  `tools/connect_session.py`. Tests: `tests/test_session_vault.py`,
  `tests/test_connect_session.py` (the script, no browser, plus its PUT body against the real
  route), `tests/test_tenancy.py::test_sessions_are_scoped_by_household`.
- Routes: `GET /api/ingest/sessions`, `GET|PUT /api/ingest/sessions/<source>`. PUT is owner only;
  `bofa` is a 400 by name, and the table's check constraint refuses it again in SQL.
- Freshness: `needs_login` on canvas/webassign/drive_statements/backup now reads "... needs you to
  log in again: run tools/connect_session.py <canvas|webassign|drive>" (no trailing full stop, so
  the command copies clean).
- Decided in the build, not by this ticket: PUT MERGES `config` (a re-login never wipes a folder id
  a later ticket stored); `config` keys naming a secret and any key naming a password are refused;
  `kind` is derived from the source, never sent; the script keeps only the site's own cookies
  (the SSO provider's stay on the laptop); browser profiles persist under
  `~/.legion/browser-profiles/<source>`; `invalid_since` is stamped by `run_job` after the job
  unwinds (a stamp inside the job's savepoint was rolled back, found by the test).
- Owed, not code: `LEGION_VAULT_KEY` must reach Cloud Run (a Secret Manager secret, and
  `deploy/cloudrun/_common.py`'s `SECRET_ENV_VARS` does not list it yet); the WebAssign post-login
  signal is reasoned, with Enter as the fallback.
