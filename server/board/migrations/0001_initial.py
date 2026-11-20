"""`public.board_snapshots`: the factory board's latest snapshot, one row per member, DARK.

Born tenanted in `public` by the temporary search_path swap `headunit_signin/migrations/0001_initial.py`
uses, and member-private: the ADR 0052 owner guard (`household/visibility_sql.py`) is attached, so
the database itself refuses a row whose owner is not a member of its household. ADDITIVE: one new
table no earlier code knows about; nothing else is touched, so the deployed code runs unchanged with
it present.
"""

import uuid

import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models

_PUBLIC_FIRST = migrations.RunSQL(
    sql="SET search_path TO public, django;",
    reverse_sql="SET search_path TO django, public;",
)

_DJANGO_FIRST = migrations.RunSQL(
    sql="SET search_path TO django, public;",
    reverse_sql="SET search_path TO public, django;",
)

# Give up after 5 s instead of queueing behind another session's lock; a timeout rolls the whole
# migration back (transactional DDL).
_LOCK_TIMEOUT = migrations.RunSQL(
    sql="SET LOCAL lock_timeout = '5s';",
    reverse_sql="SET LOCAL lock_timeout = '5s';",
)

TABLE = "board_snapshots"


def guard(apps, schema_editor):
    from household.visibility_sql import attach_guard, create_guard_function

    with schema_editor.connection.cursor() as cursor:
        create_guard_function(cursor)
        attach_guard(cursor, TABLE)


def unguard(apps, schema_editor):
    from household.visibility_sql import GUARD_TRIGGER

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(f"drop trigger if exists {GUARD_TRIGGER} on public.{TABLE}")


class Migration(migrations.Migration):
    initial = True

    dependencies = [
        ("household", "0009_devicetoken_claude_worker_scope"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _LOCK_TIMEOUT,
        _PUBLIC_FIRST,
        migrations.CreateModel(
            name="BoardSnapshot",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("cards", models.JSONField(blank=True, default=list)),
                ("build_slot", models.TextField(blank=True, default="")),
                ("generated_at", models.DateTimeField(blank=True, null=True)),
                ("received_at", models.DateTimeField(blank=True, null=True)),
                ("size_bytes", models.IntegerField(default=0)),
                (
                    "writer_key_hash",
                    models.CharField(blank=True, max_length=64, null=True, unique=True),
                ),
                ("writer_created_at", models.DateTimeField(blank=True, null=True)),
                ("writer_last_used_at", models.DateTimeField(blank=True, null=True)),
                (
                    "household",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                (
                    "owner_user",
                    models.OneToOneField(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="+",
                        to=settings.AUTH_USER_MODEL,
                    ),
                ),
            ],
            options={"db_table": "board_snapshots"},
        ),
        migrations.RunPython(guard, unguard),
        _DJANGO_FIRST,
    ]
