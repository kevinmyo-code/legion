"""`manage.py tenancy_sql` - prints the exact DDL ADR 0045's migration would
run against the database this settings module is pointed at, and runs none
of it.

It exists because the live database is not migrated by the ticket that
writes the migration. Somebody has to read the statements first, against the
REAL catalog - the index names, the partial-index predicates and the foreign
keys that decide which unique keys are already scoped are all read from the
server, so a plan generated against a test database is not the plan the live
run will execute. This command generates the live one without applying it.

    cd server && uv run --no-sync python manage.py tenancy_sql

Every query it issues is a `SELECT` against `pg_catalog` and
`information_schema`. It opens no transaction of its own and writes nothing.
"""
from __future__ import annotations

from django.core.management.base import BaseCommand
from django.db import connection

from household.tenancy import TENANT_TABLES, bootstrap_household_id
from household.tenancy_sql import HOUSEHOLD_TABLE, plan_all


class Command(BaseCommand):
    help = (
        "Prints the DDL the households-are-tenants migration would run against this "
        "database. Executes nothing."
    )

    def handle(self, *args, **options):
        with connection.cursor() as cursor:
            household_table_exists = self._household_table_exists(cursor)
            plans = plan_all(
                cursor,
                TENANT_TABLES,
                bootstrap_household_id(),
                before_the_household_table_exists=not household_table_exists,
            )

        if not household_table_exists:
            self.stdout.write(
                "-- NOTE: household_household does not exist on this database yet, so the "
                "REFERENCES clauses below assume it will be created in the `django` schema, "
                "where legion/settings.py's search_path puts every Django-owned table. The "
                "migration creates it three operations before it runs any of this."
            )
            self.stdout.write("")

        statement_count = 0
        for plan in plans:
            self.stdout.write(f"-- {plan.table}")
            for note in plan.notes:
                self.stdout.write(f"--   {note}")
            for statement in plan.statements:
                self.stdout.write(statement)
                statement_count += 1
            self.stdout.write("")

        self.stdout.write(
            self.style.SUCCESS(
                f"{statement_count} statements across {len(plans)} tables. "
                f"NOTHING WAS RUN - this command only reads catalogs."
            )
        )

    def _household_table_exists(self, cursor) -> bool:
        cursor.execute(
            "select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace "
            "where c.relname = %s and c.relkind = 'r'",
            [HOUSEHOLD_TABLE],
        )
        return cursor.fetchone() is not None
