"""Everything one member's token request (and the cascade's prompt) is built from.

Shared by `POST /api/assistant/session` and `manage.py mint_assistant_token`,
so the operator's manual mint sends exactly what a browser's would.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from assistant import live
from assistant.companions import ResolvedCompanion, companion_for
from assistant.prompt import build_system_prompt_parts
from assistant.surface import tools_for, web_description
from demo.budget import max_session_minutes
from demo.shop import scenario as shop_scenario
from household.models import is_recruiter
from household.timezones import utc_offset_minutes as zone_offset_minutes


def system_prompt_parts_for(
    user, *, now: datetime, utc_offset_minutes: int | None, cascade: bool = False
) -> tuple[str, str, ResolvedCompanion]:
    """(the prompt's stable body, its clock-and-speaker tail, the companion it speaks as): the
    one prompt both voice engines use (Live joins the two, `system_prompt_for`; the cascade
    caches the body and sends the tail per turn, ADR 0102).

    The browser's own offset wins. Without one, the household's timezone
    (when its owner has set one) supplies the CURRENT offset at `now` - a
    number, so daylight saving is right on the day. The zone's name never
    enters the prompt (CLAUDE.md section 1: it made the assistant talk about
    Chicago to a man in Houston)."""
    companion = companion_for(user)
    from_household = False
    if utc_offset_minutes is None:
        household = user.household
        utc_offset_minutes = zone_offset_minutes(
            household.timezone if household is not None else None, now
        )
        from_household = utc_offset_minutes is not None
    recruiter = is_recruiter(user)
    body, tail = build_system_prompt_parts(
        companion=companion,
        first_name=user.first_name,
        now=now,
        utc_offset_minutes=utc_offset_minutes,
        offset_from_household=from_household,
        recruiter=recruiter,
        cascade=cascade,
        # A member's turn asks nothing more: only a visitor has a visit to be in a scenario.
        shop=cascade and recruiter and shop_scenario.active(user),
    )
    return body, tail, companion


def system_prompt_for(
    user, *, now: datetime, utc_offset_minutes: int | None
) -> tuple[str, ResolvedCompanion]:
    """(the whole system prompt, the companion): what a Live token carries."""
    body, tail, companion = system_prompt_parts_for(
        user, now=now, utc_offset_minutes=utc_offset_minutes
    )
    return f"{body}\n\n{tail}", companion


def token_request_for(
    user, *, now: datetime, utc_offset_minutes: int | None
) -> tuple[dict, ResolvedCompanion]:
    """(the `auth_tokens` request body, the companion it speaks as)."""
    prompt, companion = system_prompt_for(user, now=now, utc_offset_minutes=utc_offset_minutes)
    setup = live.build_setup(
        system_prompt=prompt,
        voice_name=companion.voice_name,
        declarations=live.function_declarations(tools_for(user), web_description),
    )
    # A recruiter's conversation is capped (Kevin, 2026-11-02): the token dies
    # at the cap, and the mint reserved exactly that many minutes
    # (`demo/budget.py`). A member keeps the 30-minute backstop.
    lifetime = (
        timedelta(minutes=max_session_minutes()) if is_recruiter(user) else live.TOKEN_LIFETIME
    )
    return live.build_token_request(setup, now, lifetime=lifetime), companion
