from django.db import migrations, models


def drop_demo_callback_rows(apps, schema_editor):
    """On the way back only: the old kind constraint does not allow these rows."""
    apps.get_model("push", "PushSent").objects.filter(kind="demo_callback").delete()


class Migration(migrations.Migration):
    dependencies = [
        ("push", "0003_laptop_health"),
    ]

    operations = [
        migrations.RemoveConstraint(
            model_name="pushsent",
            name="push_sent_kind_valid",
        ),
        # Reversed after the new constraint is dropped and before the old one is put back.
        migrations.RunPython(migrations.RunPython.noop, drop_demo_callback_rows),
        migrations.AddField(
            model_name="pushpreference",
            name="demo_callback",
            field=models.BooleanField(db_default=True, default=True),
        ),
        migrations.AddConstraint(
            model_name="pushsent",
            constraint=models.CheckConstraint(
                condition=models.Q(
                    (
                        "kind__in",
                        (
                            "list_changes",
                            "event_reminders",
                            "task_due_morning",
                            "game_invite",
                            "laptop_health",
                            "demo_callback",
                        ),
                    )
                ),
                name="push_sent_kind_valid",
            ),
        ),
    ]
