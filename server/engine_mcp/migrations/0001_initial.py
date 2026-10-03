# engine-mcp ticket 10 (ticket 08's audit ruling): `public.mcp_calls`.
#
# Hand-written in the shape `makemigrations` produces, plus the same temporary
# search_path swap `ingest/migrations/0001_initial.py` uses so the table lands
# in `public` rather than `django` (see `engine_mcp/models.py` for why a tenant
# table has to). Born tenanted: `household` is a real ForeignKey here, so
# `household/migrations/0002`'s SQL loop has nothing to add to it.
#
# ADDITIVE: one new table, nothing existing touched.
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
        ("household", "0004_devicetoken_scope"),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.CreateModel(
            name="McpCall",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("token_name", models.TextField()),
                ("token_scope", models.TextField()),
                ("method", models.TextField()),
                ("tool", models.TextField(blank=True, null=True)),
                (
                    "outcome",
                    models.TextField(
                        choices=[
                            ("ok", "OK"),
                            ("refused", "Refused"),
                            ("failed", "Failed"),
                            ("throttled", "Throttled"),
                        ]
                    ),
                ),
                ("at", models.DateTimeField(db_default=django.db.models.functions.datetime.Now())),
                (
                    "household",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                (
                    "token",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.SET_NULL,
                        related_name="+",
                        to="household.devicetoken",
                    ),
                ),
            ],
            options={
                "db_table": "mcp_calls",
                "indexes": [
                    models.Index(fields=["household", "-at"], name="mcp_calls_household_at")
                ],
                "constraints": [
                    models.CheckConstraint(
                        condition=models.Q(
                            ("outcome__in", ["ok", "refused", "failed", "throttled"])
                        ),
                        name="mcp_calls_outcome_valid",
                    )
                ],
            },
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
