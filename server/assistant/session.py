"""Everything one member's token request is built from, in one function.

Shared by `POST /api/assistant/session` and `manage.py mint_assistant_token`,
so the operator's manual mint sends exactly what a browser's would.
"""

from __future__ import annotations

from datetime import datetime

from assistant import live
from assistant.companions import ResolvedCompanion, companion_for
from assistant.prompt import build_system_prompt
from assistant.surface import web_description, web_tools


def token_request_for(
    user, *, now: datetime, utc_offset_minutes: int | None
) -> tuple[dict, ResolvedCompanion]:
    """(the `auth_tokens` request body, the companion it speaks as)."""
    companion = companion_for(user)
    setup = live.build_setup(
        system_prompt=build_system_prompt(
            companion=companion,
            first_name=user.first_name,
            now=now,
            utc_offset_minutes=utc_offset_minutes,
        ),
        voice_name=companion.voice_name,
        declarations=live.function_declarations(web_tools(), web_description),
    )
    return live.build_token_request(setup, now), companion
