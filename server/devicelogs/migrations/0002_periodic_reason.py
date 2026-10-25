"""`device_logs.reason` gains `periodic` (2026-10-24): the head unit uploads every few
minutes while it runs, because the ignition is cut the moment the car stops and a drive-end
upload never got to run.

Same search_path swap as `0001_initial` so the constraint is found on `public.device_logs`.
Widens a CHECK; no row changes.
"""

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
        ("devicelogs", "0001_initial"),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.RemoveConstraint(
            model_name="devicelog",
            name="device_logs_reason_valid",
        ),
        migrations.AddConstraint(
            model_name="devicelog",
            constraint=models.CheckConstraint(
                condition=models.Q(("reason__in", ("drive_end", "startup", "manual", "periodic"))),
                name="device_logs_reason_valid",
            ),
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
