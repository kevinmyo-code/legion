# Kevin's tester code: a demo code whose visitors are not counted against the daily budget.

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("demo", "0005_budget_web_searches"),
    ]

    operations = [
        migrations.AddField(
            model_name="democode",
            name="unmetered",
            field=models.BooleanField(default=False),
        ),
    ]
