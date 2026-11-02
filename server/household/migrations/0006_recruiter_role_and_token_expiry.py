# recruiter-demo tickets 01 and 02 (Kevin, 2026-11-02, ADR 0068).
#
# ADDITIVE: one nullable column on `household_devicetoken` (`expires_at`, null
# = never, which is every existing token) and `householdmember.role` widened
# from 8 to 16 characters to fit `recruiter`. Widening a varchar rewrites no
# row and drops nothing; every existing role keeps its value.

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('household', '0005_household_timezone'),
    ]

    operations = [
        migrations.AddField(
            model_name='devicetoken',
            name='expires_at',
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AlterField(
            model_name='householdmember',
            name='role',
            field=models.CharField(choices=[('owner', 'owner'), ('member', 'member'), ('recruiter', 'recruiter')], default='member', max_length=16),
        ),
    ]
