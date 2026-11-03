"""`web_search` (Kevin, 2026-11-03: "it needs a websearch tool").

One Gemini `generateContent` call with Google Search grounding, on the
household key. No test reaches the network: `tests/conftest.py` makes the
transport fail unless a test hands it one, and every Google reply here is a
canned JSON body in the shape `brief/news.py`'s tests use.

- The request: the flash-lite URL the engine already uses, the key in a
  header, `google_search` as the one tool, the question and nothing else.
- The result: a short answer, the source names and links from
  `groundingMetadata`, and the instruction to cite them; no source is said.
- Failure is honest: no key, a timeout, an HTTP error, an empty answer.
- Every surface has it; a recruiter's call is charged against the demo's
  daily budget before Google is asked, and refused in words when it is spent.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime

import pytest
from rest_framework.test import APIClient

from assistant.surface import RECRUITER_TOOLS, WEB_TOOL_NAMES
from demo import budget
from demo.models import DemoBudgetDay, DemoCode
from engine_mcp import web_search
from engine_mcp.tools import TOOLS_BY_NAME, run_tool
from household.models import Household
from ingest import statements
from ingest.drive import Response

KEY = "test-gemini-key-never-echoed"

GROUNDED = {
    "candidates": [
        {
            "content": {
                "parts": [
                    {
                        "text": "The H-1B1 is a US work visa for professionals from Chile and "
                        "Singapore, created by free trade agreements."
                    }
                ]
            },
            "finishReason": "STOP",
            "groundingMetadata": {
                "webSearchQueries": ["H-1B1 visa Singapore"],
                "groundingChunks": [
                    {"web": {"uri": "https://vertexaisearch.example/r/1", "title": "uscis.gov"}},
                    {"web": {"uri": "https://vertexaisearch.example/r/2", "title": "state.gov"}},
                    {"web": {"uri": "https://vertexaisearch.example/r/1", "title": "uscis.gov"}},
                ],
            },
        }
    ],
    "usageMetadata": {"promptTokenCount": 30, "candidatesTokenCount": 25},
}


class FakeGoogle:
    def __init__(self, reply=None, status=200, raises=None):
        self.reply = GROUNDED if reply is None else reply
        self.status = status
        self.raises = raises
        self.sent: list[dict] = []

    def __call__(self, method, url, headers, body):
        self.sent.append(
            {"method": method, "url": url, "headers": headers, "body": json.loads(body)}
        )
        if self.raises is not None:
            raise self.raises
        return Response(self.status, {}, json.dumps(self.reply).encode())


@pytest.fixture
def google(monkeypatch):
    monkeypatch.setenv(statements.GEMINI_KEY_ENV, KEY)
    fake = FakeGoogle()
    monkeypatch.setattr(web_search, "_transport", fake)
    return fake


def _run(args):
    return run_tool(None, "web_search", args)


# =============================================================================
# The request
# =============================================================================


def test_the_request_is_one_grounded_flash_call_with_the_question_only(google):
    result = _run({"query": "What is the H1B1 Singapore free trade visa?"})
    assert not result.is_error, result.text
    [sent] = google.sent
    assert sent["method"] == "POST"
    assert sent["url"] == statements.GEMINI_URL
    assert statements.GEMINI_MODEL in sent["url"] and "flash" in statements.GEMINI_MODEL
    assert sent["headers"]["x-goog-api-key"] == KEY
    body = sent["body"]
    assert body["tools"] == [{"google_search": {}}]
    [content] = body["contents"]
    prompt = content["parts"][0]["text"]
    assert prompt.endswith("Question: What is the H1B1 Singapore free trade visa?")
    assert KEY not in json.dumps(body) and KEY not in result.text
    assert web_search.TIMEOUT_SECONDS == 10


# =============================================================================
# The result
# =============================================================================


def test_the_answer_carries_its_sources_and_says_to_cite_them(google):
    result = _run({"query": "what is the H1B1 visa"})
    assert not result.is_error
    assert result.text.startswith("Web search for 'what is the H1B1 visa' (Gemini with Google")
    assert "The H-1B1 is a US work visa" in result.text
    assert "from the web, not LEGION's data" in result.text
    assert "name the sources you use by their names" in result.text
    assert (
        "Sources: [1] uscis.gov https://vertexaisearch.example/r/1; "
        "[2] state.gov https://vertexaisearch.example/r/2"
    ) in result.text
    structured = result.structured
    assert structured["sources"] == [
        {"title": "uscis.gov", "url": "https://vertexaisearch.example/r/1"},
        {"title": "state.gov", "url": "https://vertexaisearch.example/r/2"},
    ]
    assert structured["search_queries"] == ["H-1B1 visa Singapore"]
    assert structured["model"] == statements.GEMINI_MODEL


def test_an_answer_with_no_web_source_is_said_to_be_unconfirmed(google):
    google.reply = {"candidates": [{"content": {"parts": [{"text": "Probably sunny."}]}}]}
    result = _run({"query": "weather in Houston"})
    assert not result.is_error
    assert "NO web source" in result.text and "could not confirm it" in result.text
    assert result.structured["sources"] == []


def test_a_thought_part_is_never_the_answer(google):
    google.reply = {
        "candidates": [
            {
                "content": {
                    "parts": [
                        {"text": "secret reasoning", "thought": True},
                        {"text": "The answer."},
                    ]
                }
            }
        ]
    }
    result = _run({"query": "anything"})
    assert "The answer." in result.text and "secret reasoning" not in result.text


# =============================================================================
# Honest failure
# =============================================================================


@pytest.mark.parametrize(
    ("fake", "starts", "says"),
    [
        (FakeGoogle(raises=TimeoutError()), "Nothing was found.", "TimeoutError"),
        (FakeGoogle(raises=OSError("down")), "Nothing was found.", "could not be reached"),
        (FakeGoogle(status=500), "Nothing was searched.", "HTTP 500"),
        (FakeGoogle(status=403), "Nothing was searched.", "refused this server's Gemini key"),
        (FakeGoogle(reply={"candidates": []}), "Nothing was found.", "no text"),
        (FakeGoogle(reply=[1, 2]), "Nothing was found.", "could not be read"),
    ],
)
def test_every_failure_says_what_did_not_happen(google, monkeypatch, fake, starts, says):
    monkeypatch.setattr(web_search, "_transport", fake)
    result = _run({"query": "news today"})
    assert result.is_error
    assert result.text.startswith(starts), result.text
    assert says in result.text
    assert KEY not in result.text


def test_no_key_means_nothing_was_searched(monkeypatch):
    monkeypatch.delenv(statements.GEMINI_KEY_ENV, raising=False)
    sent = []
    monkeypatch.setattr(web_search, "_transport", lambda *a: sent.append(a))
    result = _run({"query": "news today"})
    assert result.is_error
    assert result.text.startswith("Nothing was searched.")
    assert "LEGION_GEMINI_KEY is not set" in result.text
    assert sent == []


def test_tests_never_reach_the_network(monkeypatch):
    """The conftest default: with a key and no fake, the transport refuses."""
    monkeypatch.setenv(statements.GEMINI_KEY_ENV, KEY)
    result = _run({"query": "news today"})
    assert result.is_error and result.text.startswith("Nothing was found.")


@pytest.mark.parametrize("args", [{}, {"query": ""}, {"query": "x" * 301}, {"query": "a", "n": 1}])
def test_bad_arguments_are_refused_before_anything_is_sent(google, args):
    result = _run(args)
    assert result.is_error and result.text.startswith("Nothing was read")
    assert google.sent == []


# =============================================================================
# Every surface, and the description
# =============================================================================


def test_the_tool_is_on_every_surface_and_reads_only():
    assert "web_search" in TOOLS_BY_NAME  # /mcp lists the registry
    assert "web_search" in WEB_TOOL_NAMES  # members' web assistant
    assert "web_search" in RECRUITER_TOOLS  # a recruiter's visit
    assert not TOOLS_BY_NAME["web_search"].writes


def test_the_description_says_when_to_use_it_and_to_cite():
    description = TOOLS_BY_NAME["web_search"].description
    assert "current events" in description and "general facts" in description
    assert "not in LEGION's own data" in description
    assert "name the sources" in description
    assert "Nothing searched is stored" in description


def test_the_prompt_no_longer_says_there_is_no_search():
    from assistant.prompt import WEB_FRAME

    assert "no web search" not in WEB_FRAME.lower()
    assert "use web_search" in WEB_FRAME and "name the sources" in WEB_FRAME


# =============================================================================
# A recruiter's call is charged against the demo budget
# =============================================================================


@pytest.fixture
def recruiter(db):
    Household.objects.create(name="Demo")
    _row, code = DemoCode.rotate()
    browser = APIClient()
    assert browser.post("/api/demo/enter", {"code": code}, format="json").status_code == 201
    return browser


def _tool(client, args):
    return client.post(
        "/api/assistant/tool", {"name": "web_search", "args": args}, format="json"
    )


def _today() -> DemoBudgetDay | None:
    return DemoBudgetDay.objects.filter(day=datetime.now(UTC).date()).first()


def test_the_default_search_cost_is_two_cents(settings):
    assert settings.LEGION_DEMO_COST_PER_SEARCH_USD == "0.02"
    assert budget.limits().search_cents == 2


def test_a_recruiters_search_is_charged_before_google_is_asked(recruiter, google, settings):
    settings.LEGION_DEMO_COST_PER_SEARCH_USD = "0.05"
    response = _tool(recruiter, {"query": "What is the H1B1?"})
    assert response.status_code == 200, response.data
    assert response.data["is_error"] is False, response.data["text"]
    assert "uscis.gov" in response.data["text"]
    row = _today()
    assert (row.reserved_cents, row.web_searches) == (5, 1)
    assert len(google.sent) == 1


def test_a_spent_budget_refuses_the_search_in_words(recruiter, google, settings):
    settings.LEGION_DEMO_DAILY_BUDGET_USD = "0.03"
    settings.LEGION_DEMO_COST_PER_SEARCH_USD = "0.02"
    assert _tool(recruiter, {"query": "first"}).data["is_error"] is False
    refused = _tool(recruiter, {"query": "second"}).data
    assert refused["is_error"] is True
    assert refused["text"] == f"Nothing was searched. {budget.BUDGET_USED_UP}"
    assert len(google.sent) == 1  # Google was never asked the second time
    assert _today().reserved_cents == 2


def test_a_search_google_refused_gives_its_reservation_back(recruiter, google, monkeypatch):
    monkeypatch.setattr(web_search, "_transport", FakeGoogle(status=500))
    assert _tool(recruiter, {"query": "news"}).data["is_error"] is True
    assert _today().reserved_cents == 0


def test_a_search_that_timed_out_keeps_its_reservation(recruiter, google, monkeypatch):
    """It may have been billed; the cap can be under-used, never overshot."""
    monkeypatch.setattr(web_search, "_transport", FakeGoogle(raises=TimeoutError()))
    assert _tool(recruiter, {"query": "news"}).data["is_error"] is True
    assert _today().reserved_cents == 2


def test_a_members_search_is_never_counted(household_user, google):
    web = APIClient()
    web.force_login(household_user)
    response = _tool(web, {"query": "news today"})
    assert response.status_code == 200 and response.data["is_error"] is False
    assert DemoBudgetDay.objects.count() == 0

