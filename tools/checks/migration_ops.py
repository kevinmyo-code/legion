"""Does a Django migration do something destructive to the DATABASE?

Both the review-finding gate (`migrations.py`, check (c), a WARN) and the engine ship
(`deploy/ship_checks.py`, a STOP) ask that one question, so one module answers it for both.

Until 2026-11-24 both matched the text `RemoveField|DeleteModel|AlterField|RenameField|RenameModel`
anywhere in the file. That reads the migration's PROSE, not its effect: a
`SeparateDatabaseAndState` with an empty `database_operations` list emits no SQL at all - it only
tells Django's model state what already happened - and was refused with no way out, as was any
migration whose docstring named an operation.

So the rule is now about the database:

- the migration's operations are read with `ast`, and a destructive name anywhere in them is
  destructive, exactly as before (an alias, an attribute, a helper's body and a string all count);
- comments and docstrings are not operations and are skipped;
- the ONE exemption is the `state_operations` list of a
  `SeparateDatabaseAndState(database_operations=[], state_operations=[...])` call: with no database
  operations there is no DDL, so nothing there can drop a column or a table.

**The exemption is granted by the WHOLE FILE'S SHAPE, not by spotting a call** (the smig-red RED
review, 2026-11-24: eight shapes reached `operations` with a real `DeleteModel` while one deny rule
per smuggling route was being added, and that chase does not converge). The invariant wanted is
that nothing built inside the exempt list is reachable from outside it, so the exemption is an
allow-list, and a file that does not fit it is judged by its operations exactly as the old text
match judged them. All five must hold:

1. the module body holds only a docstring, `import` / `from ... import` statements and one
   `class Migration` with no decorator, no `metaclass=` keyword and every base a name taken from
   `django.db` (a decorator, a class keyword and a base all run code around the class body and can
   rewrite `operations`), and every name an import binds is bound once, never rebound by an
   assignment, a `def`, a `class` or an argument, and never by `import *`;
2. the class body holds only a docstring and single-name assignments, `operations` is assigned
   exactly once, and every other assignment's value is a literal (so nothing can mutate
   `operations` or rebind a name the next rule trusts);
3. the exempt `SeparateDatabaseAndState` call is a DIRECT element of that `operations` literal, its
   callee is a name taken from `django.db`, it takes no positional argument and no keyword but the
   two, and both are literal lists or tuples with `database_operations` empty;
4. every element of `state_operations` is a call whose callee is a name taken from `django.db`;
5. nowhere in the file is there a `lambda` or a `:=`, and every call in the file is on the
   allow-list below.

**Rule 5 is an allow-list on calls, not a ban list** (the smig-red2 RED review, 2026-11-24). It
used to say only that a call's callee is rooted at a name an import bound, on the reasoning that a
builtin is not import-bound; one `import builtins` line, or a `django.db`-pathed re-export of that
module, makes every builtin import-rooted, which is how `setattr` reached
`SeparateDatabaseAndState` beside the exempt call. So a call is allowed only when it is:

- an OPERATION CONSTRUCTOR: `<name>.<Attr>(...)` where the name is bound to EXACTLY `django.db`,
  `django.db.migrations` or `django.db.models`. An exact set, not a `django.db.` prefix, because
  anything re-exported under such a path would otherwise count as Django's; or
- a HELPER nested inside an operation's ARGUMENT, bound from a module that is not in the standard
  library (`checklists/0002_household.py` passes its constraint name through its own
  `household_unique_name(...)`, which is why the second clause exists and the rule is not simply
  "no non-Django calls").

Every DIRECT element of `operations`, and of an exempt `state_operations`, must be an operation
constructor, so nothing else runs beside the exempt call.

What is exempt is then the CALLEE of each state operation and nothing else: an element's arguments
are read like any other code, so an operation stashed inside one is still refused.

**Outside the plain shape the old text match still applies, so this is never weaker than what it
replaced** (smig-red2 F1-r2). A file the shape rules refuse is held to `DESTRUCTIVE.search(source)`
as well as to the AST walk, because a walk reads NODES and a node it never visits, or a constant it
declines to read, is invisible: a `bytes` constant, a docstring, a `match` statement's `kwd_attrs`
and the dropped `reverse_sql` subtree each hid a destructive operation NAME that the text match
caught. The invariant is by construction rather than one rule per hiding place. It costs nothing:
the two files the exemption exists for are both plain-shape, and over the 154 tracked migrations
plus `homes/0003` the text fallback adds no flag that the walk does not already make.

**It fails closed.** A file that does not parse, a positional, absent, variable or non-empty
`database_operations`, `**kwargs`, a locally defined function of the same name, a name that could
have come from somewhere else: every one of them is judged, so a real `DeleteModel` or a
`RunSQL('DROP ...')` beside state operations is still refused.

**The SQL counts too** (the inversion the redesign-s1 RED review found, 2026-11-24): a
`RunSQL("DROP TABLE ...")` passed clean while a state-only migration was refused, which is the rule
backwards. So every string the migration holds, outside a docstring and outside the exempt callees,
is read for SQL that destroys data or a column: `DROP TABLE`, `DROP COLUMN`, `DROP CONSTRAINT`,
`DROP SCHEMA`, `DROP DATABASE`, `DROP TYPE`, `DROP DOMAIN`, `DROP MATERIALIZED VIEW`, `TRUNCATE`,
`DELETE FROM`. A type or a domain takes every dependent column with it when it is dropped, with or
without `CASCADE`, which is why those two are here (smig-red F2).

Three deliberate narrowings, each measured against the tree rather than guessed:

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
  argument and running it forward keeps the rule. The skip is right for SQL and is NOT safe in
  general: the dropped subtree is somewhere to park an operation CLASS and read back out
  (`S = migrations.RunSQL('select 1', reverse_sql=migrations.DeleteModel)`, then `S.reverse_sql`
  called in `operations`), which the old text match DID stop. Such a file is not the plain shape,
  so the text fallback above stops it.
- **`AlterField` stays destructive**, though it is the one name here that can be metadata-only. The
  operation restates the WHOLE field, so one file cannot show that only `help_text` changed: a type
  or a nullability change is spelled the same way. The consequence is real and recorded by the
  smig-red review - a routine field change is a STOP with no hatch - and the hatch is Kevin's call,
  not this module's.

Not covered, and deliberately not chased: SQL the module never spells out (a statement built at
runtime by `+`, `%`, `.format`, `.join` or `bytes.decode`, a comment inside the statement, a string
read from a file, an imported helper's SQL, an ORM `.objects.all().delete()` inside `RunPython`, a
`reverse_sql` read back off a call, anything reached through `gc`, `sys.modules` or a dunder chain
on an imported module). Every one of those passes the old text match too AS SQL, because the text
it would have to match is never written down, so none is a regression; writing rules for them would
not converge either. The same shapes carrying an operation NAME are a different matter - the name IS
written down, so the text match caught it - and those are stopped by the fallback above, not by this
list. Nor is a second module covered: a `SeparateDatabaseAndState` re-exported from a module of
one's own is not Django's here, and an attacker who can add a module needs no exemption at all.

Stdlib only, pure, and tested in tools/checks/test_checks.py and server/tests/test_ship_checks.py.
"""

from __future__ import annotations

import ast
import re
import sys

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
STATE_AND_DB = "SeparateDatabaseAndState"
DJANGO = "django.db"
# Where an operation constructor may be taken from inside the plain shape, EXACTLY. Not a
# `django.db.` prefix: `from django.db.migrations.serializer import builtins` re-exports the
# builtins module under such a path, which is how `setattr` got in (smig-red2 F1-r2 payload F).
DJANGO_OPERATION_MODULES = frozenset({DJANGO, f"{DJANGO}.migrations", f"{DJANGO}.models"})


def database_destructive(source: str) -> str | None:
    """What makes this migration destructive to the database, or None when nothing does.

    The reason is for an operator reading a STOP line, so it names the operation it found."""
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        return f"the file does not parse ({exc.msg}), so its operations cannot be read"
    bindings = _bindings(tree)
    exempt, plain = _exempt_callees(tree, bindings)
    stack: list[ast.AST] = [tree]
    while stack:
        node = stack.pop()
        if id(node) in exempt:
            continue
        for token in _tokens(node):
            m = DESTRUCTIVE.search(token)
            if m:
                return m.group(0)
        sql = _destructive_sql(node)
        if sql:
            return f"SQL running {sql}"
        stack.extend(_operation_children(node, bindings))
    if not plain:
        # The walk reads nodes, and a node it never visits hides a name the old text match saw. Only
        # the plain shape earns an exemption, so every other file is still held to that match.
        m = DESTRUCTIVE.search(source)
        if m:
            return m.group(0)
    return None


def _tokens(node: ast.AST) -> tuple[str, ...]:
    """The names this one node spells out: what it calls, imports, defines or holds as a string."""
    if isinstance(node, ast.Name):
        return (node.id,)
    if isinstance(node, ast.Attribute):
        return (node.attr,)
    if isinstance(node, ast.alias):
        return (node.name, node.asname or "")
    if isinstance(node, ast.keyword):
        return (node.arg or "",)
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        return (node.name,)
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return (node.value,)
    return ()


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


def _exempt_callees(tree: ast.AST, bindings: dict[str, str]) -> tuple[frozenset[int], bool]:
    """The callee nodes of the state operations this file's shape makes exempt, by `id`, and
    whether the file IS the plain state-only shape the module docstring sets out.

    The exemption is an allow-list, so anything else is judged by its operations AND by the old
    text match, which is what the second half of the pair is for: a file that is not plain earns
    neither the exemption nor the narrowing that came with it.
    """
    if any(isinstance(node, (ast.Lambda, ast.NamedExpr)) for node in ast.walk(tree)):
        return frozenset(), False
    klass = _migration_class(tree, bindings)
    if klass is None:
        return frozenset(), False
    operations = _operations_literal(klass)
    if operations is None:
        return frozenset(), False
    exempt: set[int] = set()
    constructors: set[int] = set()
    for element in operations.elts:
        if not _operation_constructor(element, bindings):
            return frozenset(), False  # something else runs beside the exempt call
        constructors.add(id(element))
        state = _state_only_operations(element, bindings)
        if state is None:
            continue  # an ordinary operation, judged by its own name
        for op in state.elts:
            if not _operation_constructor(op, bindings):
                return frozenset(), False  # not a django.db operation: the exemption is off
            constructors.add(id(op))
            exempt.add(id(op.func))
    if not _only_allowed_helpers(tree, bindings, constructors):
        return frozenset(), False
    return frozenset(exempt), True


def _operation_constructor(node: ast.AST, bindings: dict[str, str]) -> bool:
    """Whether this is a call on `<name>.<Attr>`, with the name bound to exactly one of Django's
    three operation modules: the only call shape allowed to BE an operation (rule 5)."""
    if not isinstance(node, ast.Call):
        return False
    func = node.func
    if not (isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name)):
        return False
    return bindings.get(func.value.id, "") in DJANGO_OPERATION_MODULES


def _only_allowed_helpers(
    tree: ast.AST, bindings: dict[str, str], constructors: set[int]
) -> bool:
    """Whether every remaining call in the file - each one nested inside an operation's argument,
    since rules 1 and 2 leave nowhere else for a call to sit - is an operation constructor or a
    helper from a module outside the standard library (rule 5's second clause).

    The stdlib ban is what shuts `builtins.setattr` and friends, however they were imported: a
    `django.db`-pathed re-export of `builtins` is still the `builtins` module.
    """
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or id(node) in constructors:
            continue
        if _operation_constructor(node, bindings):
            continue
        root = node.func
        while isinstance(root, ast.Attribute):
            root = root.value
        if not isinstance(root, ast.Name) or root.id not in bindings:
            return False
        if set(bindings[root.id].split(".")) & sys.stdlib_module_names:
            return False
    return True


def _migration_class(tree: ast.AST, bindings: dict[str, str]) -> ast.ClassDef | None:
    """The one `class Migration` of a module whose body holds nothing but a docstring, imports and
    that class, or None when the module is any other shape (rule 1).

    The class header is part of the shape: a decorator, a `metaclass=` (or any other class keyword)
    and a base of its own all run code around the body, and each of them can rewrite `operations`
    after the list the rules below read.
    """
    if not isinstance(tree, ast.Module):
        return None
    found = None
    for node in tree.body:
        if _is_docstring(node) or isinstance(node, (ast.Import, ast.ImportFrom)):
            continue
        if isinstance(node, ast.ClassDef) and node.name == "Migration" and found is None:
            if node.decorator_list or node.keywords:
                return None
            if not node.bases or not all(_from_django(base, bindings) for base in node.bases):
                return None
            found = node
            continue
        return None
    return found


def _operations_literal(klass: ast.ClassDef) -> ast.List | ast.Tuple | None:
    """The class's `operations` list or tuple, when the class body holds nothing but a docstring and
    single-name assignments, `operations` is assigned once, and every other value is a literal
    (rule 2). None otherwise."""
    operations = None
    for node in klass.body:
        if _is_docstring(node):
            continue
        if not (
            isinstance(node, ast.Assign)
            and len(node.targets) == 1
            and isinstance(node.targets[0], ast.Name)
        ):
            return None
        if node.targets[0].id == "operations":
            if operations is not None:
                return None  # assigned twice: the second one is not readable from the first
            operations = node.value
        else:
            try:
                ast.literal_eval(node.value)
            except (ValueError, TypeError, SyntaxError, MemoryError, RecursionError):
                return None  # it could read, mutate or rebind what the next rules trust
    return operations if isinstance(operations, (ast.List, ast.Tuple)) else None


def _state_only_operations(
    element: ast.AST, bindings: dict[str, str]
) -> ast.List | ast.Tuple | None:
    """The `state_operations` of a `SeparateDatabaseAndState` call taken from `django.db` that has
    an empty literal `database_operations` and no other argument, or None for anything else
    (rule 3)."""
    if not isinstance(element, ast.Call):
        return None
    if _spelled(element.func) != STATE_AND_DB or not _from_django(element.func, bindings):
        return None
    if element.args:  # positional: Django's own docs pass these by keyword, so judge it as written
        return None
    database = state = None
    for kw in element.keywords:
        if kw.arg == "database_operations":
            database = kw.value
        elif kw.arg == "state_operations":
            state = kw.value
        else:
            return None  # `**kwargs`, or an argument Django's operation does not take
    if not isinstance(database, (ast.List, ast.Tuple)) or database.elts:
        return None
    return state if isinstance(state, (ast.List, ast.Tuple)) else None


def _is_docstring(node: ast.AST) -> bool:
    return (
        isinstance(node, ast.Expr)
        and isinstance(node.value, ast.Constant)
        and isinstance(node.value.value, str)
    )


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
