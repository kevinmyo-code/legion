from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("claude_session", "0011_task_made_only"),
        ("household", "0009_devicetoken_claude_worker_scope"),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.AddField(
            model_name="claudetask",
            name="session_id",
            field=models.TextField(blank=True, db_default="", default=""),
        ),
        migrations.AddField(
            model_name="claudetask",
            name="session_text",
            field=models.TextField(blank=True, null=True),
        ),
        migrations.AddIndex(
            model_name="claudetask",
            index=models.Index(
                fields=["household", "session_id", "-created_at"],
                name="claude_tasks_session",
            ),
        ),
    ]
