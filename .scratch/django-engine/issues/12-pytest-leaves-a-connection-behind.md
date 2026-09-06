---
map: django-engine
ticket: "12"
title: "Every pytest run leaves a connection on test_postgres and breaks the next one"
type: build
status: open
status-detail: "Found 2026-09-06; cost one agent four runs. Pre-existing, not caused by any change that night."
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

## Fix, in this order

1. **`--reuse-db` as the default** in `server/pyproject.toml`'s `addopts`, with `--create-db`
   documented for schema work. It sidesteps the drop entirely and saves the 30-60 s of database
   creation per run. `tests/conftest.py` was already written for this: its schema SQL is
   `create or replace` / `if not exists` and its docstring says in as many words that running it
   twice against a reused database changes nothing.
2. **Find the connection that leaks**, as the real fix, in the same ticket but not blocking step 1.
   The evidence points at a Django connection never closed at teardown rather than a stuck test: a
   retry loop needed up to 11 attempts over about 22 seconds and then succeeded, which is the shape
   of a server-side idle timeout reaping an abandoned connection.
3. **Not** a session fixture that terminates stray backends. That treats the symptom and gives the
   suite a hard dependency on admin privileges it does not otherwise need.
