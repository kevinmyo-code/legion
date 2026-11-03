# recruiter-demo ticket 13 (Kevin, 2026-11-02, ADR 0069).
#
# ADDITIVE: one boolean column on `household_household`, false for every
# existing household. Set true only by `POST /api/demo/enter` on the
# throwaway household it makes for one recruiter visit.

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('household', '0006_recruiter_role_and_token_expiry'),
    ]

    operations = [
        migrations.AddField(
            model_name='household',
            name='is_demo_visit',
            field=models.BooleanField(default=False, editable=False),
        ),
    ]
