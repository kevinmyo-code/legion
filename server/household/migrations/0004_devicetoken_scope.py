# engine-mcp ticket 05 (Kevin, 2026-10-02): a read/write scope on device tokens.
#
# ADDITIVE: one new NOT NULL column with a default, nothing dropped, nothing
# renamed. Every token that exists when this runs gets `write`, which is exactly
# what it could do before the column existed - so the phone, the browser and
# every other signed-in device keep working with no re-login. Only a token
# minted afterwards with `scope="read"` (`manage.py issue_device_token`, which
# defaults to read) is narrower.

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("household", "0003_invite"),
    ]

    operations = [
        migrations.AddField(
            model_name="devicetoken",
            name="scope",
            field=models.CharField(
                choices=[("read", "read"), ("write", "write")],
                default="write",
                max_length=8,
            ),
        ),
    ]
