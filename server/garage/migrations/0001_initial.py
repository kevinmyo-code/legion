# Garage assistant (option B, 2026-11-20): the owner's checked fastener specs and his truck
# profile, loaded from a private folder outside the repo. Two new tables only.

from django.db import migrations, models


class Migration(migrations.Migration):
    initial = True

    dependencies = []

    operations = [
        migrations.CreateModel(
            name="FastenerSpec",
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
                ("model", models.CharField(max_length=48)),
                ("fastener_id", models.CharField(max_length=120)),
                ("torque_min", models.DecimalField(decimal_places=1, max_digits=6)),
                (
                    "torque_max",
                    models.DecimalField(blank=True, decimal_places=1, max_digits=6, null=True),
                ),
                (
                    "unit",
                    models.CharField(
                        choices=[
                            ("ft-lb", "foot-pounds"),
                            ("in-lb", "inch-pounds"),
                            ("N-m", "newton-metres"),
                        ],
                        max_length=8,
                    ),
                ),
                ("socket", models.CharField(blank=True, default="", max_length=60)),
                ("thread", models.CharField(blank=True, default="", max_length=60)),
                ("year_from", models.PositiveSmallIntegerField()),
                ("year_to", models.PositiveSmallIntegerField()),
                ("engine", models.CharField(max_length=20)),
                ("axle", models.CharField(max_length=40)),
                (
                    "hardware",
                    models.CharField(
                        choices=[("stock", "the stock part"), ("kit", "an aftermarket kit's part")],
                        max_length=8,
                    ),
                ),
                ("kit_name", models.CharField(blank=True, default="", max_length=120)),
                (
                    "source_kind",
                    models.CharField(
                        choices=[
                            ("fsm", "factory service manual"),
                            ("reprint", "licensed factory manual reprint"),
                            ("alldata", "ALLDATA"),
                            ("kit_instructions", "the kit maker's instructions"),
                        ],
                        max_length=20,
                    ),
                ),
                ("source", models.CharField(max_length=200)),
                ("source_page", models.CharField(max_length=200)),
                ("verified_on", models.DateField()),
                ("loaded_at", models.DateTimeField(auto_now=True)),
            ],
            options={
                "db_table": "garage_fastener_specs",
                "ordering": ["model", "fastener_id"],
                "constraints": [
                    models.UniqueConstraint(
                        fields=("model", "fastener_id"), name="garage_spec_unique"
                    ),
                    models.CheckConstraint(
                        condition=models.Q(
                            ("torque_max__isnull", True),
                            ("torque_max__gte", models.F("torque_min")),
                            _connector="OR",
                        ),
                        name="garage_spec_range",
                    ),
                    models.CheckConstraint(
                        condition=models.Q(("year_to__gte", models.F("year_from"))),
                        name="garage_spec_years",
                    ),
                ],
            },
        ),
        migrations.CreateModel(
            name="GarageTruck",
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
                ("model", models.CharField(max_length=48, unique=True)),
                ("year", models.PositiveSmallIntegerField()),
                ("engine", models.CharField(max_length=20)),
                ("axle", models.CharField(max_length=40)),
                ("lift_in", models.DecimalField(decimal_places=1, max_digits=3)),
                ("kit_fasteners", models.JSONField(default=list)),
                ("loaded_at", models.DateTimeField(auto_now=True)),
            ],
            options={
                "db_table": "garage_trucks",
            },
        ),
    ]
