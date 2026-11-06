"""`device_logs.nav_audit_lines`: how many NAV_AUDIT lines a batch holds (nav-quality decision D7).

A batch with any is kept 90 days (`NAV_AUDIT_RETENTION_DAYS`) instead of 60, cut to those lines
after 60 (`devicelogs.views.prune`). Additive: every existing row gets 0 and keeps the 60-day rule;
none was uploaded with NAV_AUDIT lines before this branch. Same search_path swap as `0001_initial`
so the column lands on `public.device_logs`.
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
            name="nav_audit_lines",
            field=models.IntegerField(default=0),
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
