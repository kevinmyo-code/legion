"""`public.push_subscriptions`, `push_preferences`, `push_sent` (web-revamp
ticket 15). Born tenanted, and in `public` by the same temporary search_path
swap `ingest/migrations/0001_initial.py` uses for `ingest_runs`, so they sit
beside the other tenant tables `household.tenancy.TENANT_TABLES` lists.
"""

import datetime
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
        ("household", "0004_devicetoken_scope"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.CreateModel(
            name="PushPreference",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("list_changes", models.BooleanField(default=True)),
                ("event_reminders", models.BooleanField(default=True)),
                ("task_due_morning", models.BooleanField(default=True)),
                ("morning_time", models.TimeField(default=datetime.time(7, 30))),
                (
                    "updated_at",
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
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="+",
                        to=settings.AUTH_USER_MODEL,
                    ),
                ),
            ],
            options={
                "db_table": "push_preferences",
                "constraints": [
                    models.UniqueConstraint(
                        fields=("household", "user"), name="push_preferences_household_user_id_uniq"
                    )
                ],
            },
        ),
        migrations.CreateModel(
            name="PushSent",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("kind", models.TextField()),
                ("key", models.TextField()),
                ("watermark", models.DateTimeField(blank=True, null=True)),
                ("delivered", models.BooleanField(default=True)),
                (
                    "sent_at",
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
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="+",
                        to=settings.AUTH_USER_MODEL,
                    ),
                ),
            ],
            options={
                "db_table": "push_sent",
                "constraints": [
                    models.UniqueConstraint(
                        fields=("household", "user", "kind", "key"),
                        name="push_sent_household_user_id_kind_key_uniq",
                    ),
                    models.CheckConstraint(
                        condition=models.Q(
                            ("kind__in", ("list_changes", "event_reminders", "task_due_morning"))
                        ),
                        name="push_sent_kind_valid",
                    ),
                ],
            },
        ),
        migrations.CreateModel(
            name="PushSubscription",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4, editable=False, primary_key=True, serialize=False
                    ),
                ),
                ("endpoint", models.TextField()),
                ("p256dh", models.TextField()),
                ("auth", models.TextField()),
                ("user_agent", models.TextField(blank=True, default="")),
                ("tz", models.TextField(default="UTC")),
                (
                    "created_at",
                    models.DateTimeField(db_default=django.db.models.functions.datetime.Now()),
                ),
                ("last_ok_at", models.DateTimeField(blank=True, null=True)),
                ("failure_count", models.IntegerField(default=0)),
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
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="+",
                        to=settings.AUTH_USER_MODEL,
                    ),
                ),
            ],
            options={
                "db_table": "push_subscriptions",
                "constraints": [
                    models.UniqueConstraint(
                        fields=("household", "endpoint"),
                        name="push_subscriptions_household_endpoint_uniq",
                    )
                ],
            },
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
