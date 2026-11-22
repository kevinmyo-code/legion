"""demo-enter-slow (2026-11-22): spare visit households seeded ahead of time (`demo.spares`).
Additive: one new table; nothing existing changes, and the revision before this one never reads
it."""

import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ("demo", "0011_store_rep"),
        ("household", "0009_devicetoken_claude_worker_scope"),
    ]

    operations = [
        migrations.CreateModel(
            name="SpareVisitHousehold",
            fields=[
                (
                    "id",
                    models.BigAutoField(
                        auto_created=True,
                        primary_key=True,
                        serialize=False,
                        verbose_name="ID",
                    ),
                ),
                ("seeded_for", models.DateField()),
                ("seed_version", models.CharField(max_length=64)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                (
                    "household",
                    models.OneToOneField(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="+",
                        to="household.household",
                    ),
                ),
            ],
            options={
                "indexes": [
                    models.Index(
                        fields=["seeded_for", "seed_version"],
                        name="demo_sparev_seeded__c74e56_idx",
                    )
                ],
            },
        ),
    ]
