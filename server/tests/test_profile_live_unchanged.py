# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Live, the page's one-command voice rollback, is untouched by the cascade's spoken profile.

Under Live nothing can speak for the model, so its `about_kevin` must be exactly what it was at
421306f0: the declaration Live is given, the tool's result and the recruiter's system prompt are
compared byte for byte with `fixtures/about_kevin_live_421306f0.json`, which this same file wrote
when run on 421306f0 with `PV_GOLDEN_WRITE=1`. Invented profile only.
"""

from __future__ import annotations

import json
import os
from datetime import UTC, datetime
from pathlib import Path

import pytest

from tests.test_assistant_cascade import visitor_user  # noqa: F401 - fixture
from tests.test_recruiter_content import fake_content, load_fake

pytestmark = pytest.mark.django_db

GOLDEN = Path(__file__).parent / "fixtures" / "about_kevin_live_421306f0.json"
PROFILE = {
    "Location": "Lives in Fakeville, Example State.",
    "Education": "B.S. Information Systems, Example State University.\nGraduating in 2031.",
    "Experience": "Analyst intern at Fabrikam Example Co. Built ten pretend dashboards.",
}


def _live(visitor_user) -> dict:
    from assistant import live
    from assistant.session import system_prompt_for
    from assistant.surface import tools_for, web_description
    from demo.tools import ABOUT_KEVIN

    declarations = live.function_declarations(tools_for(visitor_user), web_description)

    class Request:
        user = visitor_user

    prompt, _companion = system_prompt_for(
        visitor_user, now=datetime(2031, 3, 4, 5, 6, tzinfo=UTC), utc_offset_minutes=-360
    )
    return {
        "declaration": next(d for d in declarations if d.get("name") == "about_kevin"),
        "result": ABOUT_KEVIN.handler(Request(), {}).text,
        "prompt": prompt,
    }


def test_live_about_kevin_and_the_recruiter_prompt_are_as_at_421306f0(tmp_path, visitor_user):
    content = fake_content()
    content["profile"] = PROFILE
    load_fake(tmp_path, content=content)
    now = _live(visitor_user)
    if os.environ.get("PV_GOLDEN_WRITE"):
        GOLDEN.write_text(json.dumps(now, indent=1, ensure_ascii=False), encoding="utf-8")
    assert now == json.loads(GOLDEN.read_text(encoding="utf-8"))
