"""Control room C2: `public.board_answers` and `public.board_votes`, and the snapshot's compact
backlog, DARK behind `LEGION_BOARD`.

Born tenanted in `public` and member-private exactly as `0002_board_ideas` made `board_ideas`: the
ADR 0052 owner guard is attached and both keys cascade at the database. ADDITIVE: two new tables
and two NULLABLE columns on `board_snapshots` (the revision still serving during a rollout inserts
snapshot rows without them), so the deployed code runs unchanged.
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

TABLES = ("board_answers", "board_votes")


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
        ("board", "0002_board_ideas"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _LOCK_TIMEOUT,
        _PUBLIC_FIRST,
        migrations.AddField(
            model_name="boardsnapshot",
            name="backlog",
            field=models.JSONField(blank=True, default=list, null=True),
        ),
        migrations.AddField(
            model_name="boardsnapshot",
            name="backlog_total",
            field=models.IntegerField(blank=True, default=0, null=True),
        ),
        migrations.CreateModel(
            name="BoardAnswer",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("card_id", models.CharField(max_length=60)),
                ("ask_sha256", models.CharField(max_length=64)),
                ("choice", models.TextField(blank=True, default="")),
                ("text", models.TextField(blank=True, default="")),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
                ("delivered_at", models.DateTimeField(blank=True, null=True)),
                ("outcome", models.CharField(default="pending", max_length=12)),
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
                "db_table": "board_answers",
                "indexes": [
                    models.Index(fields=["owner_user", "card_id"], name="board_answers_owner_card"),
                    models.Index(
                        fields=["owner_user", "delivered_at"], name="board_answers_owner_due"
                    ),
                ],
            },
        ),
        migrations.CreateModel(
            name="BoardVote",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("card_id", models.CharField(max_length=60)),
                ("vote", models.CharField(max_length=8)),
                ("created_at", models.DateTimeField(default=django.utils.timezone.now)),
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
                "db_table": "board_votes",
                "indexes": [
                    models.Index(fields=["owner_user", "card_id"], name="board_votes_owner_card")
                ],
            },
        ),
        migrations.RunPython(guard, unguard),
        migrations.RunSQL(sql=_CASCADES, reverse_sql=migrations.RunSQL.noop),
        _DJANGO_FIRST,
    ]
