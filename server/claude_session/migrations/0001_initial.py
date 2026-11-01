"""The four Claude session tables (claude-session ticket 03, ADR 0067).

Same temporary search_path swap `devicelogs/migrations/0001_initial.py` uses so
the tables land in `public` beside the other tenant tables
`household.tenancy.TENANT_TABLES` lists. Born tenanted: every table has a real
`household` ForeignKey, so `household/migrations/0002`'s SQL loop has nothing
to add.

ADDITIVE: four new tables, nothing existing touched.
"""

import django.db.models.deletion
import django.db.models.functions.datetime
import django.db.models.functions.text
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
            name='ClaudeMachine',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ('name', models.TextField()),
                ('hostname', models.TextField(blank=True, default='')),
                ('scope', models.TextField(default='listed')),
                ('reported_state', models.TextField(default='idle')),
                ('claude_version', models.TextField(blank=True, default='')),
                ('last_seen_at', models.DateTimeField(blank=True, null=True)),
                ('created_at', models.DateTimeField(db_default=django.db.models.functions.datetime.Now())),
                ('household', models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, related_name='+', to='household.household')),
            ],
            options={
                'db_table': 'claude_machines',
            },
        ),
        migrations.CreateModel(
            name='ClaudeCodeSession',
            fields=[
                ('id', models.BigAutoField(primary_key=True, serialize=False)),
                ('session_id', models.TextField()),
                ('project', models.TextField(blank=True, default='')),
                ('state', models.TextField()),
                ('last_prompt_summary', models.TextField(blank=True, null=True)),
                ('reported_at', models.DateTimeField(blank=True, null=True)),
                ('last_seen_at', models.DateTimeField()),
                ('household', models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, related_name='+', to='household.household')),
                ('machine', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='sessions', to='claude_session.claudemachine')),
            ],
            options={
                'db_table': 'claude_code_sessions',
            },
        ),
        migrations.CreateModel(
            name='ClaudeTask',
            fields=[
                ('id', models.BigAutoField(primary_key=True, serialize=False)),
                ('origin', models.TextField()),
                ('deliver', models.TextField(blank=True, null=True)),
                ('request', models.TextField()),
                ('status', models.TextField(default='queued')),
                ('summary', models.TextField(blank=True, null=True)),
                ('question', models.TextField(blank=True, null=True)),
                ('turns', models.IntegerField(blank=True, null=True)),
                ('duration_ms', models.BigIntegerField(blank=True, null=True)),
                ('progress', models.TextField(blank=True, null=True)),
                ('progress_at', models.DateTimeField(blank=True, null=True)),
                ('created_at', models.DateTimeField(db_default=django.db.models.functions.datetime.Now())),
                ('started_at', models.DateTimeField(blank=True, null=True)),
                ('finished_at', models.DateTimeField(blank=True, null=True)),
                ('household', models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, related_name='+', to='household.household')),
                ('machine', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='tasks', to='claude_session.claudemachine')),
                ('parent', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='+', to='claude_session.claudetask')),
                ('user', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='+', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'db_table': 'claude_tasks',
            },
        ),
        migrations.CreateModel(
            name='ClaudeTaskFile',
            fields=[
                ('id', models.BigAutoField(primary_key=True, serialize=False)),
                ('path', models.TextField()),
                ('name', models.TextField()),
                ('size', models.BigIntegerField(blank=True, null=True)),
                ('requested_at', models.DateTimeField(blank=True, null=True)),
                ('uploaded_at', models.DateTimeField(blank=True, null=True)),
                ('content', models.BinaryField(blank=True, null=True)),
                ('content_type', models.TextField(blank=True, default='')),
                ('created_at', models.DateTimeField(db_default=django.db.models.functions.datetime.Now())),
                ('household', models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, related_name='+', to='household.household')),
                ('task', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='files', to='claude_session.claudetask')),
            ],
            options={
                'db_table': 'claude_task_files',
            },
        ),
        migrations.AddConstraint(
            model_name='claudemachine',
            constraint=models.UniqueConstraint(models.F('household'), django.db.models.functions.text.Lower('name'), name='claude_machines_household_name_uniq'),
        ),
        migrations.AddConstraint(
            model_name='claudemachine',
            constraint=models.CheckConstraint(condition=models.Q(('scope__in', ('full', 'listed'))), name='claude_machines_scope_valid'),
        ),
        migrations.AddConstraint(
            model_name='claudemachine',
            constraint=models.CheckConstraint(condition=models.Q(('reported_state__in', ('idle', 'busy'))), name='claude_machines_state_valid'),
        ),
        migrations.AddConstraint(
            model_name='claudecodesession',
            constraint=models.UniqueConstraint(fields=('household', 'machine', 'session_id'), name='claude_code_sessions_uniq'),
        ),
        migrations.AddIndex(
            model_name='claudetask',
            index=models.Index(fields=['household', '-created_at'], name='claude_tasks_household_created'),
        ),
        migrations.AddIndex(
            model_name='claudetask',
            index=models.Index(fields=['household', 'status'], name='claude_tasks_household_status'),
        ),
        migrations.AddConstraint(
            model_name='claudetask',
            constraint=models.CheckConstraint(condition=models.Q(('status__in', ('queued', 'needs_machine', 'running', 'cancel_requested', 'done', 'failed', 'needs_confirmation', 'cancelled'))), name='claude_tasks_status_valid'),
        ),
        migrations.AddConstraint(
            model_name='claudetask',
            constraint=models.CheckConstraint(condition=models.Q(('origin__in', ('named', 'offered'))), name='claude_tasks_origin_valid'),
        ),
        migrations.AddConstraint(
            model_name='claudetask',
            constraint=models.CheckConstraint(condition=models.Q(('deliver__isnull', True), ('deliver__in', ('email_self',)), _connector='OR'), name='claude_tasks_deliver_valid'),
        ),
        migrations.AddIndex(
            model_name='claudetaskfile',
            index=models.Index(fields=['household', 'task'], name='claude_task_files_task'),
        ),
        _RESTORE_SEARCH_PATH_TO_DJANGO_FIRST,
    ]
