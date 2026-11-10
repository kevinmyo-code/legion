# Architecture upgrade 2 (2026-11-10): the Claude worker's own token scope, `claude_worker`.
#
# ADDITIVE: the column widens from 8 to 16 characters and gains one choice. No row changes:
# every token keeps the scope it has, so the phone, the browser and the laptop worker's
# current `write` token all keep working until Kevin re-issues the worker's token.

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("household", "0008_demo_visit_delete_exemption"),
    ]

    operations = [
        migrations.AlterField(
            model_name="devicetoken",
            name="scope",
            field=models.CharField(
                choices=[
                    ("read", "read"),
                    ("write", "write"),
                    ("claude_worker", "claude_worker"),
                ],
                default="write",
                max_length=16,
            ),
        ),
    ]
