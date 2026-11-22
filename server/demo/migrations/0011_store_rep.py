from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):
    dependencies = [
        ("demo", "0010_recruitermediafile"),
    ]

    operations = [
        migrations.AddField(
            model_name="demovisit",
            name="scenario",
            field=models.CharField(blank=True, db_default="", default="", max_length=16),
        ),
        migrations.CreateModel(
            name="DemoCallbackDay",
            fields=[
                (
                    "id",
                    models.BigAutoField(
                        auto_created=True, primary_key=True, serialize=False, verbose_name="ID"
                    ),
                ),
                ("day", models.DateField(unique=True)),
                ("count", models.IntegerField(default=0)),
            ],
            options={
                "constraints": [
                    models.CheckConstraint(
                        condition=models.Q(("count__gte", 0)), name="demo_callback_day_nonneg"
                    )
                ],
            },
        ),
        migrations.CreateModel(
            name="CallbackRequest",
            fields=[
                (
                    "id",
                    models.BigAutoField(
                        auto_created=True, primary_key=True, serialize=False, verbose_name="ID"
                    ),
                ),
                ("ref", models.CharField(blank=True, default="", max_length=64)),
                ("address_key", models.CharField(blank=True, default="", max_length=64)),
                ("name", models.CharField(max_length=60)),
                ("contact", models.CharField(max_length=120)),
                ("note", models.CharField(blank=True, default="", max_length=280)),
                ("summary", models.CharField(blank=True, default="", max_length=400)),
                ("delivery", models.CharField(blank=True, default="", max_length=40)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                (
                    "visit",
                    models.ForeignKey(
                        null=True,
                        on_delete=django.db.models.deletion.SET_NULL,
                        related_name="callbacks",
                        to="demo.demovisit",
                    ),
                ),
            ],
        ),
        migrations.CreateModel(
            name="ShopOrder",
            fields=[
                (
                    "id",
                    models.BigAutoField(
                        auto_created=True, primary_key=True, serialize=False, verbose_name="ID"
                    ),
                ),
                ("ref", models.CharField(max_length=16)),
                ("status", models.CharField(default="quoted", max_length=8)),
                ("lines", models.JSONField()),
                ("bundle_saving_cents", models.IntegerField(default=0)),
                ("shipping_cents", models.IntegerField(default=0)),
                ("total_cents", models.IntegerField()),
                ("conversation_id", models.UUIDField(blank=True, null=True)),
                ("quoted_turn", models.IntegerField(blank=True, null=True)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                ("placed_at", models.DateTimeField(blank=True, null=True)),
                (
                    "visit",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="shop_orders",
                        to="demo.demovisit",
                    ),
                ),
            ],
            options={
                "constraints": [
                    models.CheckConstraint(
                        condition=models.Q(("total_cents__gte", 0)),
                        name="demo_shop_order_total_nonneg",
                    ),
                    models.CheckConstraint(
                        condition=models.Q(("status__in", ("quoted", "placed"))),
                        name="demo_shop_order_status_valid",
                    ),
                ],
            },
        ),
    ]
