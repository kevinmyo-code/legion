"""`device_logs.reason` gains `conversation` (2026-10-28, Kevin: "all logs on engine. both
headunit and phone"): a batch of a device's LIVE_AUDIT and LIVE_LATENCY lines only.

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
        ("devicelogs", "0002_periodic_reason"),
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
                condition=models.Q(
                    ("reason__in", ("drive_end", "startup", "manual", "periodic", "conversation"))
                ),
                name="device_logs_reason_valid",
            ),
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
