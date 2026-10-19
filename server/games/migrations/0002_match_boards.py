"""Each player's live board on `public.game_matches` (ADR 0060, amended
2026-10-19: a race shows both boards side by side).

ADDITIVE: two new text columns with a default, nothing existing touched. Same
search_path swap as 0001 so the ALTER resolves the table in `public`; nothing
here touches the `private` schema, which the live engine role cannot use.
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
        ("games", "0001_initial"),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.AddField(
            model_name="gamematch",
            name="inviter_board",
            field=models.TextField(blank=True, default=""),
        ),
        migrations.AddField(
            model_name="gamematch",
            name="invitee_board",
            field=models.TextField(blank=True, default=""),
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
