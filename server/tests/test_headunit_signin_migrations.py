"""T3b migrations: back to the schema dev has (no `headunit_signin`, `devicelogs` at 0006), forward
again over a row written at dev, and back once more. The rest of the suite proves "from scratch"
(every test database is built by `migrate`)."""

from __future__ import annotations

import uuid

import pytest
from django.db import connection
from django.db.migrations.executor import MigrationExecutor

pytestmark = pytest.mark.django_db(transaction=True)

DEV = [("devicelogs", "0006_nav_audit_lines"), ("headunit_signin", None)]
TIP = [("devicelogs", "0007_headunit_uploader"), ("headunit_signin", "0001_initial")]


def _migrate(targets) -> None:
    executor = MigrationExecutor(connection)
    executor.loader.build_graph()
    executor.migrate(targets)


def _columns() -> set[str]:
    with connection.cursor() as cursor:
        cursor.execute(
            "select column_name from information_schema.columns where table_schema = 'public' "
            "and table_name = 'device_logs' and column_name in ('uploader_kind', 'headunit_id')"
        )
        return {row[0] for row in cursor.fetchall()}


def _table_exists() -> bool:
    with connection.cursor() as cursor:
        cursor.execute("select to_regclass('public.headunit_credentials') is not null")
        return cursor.fetchone()[0]


def test_down_to_dev_up_over_a_dev_row_and_down_again(bootstrap_household, household_user):
    try:
        _migrate(DEV)
        assert _columns() == set() and not _table_exists()
        row_id = uuid.uuid4()
        with connection.cursor() as cursor:
            # A row as dev writes it, before the migration.
            cursor.execute(
                "insert into public.device_logs (id, household_id, user_id, batch_id, "
                "device_label, app_version_name, reason, line_count, raw_bytes, body_gz, "
                "demo_visitor, visit_ref, run_id, nav_audit_lines) values (%s, %s, %s, %s, "
                "'phone', '', 'manual', 0, 0, '\\\\x00', false, '', '', 0)",
                [row_id, bootstrap_household.id, household_user.pk, uuid.uuid4()],
            )
        _migrate(TIP)
        assert _columns() == {"uploader_kind", "headunit_id"} and _table_exists()
        with connection.cursor() as cursor:
            cursor.execute(
                "select uploader_kind, headunit_id from public.device_logs where id = %s",
                [row_id],
            )
            assert cursor.fetchone() == ("member", None)
            cursor.execute(
                "select column_default from information_schema.columns where table_schema = "
                "'public' and table_name = 'device_logs' and column_name = 'uploader_kind'"
            )
            assert "member" in cursor.fetchone()[0]
        _migrate(DEV)
        assert _columns() == set() and not _table_exists()
    finally:
        _migrate(TIP)
    assert _columns() == {"uploader_kind", "headunit_id"} and _table_exists()
