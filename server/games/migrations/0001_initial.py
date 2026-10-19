"""`public.game_matches` (retro-games ticket 05, ADR 0060).

Same temporary search_path swap `devicelogs/migrations/0001_initial.py` uses so
the table lands in `public` beside the other tenant tables
`household.tenancy.TENANT_TABLES` lists. Born tenanted: `household` is a real
ForeignKey. Nothing here touches the `private` schema, which the live engine
role cannot use.

ADDITIVE: one new table, nothing existing touched.
"""

import django.db.models.deletion
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
        ('household', '0005_household_timezone'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        _SWAP_SEARCH_PATH_TO_PUBLIC_FIRST,
        migrations.CreateModel(
            name='GameMatch',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ('seed', models.BigIntegerField()),
                ('status', models.TextField(default='invited')),
                ('created_at', models.DateTimeField()),
                ('expires_at', models.DateTimeField()),
                ('start_at', models.DateTimeField(blank=True, null=True)),
                ('ends_at', models.DateTimeField(blank=True, null=True)),
                ('closed_at', models.DateTimeField(blank=True, null=True)),
                ('inviter_score', models.IntegerField(default=0)),
                ('inviter_lines', models.IntegerField(default=0)),
                ('inviter_level', models.IntegerField(default=1)),
                ('inviter_topped_out', models.BooleanField(default=False)),
                ('inviter_finished', models.BooleanField(default=False)),
                ('inviter_updated_at', models.DateTimeField(blank=True, null=True)),
                ('invitee_score', models.IntegerField(default=0)),
                ('invitee_lines', models.IntegerField(default=0)),
                ('invitee_level', models.IntegerField(default=1)),
                ('invitee_topped_out', models.BooleanField(default=False)),
                ('invitee_finished', models.BooleanField(default=False)),
                ('invitee_updated_at', models.DateTimeField(blank=True, null=True)),
                ('household', models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, related_name='+', to='household.household')),
                ('invitee', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='+', to=settings.AUTH_USER_MODEL)),
                ('inviter', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='+', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'db_table': 'game_matches',
                'indexes': [models.Index(fields=['household', 'invitee', 'status'], name='game_matches_invitee_status')],
                'constraints': [models.CheckConstraint(condition=models.Q(('status__in', ('invited', 'playing', 'ended', 'declined', 'cancelled', 'expired'))), name='game_matches_status_valid'), models.CheckConstraint(condition=models.Q(('seed__gte', 0), ('seed__lte', 4294967295)), name='game_matches_seed_uint32'), models.CheckConstraint(condition=models.Q(('inviter', models.F('invitee')), _negated=True), name='game_matches_two_players')],
            },
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
