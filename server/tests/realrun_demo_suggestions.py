# ruff: noqa: F811 - fixtures imported from other test modules
"""One real model round for a tapped chip whose answer the model words (demo stage 2, items 1/2c).
NOT part of the suite (pytest collects only `test_*.py`); it runs when named, with a key:

    LEGION_GEMINI_KEY_FILE=<path> pt.sh <worktree> <port> <db> tests/realrun_demo_suggestions.py -s

The chip's call is injected as the turn's first round (`cascade.ChipFirst`) with Gemini's
`skip_thought_signature_validator` in place of a thought signature; the model then words the
result. This proves the text model accepts that history (a Midcon review asked for one real call
before relying on it). A typed turn: no speech is paid for. About one cent.
"""

from __future__ import annotations

import json

import pytest
from django.core.cache import cache

from household.demo_seed import seed_household
from household.models import HouseholdMember
from tests.realrun_browser_cascade import real  # noqa: F401 - fixture
from tests.test_assistant_cascade import _events, _of, _post, visitor, visitor_user  # noqa: F401
from tests.test_profile_verbatim import _content
from tests.test_recruiter_content import load_fake

pytestmark = [pytest.mark.django_db, pytest.mark.live_model]


def test_realrun_a_worded_chip_is_answered_by_the_real_model(
    real, visitor, visitor_user, tmp_path
):
    load_fake(tmp_path, content=_content())
    seed_household(HouseholdMember.objects.get(user=visitor_user).household, created=True)
    cache.clear()
    greet = _events(_post(visitor, greet="true"))
    offer = _of(greet, "done")[-1]["offer"]
    chips = visitor.post("/api/demo/chips", {"offer": offer}, format="json").data["chips"]
    chip = next(c for c in chips if c["question"] == "Show me how LEGION is built.")
    events = _events(_post(visitor, text=chip["question"], chip=chip["token"]))
    tools = _of(events, "tool")
    said = [s["text"] for s in _of(events, "sentence")]
    done = _of(events, "done")[-1]
    summary = {"tools": [t["name"] for t in tools], "said": said, "done": done}
    print("CHIP REALRUN", json.dumps(summary)[:2000])
    assert not _of(events, "error"), _of(events, "error")
    assert tools and tools[0]["name"] == "show_diagram" and tools[0]["ok"] is True
    assert tools[0]["card"]["view"] == "containers"
    assert done["status"] == "done" and said
