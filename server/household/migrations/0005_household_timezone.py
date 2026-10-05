# Kevin, 2026-10-05: the household has a timezone.
#
# ADDITIVE: one nullable column on `public.household_household`, nothing
# dropped, nothing renamed, nothing in `private`. Deliberately sets NO value
# for any household: the owner chooses it once (Settings > Household, or
# `manage.py set_household_timezone`). Until then every rule that reads it
# falls back exactly as it did before this column existed.

import household.models
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('household', '0004_devicetoken_scope'),
    ]

    operations = [
        migrations.AddField(
            model_name='household',
            name='timezone',
            field=models.CharField(blank=True, max_length=64, null=True, validators=[household.models.validate_zone_name]),
        ),
    ]
