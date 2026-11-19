import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ("demo", "0009_funnel_counts"),
    ]

    operations = [
        migrations.CreateModel(
            name="RecruiterMediaFile",
            fields=[
                (
                    "id",
                    models.BigAutoField(
                        auto_created=True, primary_key=True, serialize=False, verbose_name="ID"
                    ),
                ),
                ("name", models.CharField(max_length=80)),
                ("data", models.BinaryField()),
                ("content_type", models.CharField(max_length=100)),
                ("sha256", models.CharField(max_length=64)),
                (
                    "content",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="media_files",
                        to="demo.recruitercontent",
                    ),
                ),
            ],
            options={
                "constraints": [
                    models.UniqueConstraint(
                        fields=("content", "name"), name="demo_media_file_unique"
                    )
                ],
            },
        ),
    ]
