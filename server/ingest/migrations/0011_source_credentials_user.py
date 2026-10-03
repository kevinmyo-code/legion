"""`source_credentials.user` (ADR 0052, web-revamp ticket 06; spec D3
"Canvas").

The member who handed a session over. Canvas rows the poller inserts from
then on are private to them (`ingest/canvas.py`, `0012`).

**The backfill names the household's ONE owner, and only when there is
exactly one.** Spec D3: "backfills it to the household's single `owner`
member". A household with two owners has no single answer, and guessing
would make one person's coursework private to the other; such a credential
stays unattributed (the poller then inserts shared rows, exactly as before
this migration) until someone hands the session over again, which records
who did.
"""

import django.db.models.deletion
from django.db import migrations, models


def backfill(apps, schema_editor):
    SourceCredential = apps.get_model("ingest", "SourceCredential")
    HouseholdMember = apps.get_model("household", "HouseholdMember")
    for credential in SourceCredential.objects.filter(user__isnull=True):
        owners = list(
            HouseholdMember.objects.filter(
                household_id=credential.household_id, role="owner"
            ).values_list("user_id", flat=True)[:2]
        )
        if len(owners) == 1:
            credential.user_id = owners[0]
            credential.save(update_fields=["user"])


class Migration(migrations.Migration):
    dependencies = [
        ("household", "0004_devicetoken_scope"),
        ("ingest", "0010_events_private_rows"),
    ]

    operations = [
        migrations.AddField(
            model_name="sourcecredential",
            name="user",
            field=models.ForeignKey(
                blank=True,
                null=True,
                on_delete=django.db.models.deletion.SET_NULL,
                related_name="+",
                to="household.user",
            ),
        ),
        migrations.RunPython(backfill, migrations.RunPython.noop),
    ]
