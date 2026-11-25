"""Which companion a member talks to (web-assistant ticket 03).

Kevin, 2026-10-04: "her own companion. we already have dorothy. give that to
her." A member whose signup first name matches the seed below talks to Dorothy;
Kevin talks to his own companion, which on the engine is Alfred until the phone
pushes its roster up (ticket 03's "Kevin's phone pushes its companion roster
up"; not built - there is no write path yet beyond the admin and `manage.py
set_companion`).

A member's `Companion` row is the answer when there is one. When there is not
- a member who joined after the seeding migration ran - the companion is the
SEED below, computed, not stored: reading never writes. The seed is the ruling
written down in one place, so the migration and the fallback cannot disagree.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, replace

from django.db.models.functions import Now

from assistant import personas
from assistant.models import Companion

DEFAULT_PERSONA = personas.ALFRED.key


def first_name_digest(name: str) -> str:
    """The key `SEED_PERSONA_BY_FIRST_NAME_DIGEST` uses: SHA-256 of the
    normalised signup first name. The repo is public, so the names themselves
    are not written down here; the digest is what the map holds."""
    return hashlib.sha256(name.strip().lower().encode("utf-8")).hexdigest()


# Kevin's ruling, keyed on the name the member gave at signup (`User.first_name`,
# which `POST /api/auth/signup` fills from its `name` field), through
# `first_name_digest`. Keyed by digest rather than by the name because this file
# is public: a member's own first name is not the repo's to publish. The digest
# keeps the name out of the tree; it is not a secret, because a short first name
# falls to a name list, and git history still holds the plaintext. The ruling
# itself is unchanged - the same name resolves to the same companion as before.
#
# The map only ever decides for a member with NO `Companion` row (one who joined
# after the seeding migration ran). Once every member has a row, this map and
# `seed_persona_key` can go, and `resolve` can default straight to
# `DEFAULT_PERSONA`. That needs a count of rowless members, which is Kevin's
# read to make.
SEED_PERSONA_BY_FIRST_NAME_DIGEST = {
    "a6ae07ad556c5f9348cc09c16ed17a437e65acc71e689c1b19f872f1dab3c9c1": personas.DOROTHY.key,
}


def seed_persona_key(user) -> str:
    first = (getattr(user, "first_name", "") or "").strip().lower()
    return SEED_PERSONA_BY_FIRST_NAME_DIGEST.get(first_name_digest(first), DEFAULT_PERSONA)


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


def resolve(row: Companion | None, user, *, persona_key: str | None = None) -> ResolvedCompanion:
    if row is None:
        persona = personas.persona_for(persona_key or seed_persona_key(user))
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


RECRUITER_PERSONA = personas.ALFRED.key
# The recruiter demo's own copy of Alfred's delivery text, FROZEN at what `ALFRED.delivery` was at
# 679f7c15 (byte for byte). Members follow `personas.ALFRED` (the phone's twin, changed 2026-11-19);
# the public demo's prompt does not change until Kevin has heard the new text and said so
# (tests/test_profile_live_unchanged.py pins the live demo prompt). To change it: on Kevin's word,
# edit this constant and that test's pinned snapshot together.
RECRUITER_DELIVERY_FROZEN = (
    "This is heard, not read: no markdown, lists or stage directions, and short sentences. "
    "Read phone numbers, codes, VINs and order or confirmation numbers digit by digit; say "
    "money and everyday quantities as a person would, twelve forty and not one two four "
    "zero. Before an answer, one short word of acknowledgement at most, never a string of "
    "them; the short holding line before a slow tool still stands, worded fresh each time."
)


def recruiter_companion() -> ResolvedCompanion:
    """A recruiter's companion (recruiter-demo ticket 05, Kevin 2026-11-02:
    "same name, same voice as the phone"). Alfred, built exactly as the web
    builds Kevin's when no row overrides it: the persona's own name and its
    suggested voice. Fixed by role; a visitor has no row and cannot get one. Its delivery text
    is the demo's frozen copy (`RECRUITER_DELIVERY_FROZEN`)."""
    return replace(
        resolve(None, None, persona_key=RECRUITER_PERSONA), delivery=RECRUITER_DELIVERY_FROZEN
    )


def companion_for(user) -> ResolvedCompanion:
    """The signed-in member's companion. Scoped to their own household: a row
    for this user in another household (which the model forbids, and nothing
    creates) would be ignored rather than read. A recruiter gets
    `recruiter_companion()` whatever rows exist."""
    from household.models import is_recruiter

    if is_recruiter(user):
        return recruiter_companion()
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
