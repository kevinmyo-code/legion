"""The private file's own list of US fixed bills, for safe-to-spend (ticket 07).

ADDITIVE: one JSON column on finance_policies, defaulting to an empty list.
"""

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("finance", "0001_initial"),
    ]

    operations = [
        migrations.AddField(
            model_name="financepolicy",
            name="fixed_bills",
            field=models.JSONField(blank=True, default=list),
        ),
    ]
