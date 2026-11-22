"""A relayed diagnostics read is answered in the device's own words (engine-brain plan 1119,
inventory row 18; the phone's `VerbatimSpeech` + `DiagFilter`). The phone's `get_codes` result
carries `speak_verbatim` and `verbatim_from`; the engine says that line word for word, the model is
told it was said, and the model's own sentences that restate it are not said. Fake model, fake
limb; every code, name and line invented."""

# ruff: noqa: F811 - the fixtures are imported from the relay and voice turn tests

from __future__ import annotations

import json

import pytest
from django.core.cache import cache

from agents.legion import limb_verbatim
from agents.models import AgentTurn
from agents.runtime import model as m
from tests.test_agents_relay_api import (  # noqa: F401
    TOOLS,
    attach,
    calls,
    events_of,
    of,
    paused,
    post_result,
)
from tests.test_engine_voice_turn import engine  # noqa: F401

pytestmark = pytest.mark.django_db

GET_CODES = {
    "name": "get_codes",
    "tool_class": "vehicle",
    "description": "Read the car's stored trouble codes.",
    "parameters": {"type": "object", "properties": {"all_modules": {"type": "boolean"}}},
    "writes": False,
    "read_through": False,
    "actuates": False,
}
SAID = "One stored engine code: P0420, catalyst efficiency below threshold, bank 1."


@pytest.fixture
def codes_limb(auth_client, engine):
    cache.clear()
    return attach(auth_client, [*TOOLS, GET_CODES])


def codes_result(said=SAID, made_by="get_codes", **more) -> dict:
    return {
        "success": True,
        "codes": ["P0420"],
        "speak_verbatim": said,
        "verbatim_from": made_by,
        **more,
    }


def answered(auth_client, engine, result, *, name="get_codes", outcome="ok", reply=None):
    _e, turn_id, requests = paused(engine, auth_client, m.call(name, {}), text="any trouble codes")
    engine.rounds = [reply or m.say("Clean bill. P0420 is stored. Want me to look that code up?")]
    rest = events_of(
        post_result(
            auth_client,
            turn_id,
            requests[0]["call_id"],
            outcome=outcome,
            result=result,
            words="Read.",
        )
    )
    return turn_id, rest


def said(events) -> list[str]:
    return [e["text"] for e in of(events, "sentence")]


def test_the_devices_diagnostics_line_is_said_word_for_word_and_restatements_are_not(
    auth_client, codes_limb, engine
):
    turn_id, rest = answered(auth_client, engine, codes_result())
    assert said(rest) == [SAID, "Want me to look that code up?"]
    done = of(rest, "done")[0]
    assert done["final_text"] == f"{SAID} Want me to look that code up?"
    history = AgentTurn.objects.get(pk=turn_id).history
    assert history[-1] == {"role": "model", "parts": [{"text": done["final_text"]}]}


def test_the_model_is_told_the_line_was_said_and_not_handed_it_to_say_again(
    auth_client, codes_limb, engine
):
    answered(auth_client, engine, codes_result())
    contents = engine.models[-1].requests[-1].contents
    response = [
        p["functionResponse"]["response"]
        for c in contents
        for p in c.get("parts") or []
        if p.get("functionResponse", {}).get("name") == "get_codes"
    ][-1]
    assert response["success"] is True
    assert limb_verbatim.SAID_NOTE.split(":")[0] in response["message"]
    assert SAID in response["message"]
    assert "speak_verbatim" not in response["message"]


@pytest.mark.parametrize(
    "case",
    ["other_maker", "blank", "too_long", "not_a_dict", "failed", "timed_out"],
)
def test_a_result_that_does_not_qualify_is_handed_to_the_model_as_before(
    auth_client, codes_limb, engine, case
):
    result = {
        "other_maker": codes_result(made_by="get_balance"),
        "blank": codes_result(said="  "),
        "too_long": codes_result(said="P0420. " * 200),
        "not_a_dict": None,
        "failed": codes_result(),
        "timed_out": codes_result(),
    }[case]
    outcome = case if case in ("failed", "timed_out") else "ok"
    extra = {} if result is None else {"result": result}
    _e, turn_id, requests = paused(engine, auth_client, m.call("get_codes", {}), text="any codes")
    engine.rounds = [m.say("Clean bill.")]
    rest = events_of(
        post_result(
            auth_client, turn_id, requests[0]["call_id"], outcome=outcome, words="Read.", **extra
        )
    )
    assert SAID not in said(rest)
    assert "P0420. P0420." not in " ".join(said(rest))
    if case not in ("failed", "timed_out"):
        # No device line was said, so the model's verdict is not filtered.
        assert "Clean bill." in said(rest)


def test_only_an_allowlisted_limb_tool_speaks_its_own_words(auth_client, codes_limb, engine):
    _turn, rest = answered(
        auth_client, engine, codes_result(made_by="car_status"), name="car_status"
    )
    assert SAID not in said(rest)
    assert "Clean bill." in said(rest)


def test_the_filter_holds_across_a_second_pause(auth_client, codes_limb, engine):
    _e, turn_id, requests = paused(engine, auth_client, m.call("get_codes", {}), text="any codes")
    engine.rounds = [m.call("car_status", {})]
    second = events_of(
        post_result(
            auth_client, turn_id, requests[0]["call_id"], result=codes_result(), words="Read."
        )
    )
    assert said(second) == [SAID]
    again = of(second, "tool_request")
    assert [r["name"] for r in again] == ["car_status"]
    engine.rounds = [m.say("All good. The engine is warm.")]
    rest = events_of(post_result(auth_client, turn_id, again[0]["call_id"], words="Engine warm."))
    assert said(rest) == ["The engine is warm."]
    assert of(rest, "done")[0]["final_text"] == f"{SAID} The engine is warm."


def test_a_verbatim_line_and_a_restatement_in_the_same_batch_round(auth_client, codes_limb, engine):
    _e, turn_id, requests = paused(
        engine, auth_client, calls(("get_codes", {}), ("car_status", {})), text="codes and status"
    )
    assert [r["name"] for r in requests] == ["get_codes", "car_status"]
    engine.rounds = [m.say("No problems. The engine is warm.")]
    first = post_result(
        auth_client, turn_id, requests[0]["call_id"], result=codes_result(), words="Read."
    )
    assert first.status_code == 202
    rest = events_of(post_result(auth_client, turn_id, requests[1]["call_id"], words="Warm."))
    assert said(rest) == [SAID, "The engine is warm."]


# ---- the restatement rule: the phone's DiagFilterTest cases, mirrored ---------------------------


@pytest.mark.parametrize(
    "sentence",
    [
        "Clean bill.",
        "The engine's clear.",
        "All good.",
        "Nothing wrong there.",
        "It's fine.",
        "Seven modules answered.",
        "3 module(s) answered.",
        "P0420 is still there.",
        "P zero four two zero again.",
        "p 0 4 2 0.",
        "Looks healthy.",
        "No problems.",
        "One stored code, P-zero-four-twenty, for catalyst efficiency.",
        "Checking.",
        "On it.",
    ],
)
def test_codes_counts_verdicts_and_bare_fillers_are_restatements(sentence):
    assert limb_verbatim.restates_result(sentence)


@pytest.mark.parametrize(
    "sentence",
    [
        "Want me to scan every module?",
        "I can look one of them up.",
        "Say the word if you want more.",
        "Is the key at on?",
    ],
)
def test_a_follow_up_that_adds_nothing_about_the_result_passes(sentence):
    assert not limb_verbatim.restates_result(sentence)


def test_the_allowlist_lives_in_the_limb_contract_file():
    raw = json.loads(limb_verbatim.CLASSES_FILE.read_text(encoding="utf-8"))
    assert raw["limb_verbatim_tools"] == ["get_codes"]
    assert frozenset(raw["limb_verbatim_tools"]) == limb_verbatim.TOOLS


# ---- review 1121: safety words survive, only a bare verdict is a restatement (V1) --------------


@pytest.mark.parametrize(
    "sentence",
    [
        "If the light starts flashing it's not okay to drive: pull over.",
        "It's fine for now, but stop driving if it starts to overheat.",
        "Don't drive it far, the oil pressure looks fine but the code is real.",
        "Do not drive it if you see smoke.",
        "If the temperature warning comes on, it's not okay to keep going.",
        "It's overheating, so the brakes are fine but pull over now.",
        "P0420 is fine to watch, but if the brakes feel soft, stop.",
    ],
)
def test_a_sentence_with_a_safety_cue_is_never_dropped(sentence):
    assert not limb_verbatim.restates_result(sentence)


def test_the_safety_cues_live_in_one_list():
    assert {"pull over", "stop driving", "don't drive", "do not drive", "flashing"} <= set(
        limb_verbatim.SAFETY_CUES
    )
    assert {"overheating", "overheat", "smoke", "brakes", "oil pressure"} <= set(
        limb_verbatim.SAFETY_CUES
    )
    assert "temperature warning" in limb_verbatim.SAFETY_CUES


@pytest.mark.parametrize(
    "sentence",
    [
        "It's clear tonight, so the drive home should be easy.",
        "I can clear them if you say yes.",
        "Your oil change is fine to leave until next month.",
        "Okay, I'll keep an eye on it next time you ask.",
    ],
)
def test_a_verdict_word_inside_other_content_is_not_a_restatement(sentence):
    assert not limb_verbatim.restates_result(sentence)


def test_a_safety_warning_after_the_codes_is_said(auth_client, codes_limb, engine):
    warning = "If the light starts flashing it's not okay to drive: pull over."
    _turn, rest = answered(
        auth_client,
        engine,
        codes_result(),
        reply=m.say(f"All good. {warning} I can clear them if you say yes."),
    )
    assert said(rest) == [SAID, warning, "I can clear them if you say yes."]


# ---- review 1121: the device line passes the money and torque checks (V2) ----------------------


@pytest.mark.parametrize(
    "line",
    [
        "One stored code, P0455: a gas cap costs about $25.",
        "One stored code, P0455: a new cap is about twenty five dollars.",
        "One stored code, P0420: torque the sensor to 30 ft-lbs.",
    ],
)
def test_a_device_line_stating_money_or_torque_falls_back_to_the_model(
    auth_client, codes_limb, engine, line
):
    _turn, rest = answered(auth_client, engine, codes_result(said=line), reply=m.say("Clean bill."))
    assert line not in said(rest)
    # No device line was said, so nothing filters the model either.
    assert said(rest)[0] == "Clean bill."
