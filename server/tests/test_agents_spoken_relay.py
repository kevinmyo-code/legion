"""Engine-brain slice E3 over the relay (ADR 0098): a continuation's sentences pass the same
checks as the first stream's. Fake model, fake limb; every name invented."""

# ruff: noqa: F811 - the fixtures are imported from the relay and voice turn tests

from __future__ import annotations

import pytest

from agents.runtime import model as m
from tests.test_agents_relay_api import (  # noqa: F401
    events_of,
    limb,
    of,
    paused,
    post_result,
)
from tests.test_engine_voice_turn import engine  # noqa: F401

pytestmark = pytest.mark.django_db


def test_a_continuation_opening_on_filler_drops_it_like_the_first_stream(auth_client, limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("set_timer", {"seconds": 5}))
    engine.rounds = [m.say("One moment. Your timer rings in five seconds.")]
    rest = events_of(post_result(auth_client, turn_id, requests[0]["call_id"]))
    said = [e["text"] for e in of(rest, "sentence")]
    assert "One moment." not in said
    assert said[0] == "Your timer rings in five seconds."
