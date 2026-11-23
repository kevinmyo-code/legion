from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("claude_session", "0010_session_name_waiting"),
    ]

    operations = [
        migrations.AddField(
            model_name="claudetask",
            name="made_only",
            field=models.BooleanField(blank=True, null=True),
        ),
    ]
