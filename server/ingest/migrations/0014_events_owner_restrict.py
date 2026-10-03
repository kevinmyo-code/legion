"""`events.owner_user_id` becomes ON DELETE RESTRICT (spec D3, corrected
2026-10-03).

`0010` created the key as ON DELETE SET NULL, which would have turned a
hard-deleted user's private rows shared. The reason and the SQL are
`household/visibility_sql.py`. Guarded on the table existing like `0006`
onwards; `tests/conftest.py` calls the same function once it exists.
`created_by_id` stays SET NULL.
"""

from django.db import migrations


def restrict(apps, schema_editor):
    from household.visibility_sql import restrict_event_owner

    with schema_editor.connection.cursor() as cursor:
        restrict_event_owner(cursor)


def unrestrict(apps, schema_editor):
    from household.visibility_sql import restrict_event_owner

    with schema_editor.connection.cursor() as cursor:
        restrict_event_owner(cursor, restrict=False)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0013_event_skips_sync_columns"),
    ]

    operations = [migrations.RunPython(restrict, unrestrict)]
