from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("claude_session", "0008_laptop_health"),
    ]

    operations = [
        migrations.AddField(
            model_name="claudemachine",
            name="can_mail",
            field=models.BooleanField(blank=True, null=True),
        ),
    ]
