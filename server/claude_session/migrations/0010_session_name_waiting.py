from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("claude_session", "0009_machine_can_mail"),
    ]

    operations = [
        migrations.AddField(
            model_name="claudecodesession",
            name="name",
            field=models.TextField(blank=True, db_default="", default=""),
        ),
        migrations.AddField(
            model_name="claudecodesession",
            name="waiting_since",
            field=models.DateTimeField(blank=True, null=True),
        ),
    ]
