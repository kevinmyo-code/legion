"""`checklists.owner_user` becomes on_delete=RESTRICT (spec D3, corrected
2026-10-03): a user who owns private lists cannot be hard-deleted, because
SET_NULL would make them shared. State only for the database: Django's
foreign key carries no ON DELETE clause (NO ACTION, deferred), which already
refuses the delete at commit; RESTRICT makes Django refuse it first, in words.
"""

import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('checklists', '0003_private_rows'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.AlterField(
            model_name='checklist',
            name='owner_user',
            field=models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.RESTRICT, related_name='+', to=settings.AUTH_USER_MODEL),
        ),
    ]
