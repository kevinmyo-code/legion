"""`device_logs` carries the web assistant's conversation records (2026-11-03).

A recruiter visit's conversation has no household (`demo_visitor`), so the visit's household can be
deleted whole while the record is kept 30 days; `visit` and `visit_ref` say which visit it was.
`clock_offset_ms` puts a browser's lines on the engine's clock. Same search_path swap as
`0001_initial` so everything lands on `public.device_logs`. Adds columns and constraints; every
existing row has a household and `demo_visitor = false`, so both constraints hold on it.
"""

import django.db.models.deletion
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
        ("demo", "0004_demovisit_household"),
        ("devicelogs", "0003_conversation_reason"),
        ("household", "0008_demo_visit_delete_exemption"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.AddField(
            model_name="devicelog",
            name="clock_offset_ms",
            field=models.BigIntegerField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name="devicelog",
            name="demo_visitor",
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name="devicelog",
            name="visit",
            field=models.ForeignKey(
                blank=True,
                null=True,
                on_delete=django.db.models.deletion.SET_NULL,
                related_name="+",
                to="demo.demovisit",
            ),
        ),
        migrations.AddField(
            model_name="devicelog",
            name="visit_ref",
            field=models.CharField(blank=True, default="", max_length=64),
        ),
        migrations.AlterField(
            model_name="devicelog",
            name="household",
            field=models.ForeignKey(
                blank=True,
                null=True,
                on_delete=django.db.models.deletion.PROTECT,
                related_name="+",
                to="household.household",
            ),
        ),
        migrations.AddConstraint(
            model_name="devicelog",
            constraint=models.UniqueConstraint(
                condition=models.Q(("household__isnull", True)),
                fields=("batch_id",),
                name="device_logs_visit_batch_id_uniq",
            ),
        ),
        migrations.AddConstraint(
            model_name="devicelog",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    models.Q(("demo_visitor", False), ("household__isnull", False)),
                    models.Q(("demo_visitor", True), ("household__isnull", True)),
                    _connector="OR",
                ),
                name="device_logs_household_or_visit",
            ),
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
