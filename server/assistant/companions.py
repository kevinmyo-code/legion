"""Which companion a member talks to (web-assistant ticket 03).

Kevin, 2026-10-04: "her own companion. we already have dorothy. give that to
her." Mia talks to Dorothy; Kevin talks to his own companion, which on the
engine is Alfred until the phone pushes its roster up (ticket 03's "Kevin's
phone pushes its companion roster up"; not built - there is no write path yet
beyond the admin and `manage.py set_companion`).

A member's `Companion` row is the answer when there is one. When there is not
- a member who joined after the seeding migration ran - the companion is the
SEED below, computed, not stored: reading never writes. The seed is the ruling
written down in one place, so the migration and the fallback cannot disagree.
"""

from __future__ import annotations

from dataclasses import dataclass

from django.db.models.functions import Now

from assistant import personas
from assistant.models import Companion

DEFAULT_PERSONA = personas.ALFRED.key

# Kevin's ruling, keyed on the name the member gave at signup (`User.first_name`,
# which `POST /api/auth/signup` fills from its `name` field). Lower-cased.
SEED_PERSONA_BY_FIRST_NAME = {"mia": personas.DOROTHY.key}


def seed_persona_key(user) -> str:
    first = (getattr(user, "first_name", "") or "").strip().lower()
    return SEED_PERSONA_BY_FIRST_NAME.get(first, DEFAULT_PERSONA)


@dataclass(frozen=True)
class ResolvedCompanion:
    name: str
    persona_key: str
    # The register, with the companion's own name in it.
    clause: str
    delivery: str
    voice_name: str
    # False when no row exists and this is the seed, computed.
    stored: bool
    # True when the household wrote its own register (`persona_fragment`).
    custom_register: bool = False


def resolve(row: Companion | None, user) -> ResolvedCompanion:
    if row is None:
        persona = personas.persona_for(seed_persona_key(user))
        return ResolvedCompanion(
            name=persona.default_name,
            persona_key=persona.key,
            clause=persona.clause,
            delivery=persona.delivery,
            voice_name=persona.suggested_voice,
            stored=False,
        )
    persona = personas.persona_for(row.persona_key)
    name = row.name.strip() or persona.default_name
    custom = row.persona_fragment.strip()
    return ResolvedCompanion(
        name=name,
        persona_key=persona.key,
        # A custom register is the household's own words and is used as
        # written; a built-in one gets the renamed companion's name.
        clause=custom or personas.with_name(persona.clause, persona, name),
        delivery="" if custom else persona.delivery,
        voice_name=row.voice_name.strip() or persona.suggested_voice,
        stored=True,
        custom_register=bool(custom),
    )


def companion_for(user) -> ResolvedCompanion:
    """The signed-in member's companion. Scoped to their own household: a row
    for this user in another household (which the model forbids, and nothing
    creates) would be ignored rather than read."""
    household = user.household
    row = None
    if household is not None:
        row = Companion.objects.filter(user=user, household=household).first()
    return resolve(row, user)


def set_companion(user, persona_key: str, *, name: str = "", voice_name: str = "") -> Companion:
    """Create or replace a member's companion. Used by `manage.py
    set_companion` and the admin's save; the REST write path is not built."""
    if persona_key not in personas.BY_KEY:
        raise ValueError(
            f"{persona_key!r} is not a built-in companion. Built-in: "
            f"{', '.join(personas.BY_KEY)}."
        )
    household = user.household
    if household is None:
        raise ValueError(f"{user.email} belongs to no household, so has no companion to set.")
    persona = personas.BY_KEY[persona_key]
    row, _ = Companion.objects.update_or_create(
        user=user,
        defaults={
            "household": household,
            "name": name.strip() or persona.default_name,
            "persona_key": persona_key,
            "persona_fragment": "",
            "voice_name": voice_name.strip(),
            "updated_at": Now(),
        },
    )
    return row
