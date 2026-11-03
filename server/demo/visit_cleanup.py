"""Deleting one recruiter visit's household whole (recruiter-demo ticket 13, ADR 0069).

`manage.py prune_demo_visitors` is the caller. A visit's household is a seeded
copy of the demo data that only its visitor ever saw or changed, so when the
visit ends every row of it goes: the gated money rows too, which the database
permits only because the household carries `is_demo_visit`
(`household/demo_visit_sql.py`).

`Household.delete()` cannot do this: nearly every household foreign key is
PROTECT (deleting a household must never quietly take its rows with it), and
the legacy tables' keys are plain NO ACTION. So the rows are deleted
explicitly, table by table, in the order the foreign keys demand, read from
the Postgres catalog rather than written down here, so a table added to
`TENANT_TABLES` next month is ordered without anyone editing this file.

**Refuses outright for a household without the flag.** The check is here and
in the trigger, so a bug in the caller still cannot reach a real household's
gated rows, and cannot reach its authored rows either.
"""
from __future__ import annotations

from django.db import connection, transaction

from household.models import Household, HouseholdMember, User
from household.tenancy import TENANT_TABLES


class NotADemoVisit(RuntimeError):
    """Said in words; nothing was deleted."""


def _tenant_tables(cursor) -> dict[str, str]:
    """{table: schema} for every TENANT_TABLES table this database has."""
    cursor.execute(
        "select c.relname, n.nspname from pg_class c "
        "join pg_namespace n on n.oid = c.relnamespace "
        "join pg_attribute a on a.attrelid = c.oid and a.attname = 'household_id' "
        "where c.relkind = 'r' and n.nspname in ('public', 'django') and c.relname = any(%s)",
        [list(TENANT_TABLES)],
    )
    return dict(cursor.fetchall())


def _foreign_keys(cursor, tables: dict[str, str]) -> list[tuple[str, str, list[str]]]:
    """(child, parent, child columns) for every foreign key between two of
    `tables`, self-references included."""
    cursor.execute(
        "select ch.relname, pa.relname, array("
        "  select a.attname from unnest(con.conkey) k "
        "  join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k) "
        "from pg_constraint con "
        "join pg_class ch on ch.oid = con.conrelid "
        "join pg_class pa on pa.oid = con.confrelid "
        "where con.contype = 'f' and ch.relname = any(%s) and pa.relname = any(%s)",
        [list(tables), list(tables)],
    )
    return [(child, parent, list(cols)) for child, parent, cols in cursor.fetchall()]


def deletion_order(cursor) -> tuple[list[str], dict[str, list[list[str]]], dict[str, str]]:
    """Children before parents. Returns (order, self-referencing column sets
    per table, schema per table). Raises on a cycle between two tables, which
    no schema here has; a cycle would need a decision, not a guess."""
    tables = _tenant_tables(cursor)
    parents: dict[str, set[str]] = {t: set() for t in tables}
    self_refs: dict[str, list[list[str]]] = {}
    for child, parent, cols in _foreign_keys(cursor, tables):
        if child == parent:
            self_refs.setdefault(child, []).append(cols)
        else:
            parents[child].add(parent)
    children: dict[str, set[str]] = {t: set() for t in tables}
    for child, ps in parents.items():
        for parent in ps:
            children[parent].add(child)

    order: list[str] = []
    done: set[str] = set()
    # A table is deletable once every table that references it is done.
    while len(order) < len(tables):
        ready = sorted(t for t in tables if t not in done and children[t] <= done)
        if not ready:
            stuck = sorted(set(tables) - done)
            raise RuntimeError(f"Nothing was deleted. Foreign keys form a cycle among {stuck}.")
        order.extend(ready)
        done.update(ready)
    return order, self_refs, tables


def _delete_self_referencing(cursor, schema, table, column_sets, household_id) -> int:
    """Rows that other rows of the same table point at (a reversal's original)
    go last: delete the unreferenced ones, repeat until none are left."""
    deleted = 0
    referenced = " or ".join(
        f'exists (select 1 from "{schema}"."{table}" r where '
        + " and ".join(f'r."{c}" = t."id"' for c in cols)
        + ")"
        for cols in column_sets
    )
    while True:
        cursor.execute(
            f'delete from "{schema}"."{table}" t where t.household_id = %s and not ({referenced})',
            [household_id],
        )
        if cursor.rowcount == 0:
            break
        deleted += cursor.rowcount
    return deleted


def delete_visit_household(household: Household) -> dict[str, int]:
    """Deletes every row of `household`, its visitor users (with their
    membership and tokens), and the household itself. Returns rows deleted
    per table. Refuses, deleting nothing, unless the household is a demo
    visit."""
    household.refresh_from_db(fields=["is_demo_visit"])
    if not household.is_demo_visit:
        raise NotADemoVisit(
            f"Nothing was deleted. Household {household.id} is not a demo visit, and only a "
            f"demo visit's household may be deleted whole."
        )
    counts: dict[str, int] = {}
    with transaction.atomic(), connection.cursor() as cursor:
        order, self_refs, schemas = deletion_order(cursor)
        for table in order:
            schema = schemas[table]
            if table in self_refs:
                n = _delete_self_referencing(cursor, schema, table, self_refs[table], household.id)
            else:
                cursor.execute(
                    f'delete from "{schema}"."{table}" where household_id = %s', [household.id]
                )
                n = cursor.rowcount
            if n:
                counts[table] = n
        # The visitors: recruiters only, never staff, and only of this household.
        visitors = User.objects.filter(
            household_member__household=household,
            household_member__role=HouseholdMember.RECRUITER,
            is_staff=False,
            is_superuser=False,
        )
        counts["visitor users"] = visitors.count()
        visitors.delete()
        if HouseholdMember.objects.filter(household=household).exists():
            raise NotADemoVisit(
                f"Nothing was deleted. Household {household.id} has a member who is not a "
                f"recruiter visitor."
            )
        Household.objects.filter(pk=household.pk, is_demo_visit=True).delete()
    return counts
