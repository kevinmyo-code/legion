"""`ingest_runs.source` gains `push` (web-revamp ticket 15): `push_dispatch`
records its runs like every other scheduled job, so a stalled sender reads
as stale in `/api/freshness`.
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('household', '0004_devicetoken_scope'),
        ('ingest', '0014_events_owner_restrict'),
    ]

    operations = [
        migrations.RemoveConstraint(
            model_name='ingestrun',
            name='ingest_runs_source_valid',
        ),
        migrations.AlterField(
            model_name='ingestrun',
            name='source',
            field=models.TextField(choices=[('canvas', 'Canvas'), ('webassign', 'WebAssign'), ('drive_statements', 'Drive statements'), ('backup', 'Backup'), ('obd_rollup', 'OBD roll-up'), ('heartbeat', 'Heartbeat'), ('push', 'Notifications')]),
        ),
        migrations.AddConstraint(
            model_name='ingestrun',
            constraint=models.CheckConstraint(condition=models.Q(('source__in', ['canvas', 'webassign', 'drive_statements', 'backup', 'obd_rollup', 'heartbeat', 'push'])), name='ingest_runs_source_valid'),
        ),
    ]
