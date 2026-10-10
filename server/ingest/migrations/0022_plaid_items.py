"""`plaid_items` (ADR 0057), and `plaid` joins `ingest_runs.source`.

Generated, plus the temporary search_path swap `0001_initial.py` and
`0002_source_credentials.py` use so the new table lands in `public` (where
`household.tenancy.TENANT_TABLES` and RLS expect it) rather than `django`.
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

    dependencies = [
        ("household", "0005_household_timezone"),
        ("ingest", "0021_ledger_bank_rows"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.CreateModel(
            name="PlaidItem",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4,
                        editable=False,
                        primary_key=True,
                        serialize=False,
                    ),
                ),
                ("item_id", models.TextField()),
                ("access_token_ciphertext", models.BinaryField()),
                ("institution_id", models.TextField(blank=True, null=True)),
                ("institution_name", models.TextField(blank=True, null=True)),
                ("accounts", models.JSONField(blank=True, default=list)),
                ("cursor", models.TextField(blank=True, null=True)),
                ("consent_expires_at", models.DateTimeField(blank=True, null=True)),
                ("error_code", models.TextField(blank=True, null=True)),
                ("needs_sign_in_since", models.DateTimeField(blank=True, null=True)),
                (
                    "created_at",
                    models.DateTimeField(
                        db_default=django.db.models.functions.datetime.Now()
                    ),
                ),
                ("last_synced_at", models.DateTimeField(blank=True, null=True)),
            ],
            options={
                "db_table": "plaid_items",
            },
        ),
        migrations.RemoveConstraint(
            model_name="ingestrun",
            name="ingest_runs_source_valid",
        ),
        migrations.AlterField(
            model_name="ingestrun",
            name="source",
            field=models.TextField(
                choices=[
                    ("canvas", "Canvas"),
                    ("webassign", "WebAssign"),
                    ("drive_statements", "Drive statements"),
                    ("backup", "Backup"),
                    ("obd_rollup", "OBD roll-up"),
                    ("heartbeat", "Heartbeat"),
                    ("push", "Notifications"),
                    ("plaid", "Bank connection"),
                ]
            ),
        ),
        migrations.AddConstraint(
            model_name="ingestrun",
            constraint=models.CheckConstraint(
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
                            "push",
                            "plaid",
                        ],
                    )
                ),
                name="ingest_runs_source_valid",
            ),
        ),
        migrations.AddField(
            model_name="plaiditem",
            name="household",
            field=models.ForeignKey(
                on_delete=django.db.models.deletion.PROTECT,
                related_name="+",
                to="household.household",
            ),
        ),
        migrations.AddField(
            model_name="plaiditem",
            name="linked_by",
            field=models.ForeignKey(
                blank=True,
                null=True,
                on_delete=django.db.models.deletion.SET_NULL,
                related_name="+",
                to=settings.AUTH_USER_MODEL,
            ),
        ),
        migrations.AddConstraint(
            model_name="plaiditem",
            constraint=models.UniqueConstraint(
                fields=("household",), name="plaid_items_one_per_household"
            ),
        ),
        migrations.AddConstraint(
            model_name="plaiditem",
            constraint=models.UniqueConstraint(
                fields=("item_id",), name="plaid_items_item_id_uniq"
            ),
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
