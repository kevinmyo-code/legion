"""Supabase egress cut 4 (2026-11-23): a media file's bytes may live in a bucket, and this names
the object (`demo.media_store`). Additive: one nullable-by-default column, blank on every existing
row, which is exactly what the revision before this one reads as "the bytes are in `data`". The
`data` column is untouched and still written, so a rollback serves the page unchanged."""

from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("demo", "0012_sparevisithousehold")]

    operations = [
        migrations.AddField(
            model_name="recruitermediafile",
            name="object_name",
            field=models.CharField(blank=True, default="", max_length=200),
        ),
    ]
