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

It fails closed. A file that does not parse, a `database_operations` that is positional, absent, a
variable or non-empty, and `**kwargs` are all judged by the operations as written, so a real
`DeleteModel` or a `RunSQL('DROP ...')` beside state operations is still refused.

Stdlib only, pure, and tested in tools/checks/test_checks.py and server/tests/test_ship_checks.py.
"""

from __future__ import annotations

import ast
import re

# The operations that drop or rewrite a column, a table or a constraint when they reach the database.
DESTRUCTIVE = re.compile(r"RemoveField|DeleteModel|AlterField|RenameField|RenameModel")
STATE_AND_DB = "SeparateDatabaseAndState"


def database_destructive(source: str) -> str | None:
    """What makes this migration destructive to the database, or None when nothing does.

    The reason is for an operator reading a STOP line, so it names the operation it found."""
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        return f"the file does not parse ({exc.msg}), so its operations cannot be read"
    stack: list[ast.AST] = [tree]
    while stack:
        node = stack.pop()
        for token in _tokens(node):
            m = DESTRUCTIVE.search(token)
            if m:
                return m.group(0)
        stack.extend(_operation_children(node))
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


def _operation_children(node: ast.AST) -> list[ast.AST]:
    """This node's children that are code the database runs: a string statement (a docstring, or a
    string used as a comment) and the state-only subtree are dropped."""
    if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant):
        if isinstance(node.value.value, str):
            return []
    state = _state_only_operations(node)
    if state is not None:
        return [
            child
            for child in ast.iter_child_nodes(node)
            if not (isinstance(child, ast.keyword) and child.value is state)
        ]
    return list(ast.iter_child_nodes(node))


def _state_only_operations(node: ast.AST) -> ast.AST | None:
    """The `state_operations` value of a `SeparateDatabaseAndState` call that has an empty literal
    `database_operations` keyword, or None for any other node.

    None for a positional, absent, variable or non-empty `database_operations`, and for `**kwargs`:
    the call then may run DDL, so none of it is exempt.
    """
    if not isinstance(node, ast.Call) or _called(node.func) != STATE_AND_DB:
        return None
    if node.args:  # positional: Django's own docs pass these by keyword, so judge it as written
        return None
    database = state = None
    for kw in node.keywords:
        if kw.arg is None:  # **kwargs: what it passes cannot be read here
            return None
        if kw.arg == "database_operations":
            database = kw.value
        elif kw.arg == "state_operations":
            state = kw.value
    if not isinstance(database, (ast.List, ast.Tuple)) or database.elts:
        return None
    return state


def _called(func: ast.AST) -> str:
    if isinstance(func, ast.Name):
        return func.id
    if isinstance(func, ast.Attribute):
        return func.attr
    return ""
