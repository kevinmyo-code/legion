"""Control room C3: `public.board_actions` and its append-only tap log `public.board_action_taps`,
DARK behind `LEGION_BOARD_ACTIONS` (its own switch: `LEGION_BOARD` going on for C2 opens nothing
here).

Born tenanted in `public` and member-private as `0002_board_ideas`: the ADR 0052 owner guard and
both keys cascading at the database. ADDITIVE: two new tables no earlier code knows about.
"""

import uuid

import django.db.models.deletion
import django.utils.timezone
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

_LOCK_TIMEOUT = migrations.RunSQL(
    sql="SET LOCAL lock_timeout = '5s';",
    reverse_sql="SET LOCAL lock_timeout = '5s';",
)

TABLES = ("board_actions", "board_action_taps")


def guard(apps, schema_editor):
    from household.visibility_sql import attach_guard, create_guard_function

    with schema_editor.connection.cursor() as cursor:
        create_guard_function(cursor)
        for table in TABLES:
            attach_guard(cursor, table)


def unguard(apps, schema_editor):
    from household.visibility_sql import GUARD_TRIGGER

    with schema_editor.connection.cursor() as cursor:
        for table in TABLES:
            cursor.execute(f"drop trigger if exists {GUARD_TRIGGER} on public.{table}")


# As 0001: both keys delete with their parent AT THE DATABASE, so code that predates these tables
# (a rollback) never blocks deleting a member or household.
_CASCADES = "".join(
    f"alter table public.{t} add constraint {t}_owner_user_cascade foreign key "
    "(owner_user_id) references household_user (id) on delete cascade "
    "deferrable initially deferred; "
    f"alter table public.{t} add constraint {t}_household_cascade foreign key "
    "(household_id) references household_household (id) on delete cascade "
    "deferrable initially deferred; "
    for t in TABLES
)


class Migration(migrations.Migration):
    dependencies = [
        ("board", "0003_board_answers_votes"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _LOCK_TIMEOUT,
        _PUBLIC_FIRST,
        migrations.CreateModel(
            name="BoardAction",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("kind", models.CharField(max_length=20)),
                ("sha", models.CharField(blank=True, default="", max_length=40)),
                ("tier", models.CharField(blank=True, default="", max_length=8)),
                ("review_ok_sha", models.CharField(blank=True, default="", max_length=40)),
                ("ci_green", models.BooleanField(default=False)),
                ("flags", models.JSONField(blank=True, default=list)),
                ("amount_cents", models.IntegerField(blank=True, null=True)),
                ("payee", models.CharField(blank=True, default="", max_length=60)),
                ("purpose", models.CharField(blank=True, default="", max_length=120)),
                ("record_sha256", models.CharField(max_length=64)),
                ("status", models.CharField(default="pending", max_length=12)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("expires_at", models.DateTimeField()),
                ("decided_at", models.DateTimeField(blank=True, null=True)),
                ("pulled_at", models.DateTimeField(blank=True, null=True)),
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
                "db_table": "board_actions",
                "indexes": [
                    models.Index(fields=["owner_user", "status"], name="board_actions_owner_status")
                ],
            },
        ),
        migrations.CreateModel(
            name="BoardActionTap",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("action_id", models.UUIDField()),
                ("decision", models.CharField(max_length=10)),
                ("record_sha256", models.CharField(blank=True, default="", max_length=64)),
                ("result", models.CharField(max_length=10)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
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
                "db_table": "board_action_taps",
                "indexes": [
                    models.Index(fields=["owner_user", "action_id"], name="board_taps_owner_action")
                ],
            },
        ),
        migrations.RunPython(guard, unguard),
        migrations.RunSQL(sql=_CASCADES, reverse_sql=migrations.RunSQL.noop),
        _DJANGO_FIRST,
    ]
