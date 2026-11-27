---
map: django-engine
ticket: "12"
title: "Every pytest run leaves a connection on test_postgres and breaks the next one"
type: build
status: resolved
status-detail: "2026-11-26: not a code leak. The idle session is the Supabase pooler holding the upstream connection after the client closes it. Step 1 (--reuse-db) withdrawn. No code change."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Every pytest run leaves a connection on test_postgres and breaks the next one

Every `server/` pytest run leaves one idle session on `test_postgres`, so Django's teardown
`DROP DATABASE` fails, and the **next** run dies at creation with
`SystemExit: 2 / database "test_postgres" already exists`. Reproduced across a whole night; present
in the very first baseline run, before any edit, so it is not caused by tonight's work.

## Resolution (2026-11-26, #143)

**Root cause is not in this repo.** Reasoned from the evidence below, since the remote test DB is
off limits to agents: the run that dies is the NEXT one, so a session must outlive the pytest
process. A Django connection left open dies with the process. Only a pooler between client and
server (the Supabase pooler against the remote `test_postgres`) keeps an upstream session after the
client disconnects. That is what the 2-3 minute wait and the 11-attempt retry loop looked like.

Evidence:

- **Django path, traced.** `_create_test_db` on "already exists" runs `DROP DATABASE`, and a failed
  drop is `sys.exit(2)`. pytest-django downgrades a failed teardown drop to a `PytestWarning`
  "Error when trying to teardown test databases".
- **Full suite, checked.** Server CI run 45926634643 (dev, 6291 passed): zero such warnings. On
  direct Postgres the whole suite tears its database down cleanly.
- **Threaded paths, checked locally (throwaway Postgres 17, port 55143).** A probe before teardown
  over `test_tool_deadline.py`, then `test_demo_spares.py` + `test_ingest_runs.py`: at most one
  backend (the main connection, closed by teardown), no live threads, no open Django wrapper, and
  `test_<db>` was dropped after each run. Three files only, not the suite.

**Step 1, `--reuse-db` as default, is withdrawn.** It breaks every test: the legacy tables are
built inside `django_db_setup`, which runs only on creation (`legion-no-reuse-db`, 2026-09-08), and
AGENT-RULES forbid it. The `tests/conftest.py` docstring that says a reused database is safe is
wrong on that point; a comment fix, left for a conftest change that has another reason to be
RED. Step 3 (a session fixture that kills stray backends) stays rejected.

**Where the fix already is.** Agents run local pytest on a throwaway Postgres (`pt.sh`), never the
shared remote test DB, and CI uses a `postgres:16` service container. Neither has a pooler, so the
symptom cannot occur there. Anyone who still points pytest at the remote pooler waits out the idle
session or uses another database name.
