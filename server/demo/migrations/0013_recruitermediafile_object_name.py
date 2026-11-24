"""Supabase egress cut 4 (2026-11-23): a media file's bytes may live in a bucket, and this names
the object (`demo.media_store`). Additive: one column, blank on every existing row, which is
exactly what the revision before this one reads as "the bytes are in `data`". It carries a
DATABASE default, so the old code's INSERT, which does not name the column, still works: that is
the ship's old-code-on-new-schema rehearsal and the rollback that reloads the content. The `data`
column is untouched and still written, so a rollback serves the page unchanged."""

from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("demo", "0012_sparevisithousehold")]

    operations = [
        migrations.AddField(
            model_name="recruitermediafile",
            name="object_name",
            field=models.CharField(blank=True, db_default="", default="", max_length=200),
        ),
    ]
