#!/usr/bin/env python3
"""PreToolUse hook (Edit|Write): the CLAUDE.md section 5 checklist, at the moment a Room file is touched.

Warns, never blocks. Three schema-version incidents are on record (a doc naming v21 at v25, v34 at
v37, v37 at v41), a forgotten `SCHEMA_VERSION` bump disables restore on every backup the app then
produces, and a migration whose ALTER "lied" had to be rebuilt from generated SQL. The rules are in
CLAUDE.md; this puts them in front of the agent while the file is open rather than in a document it
read an hour ago.

Fires on any `.kt` under `app/src/main/java/com/kevin/legion/data/local/`. Exit 0 always; the
checklist goes to the model as `additionalContext` and a one-liner to the user as `systemMessage`.

Shape from everything-claude-code's `scripts/hooks/doc-file-warning.js` (MIT, (c) 2026 Affaan
Mustafa): a PreToolUse path filter that warns with additionalContext and exits 0. The content is
LEGION's section 5. See `.claude/skills/ATTRIBUTION.md`.
"""

import json
import os
import sys

ROOM_DIR = "app/src/main/java/com/kevin/legion/data/local/"

CHECKLIST = (
    "ROOM TOUCH - CLAUDE.md section 5 applies to {rel}. Before this lands:\n"
    "1. Generated SQL VERBATIM. Build first, then copy createSql / index SQL out of the new "
    "app/schemas/.../<version>.json into Migrations.kt. Never hand-write CREATE TABLE.\n"
    "2. Indices declared on the @Entity (indices = [...]), not only in the migration, or a fresh "
    "install and an upgraded install end up with different schemas.\n"
    "3. @Database(version = N) and CarDatabase.SCHEMA_VERSION bumped in lockstep. A forgotten bump "
    "disables backup restore. CarDatabaseSchemaVersionTest fails the build on drift.\n"
    "4. The new schema JSON committed under app/schemas/, produced by kapt, never edited.\n"
    "5. A migration test in app/src/test/.../data/local/ (Migration<N-1>To<N>Test, MigrationTestHelper "
    "against the LIVE JSON) added or extended, and green.\n"
    "6. Additive only. No destructive fallback. No column drop, no table rebuild without a ruling.\n"
    "7. Widening a TEXT enum is NOT a migration: no CHECK on the column means no SQL, no hash change, "
    "no bump. Confirm the JSON is byte-unchanged after a kapt run instead of assuming either way.\n"
    "8. Do not quote the schema version from any document: sed -n '/version = /p' "
    "app/src/main/java/com/kevin/legion/data/local/CarDatabase.kt"
)


def repo_relative(path: str) -> str:
    p = path.replace("\\", "/")
    root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    root = os.path.abspath(root).replace("\\", "/").rstrip("/") + "/"
    ap = os.path.abspath(p).replace("\\", "/") if os.path.isabs(p) else p
    if ap.lower().startswith(root.lower()):
        return ap[len(root):]
    return p[2:] if p.startswith("./") else p


def main() -> int:
    try:
        payload = json.loads(sys.stdin.read() or "{}")
        file_path = (payload.get("tool_input") or {}).get("file_path") or ""
    except Exception:
        return 0
    if not file_path:
        return 0
    rel = repo_relative(file_path)
    if ROOM_DIR not in rel or not rel.endswith(".kt"):
        return 0
    rel = rel[rel.index(ROOM_DIR):]
    print(json.dumps({
        "hookSpecificOutput": {"hookEventName": "PreToolUse", "additionalContext": CHECKLIST.format(rel=rel)},
        "systemMessage": (
            f"Room touch: {os.path.basename(rel)} - section 5 checklist sent (verbatim SQL, indices on "
            "entity, SCHEMA_VERSION lockstep, schema JSON, migration test)"
        ),
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
