"""Does a Django migration do something destructive to the DATABASE?

Both the review-finding gate (`migrations.py`, check (c), a WARN) and the engine ship
(`deploy/ship_checks.py`, a STOP) ask that one question, so one module answers it for both.

Until 2026-11-24 both matched the text `RemoveField|DeleteModel|AlterField|RenameField|RenameModel`
anywhere in the file. That reads the migration's PROSE, not its effect: a
`SeparateDatabaseAndState` with an empty `database_operations` list emits no SQL at all - it only
tells Django's model state what already happened - and was refused with no way out.

**The exemption is a checked-in allow-list of reviewed file CONTENTS, not an analysis of the code**
(the smig-red RED review, rounds 1 to 3, 2026-11-24). Three rounds tried to decide from the source
whether a migration was state-only, by walking it with `ast` and granting the exemption to a "plain
shape". Each round closed the shapes the last one found and each was handed eight more: an
operation parked in a dropped `reverse_sql` subtree and read back out, a name spelled in a
docstring and fetched through `__doc__`, a `bytes` constant the walk declined to read, a `match`
statement's `kwd_attrs`, `builtins` re-exported under a `django.db` path. The premise those rounds
shared is that a static analyser can prove arbitrary Python harmless. It cannot, and the chase does
not converge: every analyser has regions it does not read, and a region it does not read is where
the next payload goes.

So the question this module asks is not "is this code safe" but **"is this exactly the file a human
reviewed"**:

- `tools/checks/state_only_migrations.txt` lists, per line, the repo path and the exact git blob sha
  of each migration a RED reviewer has read and confirmed state-only;
- a migration is exempt from the destructive-NAME match ONLY when its path is listed AND the blob
  sha it is presented with equals the listed one, all 40 hex characters. One changed byte is a
  different blob, so the exemption lapses and the file is judged like any other;
- everything else gets the old text match, on the raw source, unchanged. There is no shape to fit
  and no code to analyse, so none of the hiding places the three rounds found applies: they were all
  places an AST walk does not look, and nothing walks any more.

**Adding a line to that list is itself a RED change** (`tools/checks/*` is RED in
`tools/blast_radius.py`), so a human reviewer grants every exemption in a reviewed diff, under
adversarial review, against the file's content at a named blob. That is the property the three AST
rounds were trying and failing to establish in code.

**The name half is never weaker than what it replaced, by construction.** For any file that is not
a listed (path, blob) pair, the rule starts with `DESTRUCTIVE.search(source)` over the same decoded
string the old gate read, with the same regex. No parse runs before it, so a file that does not
parse, a name hidden in a docstring, a name in a `bytes` constant and a name in a node no walk
visits are all matched exactly as they were in 2026-11-23. The NFKC-normalised text is matched after
it (see `_destructive_name`), which only ever adds a STOP.

**What the name half still does not catch**, so this paragraph is not mistaken for coverage: a name
that is never spelled contiguously anywhere in the file, such as
`getattr(migrations, 'Delete' + 'Model')('X')` or a name assembled at run time from a file or an
imported helper. The text a regex would have to match is never written down. Every one of those
passes the old text match too, so none is a regression, and no text rule can close them: that is the
cost of judging text instead of code, accepted knowingly here in exchange for having no analyser to
walk around. A reviewer listing a blob is the control that remains.

**The SQL check is kept, and it is the one thing here that still reads the tree** (the inversion the
redesign-s1 RED review found, 2026-11-24): a `RunSQL("DROP TABLE ...")` passed the old name match
clean while a state-only migration was refused, which is the rule backwards. Every `str` constant
the walk reaches - that is, every one outside the three skips below - is read for SQL that destroys
rows or a column: `DROP TABLE`, `DROP COLUMN`, `DROP CONSTRAINT`, `DROP SCHEMA`, `DROP DATABASE`,
`DROP TYPE`, `DROP DOMAIN`, `DROP MATERIALIZED VIEW`, `TRUNCATE`, `DELETE FROM`. A type or a domain
takes every dependent column with it when it is dropped, with or without `CASCADE`, which is why
those two are here.

**The SQL check runs on a listed file too**, so a line in the list can never excuse SQL: what a
reviewer confirms by listing a blob is that the file's OPERATIONS are state-only, and the gate still
reads its strings itself. It also fails closed on a file that does not parse.

The SQL half still uses `ast`, and that is safe here in a way the name half was not: its three
skips (a string statement, Django's own `RunSQL(reverse_sql=...)`, a non-`str` constant) can hide
only SQL, and SQL the module never spells out passed the old text match too, so hiding it is not a
regression. A destructive NAME in any of those regions is caught by the raw-text match above, which
is what rounds 1 to 3 kept losing. `_destructive_sql`, `_operation_children`, `_bindings`,
`_from_django` and `_spelled` are byte-unchanged from the round-3 module for that reason.

Three deliberate narrowings in the SQL rule, each measured against the tree rather than guessed:

- **`DROP TRIGGER`, `DROP FUNCTION`, `DROP INDEX` and plain `DROP VIEW` are not destructive here.**
  26 of the engine's migrations carry `drop trigger if exists ... ; create trigger ...` or the same
  for a function: that is how they stay idempotent. They destroy no row and no column, and counting
  them would refuse almost every engine ship. Two things that follow, so the next reader does not
  assume this gate covers them: a view stores nothing, so dropping one breaks its readers loudly
  rather than losing anything, while a `DROP MATERIALIZED VIEW` destroys a stored (if recomputable)
  copy and IS counted; and a `DROP TRIGGER` or `DROP FUNCTION` with no recreate silently removes an
  enforcement guard, which is a guard change for the RED schema review to catch, not data
  destruction for this gate.
- **`reverse_sql` is not read**, on a call whose callee is Django's own `RunSQL`. It runs only on a
  deliberate backward migrate, which no ship does, and a `CREATE TABLE` whose reverse is
  `DROP TABLE` is a correctly written migration. A helper of one's own taking a `reverse_sql`
  argument and running it forward keeps the rule. The skip hides SQL only: an operation parked there
  and read back out (the round-3 blocker) is a NAME, and the raw-text match above sees it wherever
  it sits.
- **`AlterField` stays destructive**, though it is the one name here that can be metadata-only. The
  operation restates the WHOLE field, so one file cannot show that only `help_text` changed: a type
  or a nullability change is spelled the same way. The consequence is real and recorded by the
  smig-red review: a routine field change is a STOP with no hatch. **The state-only list is NOT
  that hatch.** It is for migrations that run no DDL at all, and an `AlterField` that reaches the
  database is not one, however routine; a reviewer asked to list one refuses. A hatch for a real
  `AlterField` is Kevin's call (round 1 sketched `--allow-destructive`, bound to his own ship
  invocation rather than to a file an agent can write) and is deliberately not built here.

Not covered by the SQL half, and deliberately not chased: SQL the module never spells out (a
statement built at runtime by `+`, `%`, `.format`, `.join` or `bytes.decode`, a `bytes` literal, a
comment inside the statement, a string read from a file, an imported helper's SQL, an ORM
`.objects.all().delete()` inside `RunPython`). Every one of those passes the old text match too AS
SQL, because the text it would have to match is never written down, so none is a regression.

Stdlib only, pure apart from reading the list file once, and tested in tools/checks/test_checks.py
and server/tests/test_ship_checks.py.
"""

from __future__ import annotations

import ast
import re
import unicodedata
from pathlib import Path

# The operations that drop or rewrite a column, a table or a constraint when they reach the database.
DESTRUCTIVE = re.compile(r"RemoveField|DeleteModel|AlterField|RenameField|RenameModel")
# The same in SQL: what destroys rows or a column. A trigger, function, index or plain view is not
# here, because dropping and recreating one is how 26 of the engine's migrations stay idempotent.
DESTRUCTIVE_SQL = re.compile(
    r"\bDROP\s+(TABLE|COLUMN|CONSTRAINT|SCHEMA|DATABASE|TYPE|DOMAIN|MATERIALIZED\s+VIEW)\b"
    r"|\bTRUNCATE\b|\bDELETE\s+FROM\b"
    # Postgres lets `ALTER TABLE w DROP g` drop a column with the word COLUMN left out, so a DROP
    # anywhere in an ALTER TABLE statement counts unless it is one of the four harmless ones
    # (`DROP NOT NULL`, `DROP DEFAULT`, `DROP IDENTITY`, `DROP EXPRESSION`). `[^;]*?` keeps it
    # inside the one statement, so a later `drop trigger` in the same string is not dragged in.
    r"|\bALTER\s+TABLE\b[^;]*?\bDROP\s+(?!NOT\s+NULL\b|DEFAULT\b|IDENTITY\b|EXPRESSION\b)",
    re.IGNORECASE,
)
# The backward direction: it runs only on a deliberate `migrate <app> <earlier>`, never on a ship.
REVERSE = "reverse_sql"
RUN_SQL = "RunSQL"
DJANGO = "django.db"

# The reviewed state-only migrations, by path and exact blob. Repo-relative for the messages; the
# file is found beside this module, so neither the ship's cwd nor the check's matters.
LIST_PATH = "tools/checks/state_only_migrations.txt"
_LIST_FILE = Path(__file__).resolve().parent / "state_only_migrations.txt"
BLOB = re.compile(r"^[0-9a-f]{40}$")


def parse_state_only(text: str) -> dict[str, str]:
    """`<repo path> <40-hex blob>` per line (blank lines and `#` comments ignored) -> {path: blob}.

    A line that is not that shape, or a path listed twice, RAISES and names the line: a typo would
    otherwise silently drop an exemption, and the operator reading a STOP needs to know which."""
    out: dict[str, str] = {}
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2 or not BLOB.match(parts[1]):
            raise ValueError(
                f"{LIST_PATH} line {number}: not '<repo path> <40-hex blob sha>': {line!r}"
            )
        if parts[0] in out:
            raise ValueError(f"{LIST_PATH} line {number}: {parts[0]} is listed twice")
        out[parts[0]] = parts[1]
    return out


STATE_ONLY = parse_state_only(_LIST_FILE.read_text(encoding="utf-8"))


def database_destructive(source: str, path: str = "", blob: str = "") -> str | None:
    """What makes this migration destructive to the database, or None when nothing does.

    `path` and `blob` are the file's repo path and its exact git blob sha, and together they are the
    only thing that can earn the state-only exemption. Called with neither (the reviewers' probe
    harnesses do), nothing is exempt, which is the strict direction.

    The blob must come from git, never from hashing `source`: both callers decode lossily
    (`errors="replace"`), so two different files can arrive as one string.

    The reason is for an operator reading a STOP line, so it names the operation it found and, when
    a name is found, says why the exemption did not apply."""
    reviewed = bool(path) and bool(blob) and STATE_ONLY.get(path) == blob
    if not reviewed:
        name = _destructive_name(source)
        if name:
            return f"{name}{_why_not_exempt(path, blob)}"
    # The SQL check runs on a listed file too: listing a blob says the OPERATIONS are state-only,
    # and the gate still reads the file's strings itself. `reviewed` is only read for the message.
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        return f"the file does not parse ({exc.msg}), so its operations cannot be read"
    bindings = _bindings(tree)
    stack: list[ast.AST] = [tree]
    while stack:
        node = stack.pop()
        sql = _destructive_sql(node)
        if sql:
            listed = " (listed state-only, but the SQL it runs still counts)" if reviewed else ""
            return f"SQL running {sql}{listed}"
        stack.extend(_operation_children(node, bindings))
    return None


def _destructive_name(source: str) -> str | None:
    """The destructive operation name this file spells out, or None.

    The raw source is matched FIRST and with the unchanged regex, which is what makes this never
    weaker than the gate it replaced. The NFKC-normalised text is then matched as well, because
    CPython normalises identifiers before it compiles them: `migrations.ⅮeleteModel` really is
    `DeleteModel` at run time, and round 3's AST walk caught that only because `ast` had already
    normalised it. Matching both keeps that, and can only ever add a STOP."""
    for text in (source, unicodedata.normalize("NFKC", source)):
        m = DESTRUCTIVE.search(text)
        if m:
            return m.group(0)
    return None


def _why_not_exempt(path: str, blob: str) -> str:
    """Why the state-only list did not excuse this name, for the operator reading the STOP."""
    if not (path and blob):
        return ""  # judged without a path or a blob: there was nothing to look up
    if listed := STATE_ONLY.get(path):
        return (
            f"; {LIST_PATH} lists this path at blob {listed}, not the {blob} being shipped, "
            "so the review of that content does not cover this file"
        )
    return (
        f"; not listed in {LIST_PATH} (if it is state-only, a RED review adds the line "
        f"'{path} {blob}' there)"
    )


def _destructive_sql(node: ast.AST) -> str | None:
    """The SQL this string runs that destroys rows or a column, normalised for the message."""
    if not (isinstance(node, ast.Constant) and isinstance(node.value, str)):
        return None
    m = DESTRUCTIVE_SQL.search(node.value)
    if not m:
        return None
    found = " ".join(m.group(0).split()).upper()
    # The ALTER branch spans the statement, so report the shape rather than the table's name.
    return "ALTER TABLE ... DROP" if found.startswith("ALTER TABLE") else found


def _operation_children(node: ast.AST, bindings: dict[str, str]) -> list[ast.AST]:
    """This node's children that are code a forward migrate runs. Dropped: a string statement (a
    docstring, or a string used as a comment) and the `reverse_sql` of Django's own `RunSQL`."""
    if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant):
        if isinstance(node.value.value, str):
            return []
    reverse = (
        isinstance(node, ast.Call)
        and _spelled(node.func) == RUN_SQL
        and _from_django(node.func, bindings)
    )
    return [
        child
        for child in ast.iter_child_nodes(node)
        if not (isinstance(child, ast.keyword) and reverse and child.arg == REVERSE)
    ]


def _bindings(tree: ast.AST) -> dict[str, str]:
    """Each name an import binds, mapped to the dotted module it came from.

    Empty when the file's imports cannot be read name for name - a `import *`, a relative import, a
    name bound to two different sources, or a name an assignment, a `def`, a `class` or an argument
    also binds - so every exemption that asks where a name came from is withdrawn.

    Two imports that bind one name to the SAME source are not ambiguous, and they are the norm:
    `import django.db.models.deletion` beside `import django.utils.timezone` binds `django` twice,
    in a good quarter of the engine's own migrations.
    """
    bindings: dict[str, str] = {}
    rebound: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            if isinstance(node, ast.ImportFrom) and (node.level or not node.module):
                return {}  # relative: what it holds is not readable here
            for alias in node.names:
                if alias.name == "*":
                    return {}
                if isinstance(node, ast.ImportFrom):
                    name, source = alias.asname or alias.name, f"{node.module}.{alias.name}"
                elif alias.asname:
                    name, source = alias.asname, alias.name
                else:
                    name = source = alias.name.split(".")[0]
                if bindings.get(name, source) != source:
                    return {}
                bindings[name] = source
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            rebound.add(node.name)
        elif isinstance(node, ast.arg):
            rebound.add(node.arg)
        elif isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Del)):
            rebound.add(node.id)
    return {} if rebound & set(bindings) else bindings


def _spelled(func: ast.AST) -> str:
    """The name this callee is written as: `X` or the `X` of `anything.X`."""
    if isinstance(func, ast.Name):
        return func.id
    if isinstance(func, ast.Attribute):
        return func.attr
    return ""


def _from_django(func: ast.AST, bindings: dict[str, str]) -> bool:
    """Whether this callee is a name imported from `django.db`, or an attribute of one. Any other
    receiver - a module of one's own, a local, a longer attribute chain - is not Django's."""
    if isinstance(func, ast.Name):
        source = bindings.get(func.id, "")
    elif isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name):
        source = bindings.get(func.value.id, "")
    else:
        return False
    return source == DJANGO or source.startswith(f"{DJANGO}.")
