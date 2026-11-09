"""`device_logs` takes the phone's own logs (2026-11-09, Kevin: "Send everything from the phone to
the engine so we can audit without adb").

- `reason` gains `logcat` (every line the app's process wrote) and `crash` (an uncaught exception's
  stack and Android's exit records). Widens the CHECK; no row changes.
- `run_id`: one id per phone process start, so a conversation batch joins to the raw lines around
  it. Additive, blank on every existing row.

Same search_path swap as `0001_initial` so both land on `public.device_logs`.

**Numbering:** the unmerged `feat/nav-audit` branch also adds a `0005` (`0005_nav_audit_lines`).
Whichever merges second renumbers to `0006` and depends on the other.
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
        ("devicelogs", "0004_web_conversations"),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.AddField(
            model_name="devicelog",
            name="run_id",
            field=models.CharField(blank=True, default="", max_length=64),
        ),
        migrations.RemoveConstraint(
            model_name="devicelog",
            name="device_logs_reason_valid",
        ),
        migrations.AddConstraint(
            model_name="devicelog",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    (
                        "reason__in",
                        (
                            "drive_end",
                            "startup",
                            "manual",
                            "periodic",
                            "conversation",
                            "logcat",
                            "crash",
                        ),
                    )
                ),
                name="device_logs_reason_valid",
            ),
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
