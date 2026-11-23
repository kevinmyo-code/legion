from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("claude_session", "0009_machine_can_mail"),
    ]

    operations = [
        migrations.AddField(
            model_name="claudetask",
            name="made_only",
            field=models.BooleanField(blank=True, null=True),
        ),
    ]
