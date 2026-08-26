#!/usr/bin/env python3
"""Parse-check every committed Supabase migration.

Why this exists. The backend-erp arc (.scratch/backend-erp/) puts the reconciliation gate, rule 7
supersession and the household RLS into SQL. None of that runs on this machine: there is no local
Postgres, no Docker and no psql here, so the only thing standing between a typo and a failed
`supabase db push` against a live project is a parser.

`pglast` wraps libpg_query, which is Postgres's OWN grammar, so a file that parses here would parse
on the server.

WHAT THIS DOES NOT CHECK, and it matters:
  - **plpgsql function bodies.** To the outer grammar a function body is just a string literal.
    Postgres parses plpgsql at first execution (or at CREATE with check_function_bodies on), so a
    syntax error inside `private.forbid_mutation_of_facts()` will pass here and fail there.
  - Anything semantic: whether a referenced table, column, type or function actually exists,
    whether a policy makes sense, whether an index helps.
  - Whether the migration is idempotent, which this repo's migrations are by convention.

So a green run means "the statements are well formed", never "this will apply cleanly".

Usage:
    python tools/sql_check.py            # check, exit non-zero on any failure
    python tools/sql_check.py --list     # also list each statement type per file

Install the one dependency with:  pip install pglast
"""

import glob
import os
import sys

MIGRATIONS = "supabase/migrations/*.sql"


def main() -> int:
    try:
        import pglast
    except ImportError:
        print("pglast is not installed. Run: pip install pglast", file=sys.stderr)
        return 2

    show_list = "--list" in sys.argv
    files = sorted(glob.glob(MIGRATIONS))

    if not files:
        print(f"No migrations found at {MIGRATIONS}")
        return 0

    failures = 0
    total_statements = 0

    for path in files:
        name = os.path.basename(path)
        with open(path, encoding="utf-8") as handle:
            sql = handle.read()

        try:
            statements = pglast.parse_sql(sql)
        except Exception as exc:  # pglast raises its own ParseError type
            failures += 1
            print(f"FAIL  {name}")
            print(f"      {exc}")
            continue

        total_statements += len(statements)
        print(f"ok    {name}  ({len(statements)} statements)")

        if show_list:
            for statement in statements:
                node = statement.stmt
                print(f"        {type(node).__name__}")

    print()
    if failures:
        print(f"{failures} file(s) failed to parse.")
        return 1

    print(f"{len(files)} migration(s), {total_statements} statements, all parse.")
    print("Note: plpgsql function bodies are NOT checked here. See this file's docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
