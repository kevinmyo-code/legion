"""`public.device_logs` (Kevin, 2026-10-15: the head unit uploads its own logs).

Same temporary search_path swap `push/migrations/0001_initial.py` uses so the
table lands in `public` beside the other tenant tables
`household.tenancy.TENANT_TABLES` lists. Born tenanted: `household` is a real
ForeignKey, so `household/migrations/0002`'s SQL loop has nothing to add.
Nothing here touches the `private` schema, which the live engine role cannot
use.

ADDITIVE: one new table, nothing existing touched.
"""

import django.db.models.deletion
import django.db.models.functions.datetime
import uuid
from django.conf import settings
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
        ("household", "0005_household_timezone"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.CreateModel(
            name="DeviceLog",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("batch_id", models.UUIDField()),
                ("device_label", models.TextField()),
                ("app_version_name", models.TextField(blank=True, default="")),
                ("app_version_code", models.IntegerField(blank=True, null=True)),
                ("reason", models.TextField()),
                ("window_start", models.DateTimeField(blank=True, null=True)),
                ("window_end", models.DateTimeField(blank=True, null=True)),
                ("line_count", models.IntegerField(default=0)),
                ("raw_bytes", models.IntegerField()),
                ("body_gz", models.BinaryField()),
                (
                    "received_at",
                    models.DateTimeField(db_default=django.db.models.functions.datetime.Now()),
                ),
                (
                    "household",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                (
                    "user",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.SET_NULL,
                        related_name="+",
                        to=settings.AUTH_USER_MODEL,
                    ),
                ),
            ],
            options={
                "db_table": "device_logs",
                "indexes": [
                    models.Index(
                        fields=["household", "-received_at"], name="device_logs_household_recv"
                    )
                ],
                "constraints": [
                    models.UniqueConstraint(
                        fields=("household", "batch_id"),
                        name="device_logs_household_batch_id_uniq",
                    ),
                    models.CheckConstraint(
                        condition=models.Q(("reason__in", ("drive_end", "startup", "manual"))),
                        name="device_logs_reason_valid",
                    ),
                ],
            },
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
