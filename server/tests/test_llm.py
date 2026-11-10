"""`llm/` (architecture review 2026-11-10, item 15 and Q9): retry with backoff, usage
accounting in `llm_usage`, the model config route, and the member daily cap. Google is never
called; made-up values only."""
from __future__ import annotations

import json
import urllib.error

import pytest
from rest_framework.test import APIClient

from assistant import live
from ingest.drive import Response
from ingest.statements import GEMINI_KEY_ENV
from llm import cap, client
from llm.config import MODELS
from llm.models import LlmUsage
from tests.test_assistant import KEY, TOKEN, _start, browser  # noqa: F401

pytestmark = pytest.mark.django_db


@pytest.fixture(autouse=True)
def no_sleep(monkeypatch):
    slept = []
    monkeypatch.setattr(client, "_sleep", slept.append)
    return slept


def _scripted(*replies):
    calls = []

    def send(method, url, headers, body):
        calls.append(url)
        reply = replies[len(calls) - 1]
        if isinstance(reply, Exception):
            raise reply
        return reply

    send.calls = calls
    return send


# --- retry -------------------------------------------------------------------------------


def test_retries_429_and_5xx_then_returns_the_success(no_sleep):
    send = _scripted(Response(429, {}, b""), Response(503, {}, b""), Response(200, {}, b"{}"))
    assert client.retrying(send, attempts=3)("POST", "u", {}, b"").status == 200
    assert len(send.calls) == 3
    assert len(no_sleep) == 2


def test_the_last_failure_is_returned_unchanged_so_callers_keep_their_words():
    send = _scripted(Response(500, {}, b""), Response(502, {}, b"x"))
    assert client.retrying(send, attempts=2)("POST", "u", {}, b"").status == 502


def test_a_4xx_or_a_timeout_is_never_retried():
    send = _scripted(Response(403, {}, b""))
    assert client.retrying(send)("POST", "u", {}, b"").status == 403
    assert len(send.calls) == 1
    timed_out = _scripted(TimeoutError())
    with pytest.raises(TimeoutError):
        client.retrying(timed_out)("POST", "u", {}, b"")
    assert len(timed_out.calls) == 1


def test_a_refused_connection_is_retried_and_retry_after_is_honoured(no_sleep):
    send = _scripted(
        urllib.error.URLError("refused"),
        Response(429, {"Retry-After": "2"}, b""),
        Response(200, {}, b"{}"),
    )
    assert client.retrying(send, attempts=3)("POST", "u", {}, b"").status == 200
    assert no_sleep[1] == 2.0


def test_one_attempt_is_no_retry():
    send = _scripted(Response(503, {}, b""))
    assert client.retrying(send, attempts=1)("POST", "u", {}, b"").status == 503
    assert len(send.calls) == 1


# --- usage ---------------------------------------------------------------------------------


def test_usage_of_counts_tokens_and_estimates_cost():
    usage = client.usage_of(
        {
            "usageMetadata": {
                "promptTokenCount": 1000,
                "candidatesTokenCount": 100,
                "thoughtsTokenCount": 100,
                "totalTokenCount": 1200,
            },
            "candidates": [{"groundingMetadata": {"webSearchQueries": ["a"]}}],
        }
    )
    assert usage["prompt_tokens"] == 1000
    assert usage["output_tokens"] == 200
    assert usage["search_queries"] == 1
    # 1000 x 0.30 + 200 x 2.50 + 14,000 micro-dollars
    assert usage["cost_estimate_micro_usd"] == 300 + 500 + 14_000
    assert client.usage_of({})["cost_estimate_micro_usd"] is None


def test_record_writes_a_row_and_skips_without_a_household(household_a, household_user):
    client.record(household=None, feature="x", model="m")
    assert LlmUsage.objects.count() == 0
    row = client.record(
        household=household_a, feature="web_search", model="m", user=household_user,
        usage={"prompt_tokens": 5, "cost_estimate_micro_usd": 7},
    )
    assert (row.feature, row.prompt_tokens, row.cost_estimate_micro_usd) == ("web_search", 5, 7)
    assert row.user == household_user


# --- model config --------------------------------------------------------------------------


def test_model_config_answers_every_key_with_engine_defaults(token_a, monkeypatch):
    monkeypatch.delenv("LEGION_MODELS", raising=False)
    response = token_a.get("/api/config/models")
    assert response.status_code == 200
    assert set(response.data) == set(MODELS)
    assert response.data["live"] == live.MODEL


def test_model_config_overrides_known_keys_only(token_a, monkeypatch):
    monkeypatch.setenv("LEGION_MODELS", "chat=gemini-next,bogus=x,text=")
    data = token_a.get("/api/config/models").data
    assert data["chat"] == "gemini-next"
    assert "bogus" not in data
    assert data["text"] == MODELS["text"][0]


def test_model_config_needs_a_signed_in_member():
    assert APIClient().get("/api/config/models").status_code in (401, 403)


# --- member daily cap (Q9) ----------------------------------------------------------------


@pytest.fixture
def google(monkeypatch):
    sent = []

    def fake(method, url, headers, body):
        sent.append(url)
        return Response(200, {}, json.dumps({"name": TOKEN}).encode())

    monkeypatch.setattr(live, "urllib_transport", fake)
    monkeypatch.setenv(GEMINI_KEY_ENV, KEY)
    return sent


def test_cap_off_by_default_still_records_each_mint(household_user, google, monkeypatch):
    monkeypatch.delenv("LEGION_MEMBER_DAILY_CAP", raising=False)
    web = browser(household_user)
    for _ in range(3):
        assert _start(web).status_code == 200
    rows = LlmUsage.objects.filter(user=household_user, feature=cap.LIVE_MINT_FEATURE)
    assert rows.count() == 3
    assert {r.cost_estimate_micro_usd for r in rows} == {500_000}


def test_cap_refuses_the_mint_that_would_cross_it_in_words(household_user, google, monkeypatch):
    monkeypatch.setenv("LEGION_MEMBER_DAILY_CAP", "1")
    web = browser(household_user)
    assert _start(web).status_code == 200
    assert _start(web).status_code == 200
    third = _start(web)
    assert third.status_code == 429
    assert third.data["detail"] == cap.USED_UP
    assert len(google) == 2


def test_a_mint_google_refused_gives_its_estimate_back(household_user, monkeypatch):
    monkeypatch.setenv("LEGION_MEMBER_DAILY_CAP", "1")
    monkeypatch.setenv(GEMINI_KEY_ENV, KEY)
    monkeypatch.setattr(live, "urllib_transport", lambda *a: Response(500, {}, b""))
    assert _start(browser(household_user)).status_code == 503
    assert LlmUsage.objects.count() == 0


def test_web_search_is_refused_over_the_cap(household_user, household_a, monkeypatch):
    from engine_mcp import tools

    monkeypatch.setenv("LEGION_MEMBER_DAILY_CAP", "0.01")
    client.record(
        household=household_a, feature="x", model="m", user=household_user,
        cost_estimate_micro_usd=10_000,
    )
    assert cap.over_cap(household_user)

    class Req:
        user = household_user

    result = tools._web_search(Req(), {"query": "anything"})
    assert result.is_error is True
    assert "spend limit" in result.text


def test_bad_cap_value_is_a_configuration_error(monkeypatch):
    from django.core.exceptions import ImproperlyConfigured

    monkeypatch.setenv("LEGION_MEMBER_DAILY_CAP", "lots")
    with pytest.raises(ImproperlyConfigured):
        cap.cap_micro_usd()
