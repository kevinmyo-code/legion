"""`public.board_ideas`: backlog ideas said to Alfred, waiting for the laptop, DARK.

Born tenanted in `public` and member-private exactly as `0001_initial` made `board_snapshots`: the
ADR 0052 owner guard is attached and both keys cascade at the database. ADDITIVE: one new table no
earlier code knows about; nothing else is touched, so the deployed code runs unchanged with it.
"""

import uuid

import django.db.models.deletion
import django.utils.timezone
from django.conf import settings
from django.db import migrations, models

import board.models

_PUBLIC_FIRST = migrations.RunSQL(
    sql="SET search_path TO public, django;",
    reverse_sql="SET search_path TO django, public;",
)

_DJANGO_FIRST = migrations.RunSQL(
    sql="SET search_path TO django, public;",
    reverse_sql="SET search_path TO public, django;",
)

_LOCK_TIMEOUT = migrations.RunSQL(
    sql="SET LOCAL lock_timeout = '5s';",
    reverse_sql="SET LOCAL lock_timeout = '5s';",
)

TABLE = "board_ideas"


def guard(apps, schema_editor):
    from household.visibility_sql import attach_guard, create_guard_function

    with schema_editor.connection.cursor() as cursor:
        create_guard_function(cursor)
        attach_guard(cursor, TABLE)


def unguard(apps, schema_editor):
    from household.visibility_sql import GUARD_TRIGGER

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(f"drop trigger if exists {GUARD_TRIGGER} on public.{TABLE}")


# As 0001: both keys delete with their parent AT THE DATABASE, so code that predates this table (a
# rollback) never blocks deleting a member or household.
_CASCADES = (
    f"alter table public.{TABLE} add constraint {TABLE}_owner_user_cascade foreign key "
    "(owner_user_id) references household_user (id) on delete cascade "
    "deferrable initially deferred; "
    f"alter table public.{TABLE} add constraint {TABLE}_household_cascade foreign key "
    "(household_id) references household_household (id) on delete cascade "
    "deferrable initially deferred;"
)


class Migration(migrations.Migration):
    dependencies = [
        ("board", "0001_initial"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _LOCK_TIMEOUT,
        _PUBLIC_FIRST,
        migrations.CreateModel(
            name="BoardIdea",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                (
                    "card_id",
                    models.CharField(default=board.models.new_card_id, max_length=60, unique=True),
                ),
                ("title", models.TextField()),
                ("text", models.TextField()),
                ("scores", models.JSONField(blank=True, default=dict)),
                ("status", models.CharField(default="draft", max_length=12)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("confirmed_at", models.DateTimeField(blank=True, null=True)),
                ("delivered_at", models.DateTimeField(blank=True, null=True)),
                (
                    "household",
                    models.ForeignKey(
                        db_constraint=False,
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                (
                    "owner_user",
                    models.ForeignKey(
                        db_constraint=False,
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="+",
                        to=settings.AUTH_USER_MODEL,
                    ),
                ),
            ],
            options={
                "db_table": "board_ideas",
                "indexes": [
                    models.Index(fields=["owner_user", "status"], name="board_ideas_owner_status")
                ],
            },
        ),
        migrations.RunPython(guard, unguard),
        migrations.RunSQL(sql=_CASCADES, reverse_sql=migrations.RunSQL.noop),
        _DJANGO_FIRST,
    ]
