# recruiter-demo ticket 13 (ADR 0069). ADDITIVE: one nullable foreign key,
# null on every existing visit (they joined the shared demo household).

import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('demo', '0003_daily_budget'),
        ('household', '0007_household_is_demo_visit'),
    ]

    operations = [
        migrations.AddField(
            model_name='demovisit',
            name='household',
            field=models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='+', to='household.household'),
        ),
    ]
