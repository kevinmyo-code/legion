"""Check (c): schema files are declared, and existing ones are never touched.

Review findings it automates: a Room schema version with no registered migration (the app crashes on
upgrade, and no unit test walks the chain), an edit to a migration that already shipped, and two
branches each adding `0005` to the same Django app.

Django (`server/<app>/migrations/NNNN_*.py`):
  - FAIL: an existing migration modified or deleted (CLAUDE.md section 5: additive only);
  - FAIL: an added migration whose number another file in the same app already has;
  - WARN: an added migration holding a destructive operation (the engine ship refuses it later).
  Declaration itself is Django's own graph, checked by `makemigrations --check` in server.yml.
Room (`app/schemas/<db>/N.json`, `headunit/app/schemas/<db>/N.json`):
  - FAIL: an added version N > 1 whose `MIGRATION_<N-1>_<N>` is not inside an `.addMigrations(...)` call
    in that module's CarDatabase.kt (a mention in a comment does not count);
Limit: a Django number clash is seen only against this branch's own tree; two unmerged branches that
each add the same number both pass until the second one is rebased onto dev.
  - FAIL: an existing version's schema JSON modified or deleted.
--all checks the whole tree: every Room version registered, no Django number shared.
"""

from __future__ import annotations

import re

from common import FAIL, WARN, Finding
from gitdiff import Change

NAME = "migrations"
DJANGO = re.compile(r"^server/([^/]+)/migrations/(\d{4})_[A-Za-z0-9_]+\.py$")
ROOM = re.compile(r"^((?:headunit/)?app)/schemas/([^/]+)/(\d+)\.json$")
DESTRUCTIVE = re.compile(r"RemoveField|DeleteModel|AlterField|RenameField|RenameModel")


def registered(db_source: str) -> str:
    """The text inside every `.addMigrations(...)` call, comments removed: a name mentioned only in a
    comment elsewhere in CarDatabase.kt is not a registration."""
    text = re.sub(r"//[^\n]*|/\*.*?\*/", "", db_source, flags=re.S)
    out = []
    for m in re.finditer(r"\.addMigrations\s*\(", text):
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += {"(": 1, ")": -1}.get(text[i], 0)
            i += 1
        out.append(text[m.end():i])
    return "\n".join(out)


def car_database(change: Change, module: str) -> str | None:
    pat = re.compile(rf"^{re.escape(module)}/src/main/java/.+/data/local/CarDatabase\.kt$")
    for p in change.head_files:
        if pat.match(p):
            return p
    return None


def check(change: Change) -> list[Finding]:
    out: list[Finding] = []
    django_numbers: dict[tuple[str, str], list[str]] = {}
    for p in change.head_files:
        m = DJANGO.match(p)
        if m:
            django_numbers.setdefault((m.group(1), m.group(2)), []).append(p)

    for path, letter in sorted(change.status.items()):
        m = DJANGO.match(path)
        if m:
            if letter != "A":
                verb = "deleted" if letter == "D" else "modified"
                out.append(Finding(FAIL, NAME, path, 0, f"existing migration {verb}: add a new one instead"))
                continue
            twins = [p for p in django_numbers.get((m.group(1), m.group(2)), []) if p != path]
            if twins:
                out.append(Finding(FAIL, NAME, path, 0,
                                   f"migration number {m.group(2)} already used in {m.group(1)}: {', '.join(twins)}"))
            if DESTRUCTIVE.search(change.read(path) or ""):
                out.append(Finding(WARN, NAME, path, 0, "destructive operation: the engine ship refuses it"))
            continue

        r = ROOM.match(path)
        if r:
            module, db, version = r.group(1), r.group(2), int(r.group(3))
            if letter != "A":
                verb = "deleted" if letter == "D" else "modified"
                out.append(Finding(FAIL, NAME, path, 0, f"schema of shipped version {version} {verb}"))
                continue
            if version <= 1 or _is_oldest(change, module, db, version):
                continue
            name = f"MIGRATION_{version - 1}_{version}"
            db_file = car_database(change, module)
            if db_file is None:
                out.append(Finding(FAIL, NAME, path, 0, f"no CarDatabase.kt found in {module} to register {name}"))
            elif not re.search(rf"\b{name}\b", registered(change.read(db_file) or "")):
                out.append(Finding(FAIL, NAME, path, 0, f"{name} is not registered in {db_file}"))
    return out


def _is_oldest(change: Change, module: str, db: str, version: int) -> bool:
    """True for the first exported version of a database (its schema history starts there)."""
    prefix = f"{module}/schemas/{db}/"
    versions = []
    for p in change.head_files:
        if p.startswith(prefix):
            m = ROOM.match(p)
            if m:
                versions.append(int(m.group(3)))
    return bool(versions) and version == min(versions)
