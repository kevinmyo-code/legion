"""Give every existing member their companion (web-assistant ticket 03).

Kevin, 2026-10-04: Mia talks to Dorothy, Kevin to his own companion (Alfred
on the engine until the phone pushes its roster up). The rule itself lives in
`assistant/companions.py` (`seed_persona_key`) and the persona names in
`assistant/personas.py`, copied from `app/.../ai/Personas.kt` under a drift
test; this migration imports both rather than restating them, so the rows it
writes and the default a later member is given on read cannot disagree.

Rows store the persona KEY, not its text, so a later edit to a register
reaches every member wearing it. Idempotent: a member who already has a row
is left alone. Reversing deletes only rows still exactly as seeded.
"""

from django.db import migrations


def seed(apps, schema_editor):
    from assistant.companions import seed_persona_key
    from assistant.personas import BY_KEY

    HouseholdMember = apps.get_model("household", "HouseholdMember")
    Companion = apps.get_model("assistant", "Companion")
    for member in HouseholdMember.objects.select_related("user"):
        if Companion.objects.filter(user_id=member.user_id).exists():
            continue
        key = seed_persona_key(member.user)
        Companion.objects.create(
            household_id=member.household_id,
            user_id=member.user_id,
            name=BY_KEY[key].default_name,
            persona_key=key,
        )


def unseed(apps, schema_editor):
    from assistant.personas import BY_KEY

    Companion = apps.get_model("assistant", "Companion")
    for key, persona in BY_KEY.items():
        Companion.objects.filter(
            persona_key=key, name=persona.default_name, persona_fragment="", voice_name=""
        ).delete()


class Migration(migrations.Migration):
    dependencies = [
        ("assistant", "0001_initial"),
        ("household", "0004_devicetoken_scope"),
    ]

    operations = [migrations.RunPython(seed, unseed)]
