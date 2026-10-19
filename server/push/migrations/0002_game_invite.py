"""`game_invite`, a fourth push kind (retro-games ticket 05, ADR 0060).

A per-person preference like the other three, default on, and a widened CHECK
on `push_sent.kind` so the dedupe ledger accepts it. Both tables already live
in `public`, so no search_path swap is needed (compare `ingest/0015`).
Nothing here touches the `private` schema.

ADDITIVE: one new column with a default; the CHECK only widens.
"""

from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('household', '0005_household_timezone'),
        ('push', '0001_initial'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.RemoveConstraint(
            model_name='pushsent',
            name='push_sent_kind_valid',
        ),
        migrations.AddField(
            model_name='pushpreference',
            name='game_invite',
            field=models.BooleanField(default=True),
        ),
        migrations.AddConstraint(
            model_name='pushsent',
            constraint=models.CheckConstraint(condition=models.Q(('kind__in', ('list_changes', 'event_reminders', 'task_due_morning', 'game_invite'))), name='push_sent_kind_valid'),
        ),
    ]
