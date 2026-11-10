"""Row-level security policies on the tables the recruiter demo can reach (ADR 0045 layer 2,
architecture review 2026-11-10 #11). The SQL is `household/rls.py`.

Creates no role and changes no behaviour on its own: the policies bind only a role that neither
owns the tables nor has BYPASSRLS, and the engine's own connection owns them. They start to
matter when the operator creates `legion_app` and sets `LEGION_RLS=on` (`deploy/rls/README.md`).

Guarded on each table existing, like `0008`: on the pytest database the legacy tables do not
exist at `migrate` time and `tests/conftest.py` calls the same function once they do.
"""

from django.db import migrations


def add(apps, schema_editor):
    from household.rls import apply_rls

    with schema_editor.connection.cursor() as cursor:
        apply_rls(cursor)


def drop(apps, schema_editor):
    from household.rls import drop_rls

    with schema_editor.connection.cursor() as cursor:
        drop_rls(cursor)


class Migration(migrations.Migration):
    dependencies = [
        ("household", "0008_demo_visit_delete_exemption"),
        ("checklists", "0006_create_builtin_groceries"),
        ("purchases", "0003_backfill_all_groceries_ticks"),
        ("finance", "0002_policy_fixed_bills"),
        ("procedures", "0002_runs_and_steps"),
        ("assistant", "0003_read_through_turns"),
        ("devicelogs", "0006_nav_audit_lines"),
        ("ingest", "0026_memory_source"),
    ]

    operations = [migrations.RunPython(add, drop)]
