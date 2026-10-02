# Hand-written in the shape `makemigrations` produces (backend-etl ticket 01),
# plus the same temporary search_path swap `checklists/migrations/0001_initial.py`
# uses so `ingest_runs` lands in `public` rather than `django`. That file's own
# header holds the full reasoning; in short: an unqualified CREATE TABLE follows
# search_path, which `legion/settings.py` points at `django,public`, and a
# schema-qualified `db_table` was rejected there for reasons that apply here too.
#
# **Not in `household/migrations/0002`'s SQL loop, and it does not need to be.**
# That loop adds `household_id` to tables that predate tenancy. This table is
# born with the column (a real ForeignKey below), so on the live database the
# loop - long since applied - never meets it, and on a fresh test database it
# records "no such table" for it, exactly as it does for the forty legacy
# tables. `tests/conftest.py`'s second pass then finds the column already there
# and only the primary key as a unique index, and changes nothing.
import uuid

import django.db.models.deletion
import django.db.models.functions.datetime
from django.db import migrations, models

_SWAP_SEARCH_PATH_TO_PUBLIC_FIRST = migrations.RunSQL(
    sql="SET search_path TO public, django;",
    reverse_sql="SET search_path TO django, public;",
)

_RESTORE_SEARCH_PATH_TO_DJANGO_FIRST = migrations.RunSQL(
    sql="SET search_path TO django, public;",
    reverse_sql="SET search_path TO public, django;",
)


class Migration(migrations.Migration):

    initial = True

    dependencies = [
        ("household", "0002_households_are_tenants"),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.CreateModel(
            name="IngestRun",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                (
                    "source",
                    models.TextField(
                        choices=[
                            ("canvas", "Canvas"),
                            ("webassign", "WebAssign"),
                            ("drive_statements", "Drive statements"),
                            ("backup", "Backup"),
                            ("obd_rollup", "OBD roll-up"),
                            ("heartbeat", "Heartbeat"),
                        ]
                    ),
                ),
                (
                    "started_at",
                    models.DateTimeField(db_default=django.db.models.functions.datetime.Now()),
                ),
                ("finished_at", models.DateTimeField(blank=True, null=True)),
                (
                    "outcome",
                    models.TextField(
                        blank=True,
                        choices=[
                            ("ok", "OK"),
                            ("failed", "Failed"),
                            ("needs_login", "Needs login"),
                            ("skipped_locked", "Skipped, already running"),
                            ("skipped", "Skipped, not set up"),
                        ],
                        null=True,
                    ),
                ),
                ("rows_written", models.PositiveIntegerField(default=0)),
                ("rows_unchanged", models.PositiveIntegerField(default=0)),
                ("watermark", models.TextField(blank=True, null=True)),
                ("error", models.TextField(blank=True, null=True)),
                (
                    "household",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
            ],
            options={
                "db_table": "ingest_runs",
                "indexes": [
                    models.Index(
                        fields=["household", "source", "-started_at"],
                        name="ingest_runs_hh_src_start_idx",
                    )
                ],
                "constraints": [
                    models.CheckConstraint(
                        condition=models.Q(
                            (
                                "source__in",
                                [
                                    "canvas",
                                    "webassign",
                                    "drive_statements",
                                    "backup",
                                    "obd_rollup",
                                    "heartbeat",
                                ],
                            )
                        ),
                        name="ingest_runs_source_valid",
                    ),
                    models.CheckConstraint(
                        condition=models.Q(
                            ("outcome__isnull", True),
                            (
                                "outcome__in",
                                ["ok", "failed", "needs_login", "skipped_locked", "skipped"],
                            ),
                            _connector="OR",
                        ),
                        name="ingest_runs_outcome_valid",
                    ),
                ],
            },
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
