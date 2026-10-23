"""The daily brief (ai-upgrade ticket 02): each deterministic section over real
rows, News over a mocked Gemini answer, the API, and `manage.py morning_brief`.
Its household leak test is in `tests/test_tenancy.py`.
"""

from __future__ import annotations

import datetime as dt
import io
import json
import logging

import pytest
from django.core.management import call_command

from brief import news as news_module
from brief.build import BUILD_HOUR, NO_TIMEZONE, build_for_household, is_due
from brief.compose import NO_RECURRING_SOURCE, UNVERIFIED_SUFFIX
from brief.models import Brief, Trigger
from brief.news import NewsResult, build_news, failed, parse, usage_of
from ingest.drive import Response
from ingest.models import IngestRun, Outcome, Source
from ingest.statements import GEMINI_KEY_ENV
from tests.conftest import _client_for, _member
from tests.test_spend_api import line as ledger_line
from tests.test_spend_api import provisional, statement

pytestmark = pytest.mark.django_db

LATEST = "/api/brief/latest"
REFRESH = "/api/brief/refresh"
VEHICLES = "/api/fleet/vehicles/"

# 07:00 in Houston (CDT, UTC-5) on Thursday 2026-10-15.
NOW = dt.datetime(2026, 10, 15, 12, 0, tzinfo=dt.UTC)


def no_news(today):
    return failed("News was not built: test.")


def build(household, member, **kwargs):
    kwargs.setdefault("news_builder", no_news)
    return build_for_household(household, Trigger.SCHEDULED, members=[member], now=NOW, **kwargs)[0]


def section(brief, key):
    return next(s for s in brief.sections if s["key"] == key)


def texts(sec):
    return [item["text"] for item in sec["lines"]]


@pytest.fixture
def houston(household_a):
    household_a.timezone = "America/Chicago"
    household_a.save(update_fields=["timezone"])
    return household_a


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


# =============================================================================
# Canvas
# =============================================================================


def make_task(client, title, starts_at, **fields):
    made = client.post(
        "/api/events",
        {"title": title, "kind": "task", "starts_at": starts_at, **fields},
        format="json",
    )
    assert made.status_code == 201, made.data
    return made.data["id"]


def test_canvas_lists_tasks_due_in_seven_days_with_what_canvas_said(
    auth_client, houston, household_user
):
    from legacy.models.dates import Event

    hw = make_task(auth_client, "MATH 2413: HW 7", "2026-10-17T04:59:00Z")
    lab = make_task(auth_client, "CHEM 1411: Lab 3", "2026-10-16T22:00:00Z")
    make_task(auth_client, "Later essay", "2026-10-30T04:59:00Z")
    make_task(auth_client, "Past quiz", "2026-10-14T04:59:00Z")
    Event.objects.filter(pk=hw).update(structured_meta={"canvas_submitted": True})
    Event.objects.filter(pk=lab).update(structured_meta={"canvas_submitted": False})

    canvas = section(build(houston, household_user), "canvas")
    assert canvas["status"] == "ok"
    assert texts(canvas) == [
        "CHEM 1411: Lab 3: due Fri Oct 16, 5:00 PM. Canvas says not submitted.",
        "MATH 2413: HW 7: due Fri Oct 16, 11:59 PM. Canvas says submitted.",
    ]


def test_a_ticked_task_says_so(auth_client, houston, household_user):
    from legacy.models.dates import Event

    task = make_task(auth_client, "Read ch 4", "2026-10-16T15:00:00Z")
    Event.objects.filter(pk=task).update(done=True, structured_meta={"canvas_submitted": False})
    assert texts(section(build(houston, household_user), "canvas")) == [
        "Read ch 4: due Fri Oct 16, 10:00 AM. Ticked done."
    ]


def test_canvas_never_shows_another_members_private_task(auth_client, houston, household_user, mia):
    make_task(_client_for(mia), "Mia's private task", "2026-10-16T15:00:00Z", visibility="private")
    make_task(auth_client, "Shared chore", "2026-10-16T16:00:00Z")
    kevin = texts(section(build(houston, household_user), "canvas"))
    mias = texts(section(build(houston, mia), "canvas"))
    assert kevin == ["Shared chore: due Fri Oct 16, 11:00 AM."]
    assert mias == [
        "Mia's private task: due Fri Oct 16, 10:00 AM.",
        "Shared chore: due Fri Oct 16, 11:00 AM.",
    ]


def test_canvas_empty_and_never_synced_say_so_in_words(houston, household_user):
    canvas = section(build(houston, household_user), "canvas")
    assert canvas["status"] == "empty"
    assert texts(canvas) == ["No tasks due in the next 7 days."]
    assert canvas["note"] == "Canvas has never synced."


def test_canvas_needing_a_login_says_the_freshness_sentence(houston, household_user):
    from ingest.jobs import NeedsLogin, run_job

    def refused(run):
        raise NeedsLogin("Canvas refused the saved session")

    run_job(Source.CANVAS, houston, refused)
    note = section(build(houston, household_user), "canvas")["note"]
    assert note.startswith("Canvas needs you to log in again")


def test_a_fresh_canvas_feed_adds_no_note(houston, household_user):
    from ingest.jobs import run_job

    run_job(Source.CANVAS, houston, lambda run: None)
    # Built at the real clock, so the run just recorded is fresh.
    brief = build_for_household(
        houston, Trigger.SCHEDULED, members=[household_user], news_builder=no_news
    )[0]
    assert section(brief, "canvas")["note"] is None


# =============================================================================
# Money
# =============================================================================


def test_money_with_no_rows_says_so_and_never_guesses_bills(houston, household_user):
    money = section(build(houston, household_user), "money")
    assert money["status"] == "empty"
    assert texts(money) == ["No bank rows on file for the last two months.", NO_RECURRING_SOURCE]


def test_money_yesterday_top_merchants_and_month_to_date(houston, household_user):
    statement(
        houston,
        "1111",
        "DBS checking",
        [
            ledger_line("2026-10-14", "HEB #123", -4_250, "Groceries"),
            ledger_line("2026-10-14", "SHELL OIL", -3_000, "Fuel"),
            ledger_line("2026-10-14", "HEB #123", -750, "Groceries"),
            ledger_line("2026-10-14", "UBER *TRIP", -1_200),
            ledger_line("2026-10-14", "ZAXBYS", -900, "Dining"),
            # A transfer pair: never spend.
            ledger_line("2026-10-14", "TRANSFER TO 2222", -50_000),
            ledger_line("2026-10-03", "RENT", -100_000, "Housing"),
            # Today's row is not yesterday's and not month-to-date-through-yesterday.
            ledger_line("2026-10-15", "COFFEE", -500, "Dining"),
        ],
        start="2026-10-01",
        end="2026-10-15",
    )
    statement(
        houston,
        "2222",
        "DBS savings",
        [ledger_line("2026-10-14", "TRANSFER FROM 1111", 50_000)],
        start="2026-10-01",
        end="2026-10-15",
    )
    statement(
        houston,
        "1111",
        "DBS checking",
        [
            ledger_line("2026-09-05", "HEB #123", -20_000, "Groceries"),
            # After Sep 14: outside "by the same day last month".
            ledger_line("2026-09-20", "HEB #123", -99_900, "Groceries"),
        ],
        start="2026-09-01",
        end="2026-09-30",
    )
    money = section(build(houston, household_user), "money")
    assert money["status"] == "ok"
    yesterday, mtd, latest, bills = money["lines"]
    assert yesterday["text"] == (
        "Yesterday: $89.00 spent, plus $12.00 not yet categorised. "
        "Top: HEB #123 $50.00, SHELL OIL $30.00, UBER *TRIP $12.00."
    )
    assert yesterday["unverified"] is False
    assert mtd["text"] == (
        "Month to date (Oct 1 to Oct 14): $1,089.00 spent, against $200.00 by Sep 14 last "
        "month. Up $889.00. Another $12.00 this month is not yet categorised."
    )
    assert latest["text"] == (
        "Latest bank row on file is dated Oct 15, so anything after it is not counted yet."
    )
    assert bills["text"] == NO_RECURRING_SOURCE


def test_money_built_on_unverified_rows_says_unverified(houston, household_user):
    provisional(
        houston, "9999", "BofA card", [ledger_line("2026-10-14", "TARGET", -2_500, "Household")]
    )
    money = section(build(houston, household_user), "money")
    yesterday, mtd = money["lines"][:2]
    assert yesterday["unverified"] is True
    assert yesterday["text"].endswith(UNVERIFIED_SUFFIX)
    assert yesterday["text"].startswith(
        "Yesterday: $25.00 went out, not yet categorised. Top: TARGET $25.00."
    )
    assert mtd["unverified"] is True
    assert mtd["text"].endswith(UNVERIFIED_SUFFIX)


def test_money_with_rows_but_none_yesterday_says_none_on_file(houston, household_user):
    statement(
        houston,
        "1111",
        "DBS checking",
        [ledger_line("2026-10-02", "HEB #123", -1_000, "Groceries")],
        start="2026-10-01",
        end="2026-10-15",
    )
    assert texts(section(build(houston, household_user), "money"))[0] == (
        "Yesterday: no spending on file."
    )


# =============================================================================
# Car
# =============================================================================


def test_car_with_no_vehicle_says_so(houston, household_user):
    car = section(build(houston, household_user), "car")
    assert car["status"] == "empty"
    assert texts(car) == ["No vehicle on file."]


def test_car_with_nothing_logged_says_not_logged_never_zero(auth_client, houston, household_user):
    made = auth_client.put(
        f"{VEHICLES}car-1/",
        {
            "name": "M3",
            "make": "BMW",
            "model": "M3",
            "year": 2003,
            "confirmed": True,
            "archived": False,
        },
        format="json",
    )
    assert made.status_code == 200, made.data
    assert texts(section(build(houston, household_user), "car")) == [
        "M3: last drive not logged.",
        "M3: OBD readings not logged.",
        "M3: trouble codes not logged.",
        "M3: Maintenance: no schedule logged.",
    ]


def test_car_reports_drive_codes_and_maintenance_due(auth_client, houston, household_user):
    vehicle = auth_client.put(
        f"{VEHICLES}car-1/",
        {
            "name": "M3",
            "make": "BMW",
            "model": "M3",
            "year": 2003,
            "confirmed": True,
            "archived": False,
        },
        format="json",
    ).data["id"]
    put = [
        (
            "drives/d1/",
            {
                "vehicle_id": vehicle,
                "started_at": "2026-10-13T22:00:00Z",
                "ended_at": "2026-10-13T22:30:00Z",
                "miles": 12.34,
                "end_reason": "ENGINE_OFF",
            },
        ),
        (
            "code_events/c1/",
            {
                "vehicle_id": vehicle,
                "occurred_at": "2026-10-01T12:00:00Z",
                "mileage": 101_000,
                "codes": ["P0420"],
            },
        ),
        (
            "code_clear_events/x1/",
            {
                "vehicle_id": vehicle,
                "occurred_at": "2026-10-05T12:00:00Z",
                "mileage": 101_100,
                "codes_before": ["P0420"],
                "codes_after": [],
                "outcome": "CLEARED",
                "ack_raw": "OK",
            },
        ),
        (
            "code_events/c2/",
            {
                "vehicle_id": vehicle,
                "occurred_at": "2026-10-10T12:00:00Z",
                "mileage": 101_400,
                "codes": ["P0128"],
            },
        ),
        (
            "service_history/s1/",
            {
                "vehicle_id": vehicle,
                "service_name": "Oil change",
                "kind": "OBSERVED",
                "service_date": "2026-04-20",
                "mileage": 97_000,
            },
        ),
        (
            "service_history/s2/",
            {
                "vehicle_id": vehicle,
                "service_name": "Spark plugs",
                "kind": "OBSERVED",
                "service_date": "2025-01-10",
                "mileage": 71_200,
            },
        ),
        (
            f"maintenance_schedules/{vehicle}/oil change/",
            {"interval_months": 6, "interval_source": "SEEDED", "never_done": False},
        ),
        (
            f"maintenance_schedules/{vehicle}/spark plugs/",
            {"interval_miles": 30_000, "interval_source": "SEEDED", "never_done": False},
        ),
        (
            f"maintenance_schedules/{vehicle}/coolant/",
            {"interval_months": 48, "interval_source": "SEEDED", "never_done": True},
        ),
    ]
    for path, body in put:
        response = auth_client.put(f"/api/fleet/{path}", body, format="json")
        assert response.status_code == 200, (path, response.data)

    assert texts(section(build(houston, household_user), "car")) == [
        "M3: last drive Tue Oct 13, 5:30 PM, 12.3 mi.",
        "M3: OBD readings not logged.",
        "M3: trouble codes since the last clear: P0128.",
        "M3: oil change due Oct 20 (every 6 months, last done Apr 20).",
        "M3: spark plugs overdue at 101,200 mi (last recorded odometer 101,400 mi on Oct 10).",
        "M3: Never logged as done: coolant.",
    ]


# =============================================================================
# News (Gemini mocked)
# =============================================================================

ANSWER = (
    "- OpenAI shipped a new model.\n"
    "- Google released Android 17 beta 2.\n"
    "- A rumour nobody sourced.\n"
)


def grounded_payload() -> dict:
    encoded = ANSWER.encode("utf-8")
    first_end = encoded.index(b"\n") + 1
    return {
        "candidates": [
            {
                "content": {"parts": [{"text": ANSWER}]},
                "groundingMetadata": {
                    "webSearchQueries": ["ai news", "android news"],
                    "groundingChunks": [
                        {
                            "web": {
                                "uri": "https://vertexaisearch.example/r/1",
                                "title": "openai.com",
                            }
                        },
                        {
                            "web": {
                                "uri": "https://vertexaisearch.example/r/2",
                                "title": "android.com",
                            }
                        },
                    ],
                    "groundingSupports": [
                        {
                            "segment": {
                                "startIndex": 2,
                                "endIndex": first_end - 1,
                                "text": "OpenAI shipped a new model.",
                            },
                            "groundingChunkIndices": [0],
                        },
                        {
                            "segment": {"text": "Google released Android 17 beta 2."},
                            "groundingChunkIndices": [1, 1],
                        },
                    ],
                },
            }
        ],
        "usageMetadata": {
            "promptTokenCount": 100,
            "candidatesTokenCount": 40,
            "thoughtsTokenCount": 10,
            "toolUsePromptTokenCount": 900,
            "totalTokenCount": 1050,
        },
    }


class FakeGemini:
    def __init__(self, status=200, payload=None):
        self.status = status
        self.payload = payload if payload is not None else grounded_payload()
        self.calls = []

    def __call__(self, method, url, headers, body):
        self.calls.append((method, url, headers, json.loads(body)))
        return Response(self.status, {}, json.dumps(self.payload).encode())


def test_news_keeps_only_grounded_items_with_their_links():
    fake = FakeGemini()
    result = build_news(dt.date(2026, 10, 15), transport=fake, key="k")
    assert result.ok is True
    sec = result.section
    assert [item["text"] for item in sec["lines"]] == [
        "OpenAI shipped a new model.",
        "Google released Android 17 beta 2.",
    ]
    assert sec["lines"][0]["links"] == [
        {"title": "openai.com", "url": "https://vertexaisearch.example/r/1"}
    ]
    assert sec["lines"][1]["links"] == [
        {"title": "android.com", "url": "https://vertexaisearch.example/r/2"}
    ]
    assert sec["note"] == (
        "Only 2 sourced items today. 1 item was left out because no source was tied to it."
    )
    # One call, grounded, on the key, carrying no household data.
    ((method, url, headers, body),) = fake.calls
    assert body["tools"] == [{"google_search": {}}]
    assert headers["x-goog-api-key"] == "k"
    assert "2026-10-15" in body["contents"][0]["parts"][0]["text"]


def test_news_usage_and_cost_estimate_are_recorded():
    result = build_news(dt.date(2026, 10, 15), transport=FakeGemini(), key="k")
    assert result.usage == {
        "prompt_tokens": 100,
        "output_tokens": 40,
        "thinking_tokens": 10,
        "tool_prompt_tokens": 900,
        "total_tokens": 1050,
        "search_queries": 2,
        # 1000 input x 0.30 + 50 output x 2.50 + 2 searches x 14,000.
        "cost_estimate_micro_usd": 300 + 125 + 28_000,
    }


def test_news_without_a_key_says_so_and_sends_nothing():
    fake = FakeGemini()
    result = build_news(dt.date(2026, 10, 15), transport=fake, key="")
    assert result.ok is False
    assert result.section["status"] == "failed"
    assert result.section["note"] == news_module.NO_KEY
    assert fake.calls == []


@pytest.mark.parametrize(
    ("status", "words"),
    [(500, "Gemini answered HTTP 500"), (403, "Gemini refused LEGION_GEMINI_KEY (HTTP 403)")],
)
def test_news_failure_is_said_in_words(status, words):
    result = build_news(dt.date(2026, 10, 15), transport=FakeGemini(status=status), key="k")
    assert result.ok is False
    assert words in result.section["note"]


def news_from(payload: dict) -> NewsResult:
    return build_news(dt.date(2026, 10, 15), transport=FakeGemini(payload=payload), key="k")


def grounding_log(caplog) -> str:
    """The one diagnostic line, asserted to carry no content and no URI."""
    (record,) = [r for r in caplog.records if r.name == "brief.news"]
    assert record.levelno == logging.WARNING
    message = record.getMessage()
    assert "http" not in message
    assert "vertexaisearch" not in message
    for word in ("OpenAI", "Android", "rumour", "openai.com", "ai news"):
        assert word not in message
    return message


def test_news_with_chunks_but_no_supports_keeps_items_and_lists_sources_for_the_section(caplog):
    caplog.set_level(logging.WARNING, logger="brief.news")
    payload = grounded_payload()
    del payload["candidates"][0]["groundingMetadata"]["groundingSupports"]
    result = news_from(payload)
    assert result.ok is True
    sec = result.section
    assert sec["status"] == "ok"
    *items, sources = sec["lines"]
    assert [item["text"] for item in items] == [
        "OpenAI shipped a new model.",
        "Google released Android 17 beta 2.",
        "A rumour nobody sourced.",
    ]
    # No item claims a source it was never tied to.
    assert all(item["links"] == [] for item in items)
    assert sources["text"] == news_module.SECTION_SOURCES_LINE
    assert "not per item" in sources["text"]
    assert sources["links"] == [
        {"title": "openai.com", "url": "https://vertexaisearch.example/r/1"},
        {"title": "android.com", "url": "https://vertexaisearch.example/r/2"},
    ]
    assert sec["note"] == f"Only 3 items today. {news_module.SECTION_SOURCES_NOTE}"
    assert "not per item" in sec["note"]
    message = grounding_log(caplog)
    assert "outcome=section_sources" in message
    assert "groundingChunks=2 groundingSupports=0 webSearchQueries=2" in message
    assert "bullets=3" in message


def test_news_whose_supports_land_on_no_bullet_also_lists_sources_for_the_section(caplog):
    caplog.set_level(logging.WARNING, logger="brief.news")
    payload = grounded_payload()
    payload["candidates"][0]["groundingMetadata"]["groundingSupports"] = [
        {"segment": {"text": "A sentence that is not in the answer."}, "groundingChunkIndices": [0]}
    ]
    result = news_from(payload)
    assert result.ok is True
    *items, sources = result.section["lines"]
    assert len(items) == 3 and all(item["links"] == [] for item in items)
    assert len(sources["links"]) == 2
    assert "groundingSupports=1" in grounding_log(caplog)


def test_news_with_neither_chunks_nor_supports_shows_nothing_and_logs_the_shape(caplog):
    caplog.set_level(logging.WARNING, logger="brief.news")
    payload = grounded_payload()
    del payload["candidates"][0]["groundingMetadata"]
    result = news_from(payload)
    assert result.ok is False
    assert result.section["lines"] == []
    assert "no item in it was tied to a web source" in result.section["note"]
    message = grounding_log(caplog)
    assert "outcome=nothing" in message
    assert "groundingMetadata=absent" in message
    assert "groundingChunks=0 groundingSupports=0 webSearchQueries=0" in message


def test_news_with_supports_but_no_chunks_invents_no_link(caplog):
    """The shape a 2.5 Flash-Lite user reported: supports point at chunk
    indices, and the chunk list is empty. There is no URI to show."""
    caplog.set_level(logging.WARNING, logger="brief.news")
    payload = grounded_payload()
    payload["candidates"][0]["groundingMetadata"]["groundingChunks"] = []
    result = news_from(payload)
    assert result.ok is False
    assert result.section["lines"] == []
    assert "groundingChunks=0 groundingSupports=2" in grounding_log(caplog)


def test_news_that_is_not_a_list_says_so(caplog):
    caplog.set_level(logging.WARNING, logger="brief.news")
    payload = grounded_payload()
    payload["candidates"][0]["content"]["parts"] = [{"text": "OpenAI shipped a new model today."}]
    result = news_from(payload)
    assert result.ok is False
    assert "not as a list of items" in result.section["note"]
    assert "bullets=0" in grounding_log(caplog)


def test_a_grounded_answer_logs_nothing(caplog):
    caplog.set_level(logging.WARNING, logger="brief.news")
    assert news_from(grounded_payload()).ok is True
    assert [r for r in caplog.records if r.name == "brief.news"] == []


NON_ASCII = (
    "- 東京株式市場で日経平均株価が史上最高値を更新した。\n"
    "- Samsung shipped One UI 9.\n"
    "- Mozilla released Firefox 150.\n"
)


def offsets_of(text: str, sentence: str) -> tuple[int, int, int, int]:
    """(byte start, byte end, char start, char end) of `sentence` in `text`."""
    char = text.index(sentence)
    byte = text.encode("utf-8").index(sentence.encode("utf-8"))
    return byte, byte + len(sentence.encode("utf-8")), char, char + len(sentence)


def one_support_payload(text_parts: list[dict], segment: dict) -> dict:
    return {
        "candidates": [
            {
                "content": {"parts": text_parts},
                "groundingMetadata": {
                    "groundingChunks": [
                        {"web": {"uri": "https://x.example/r/1", "title": "x.com"}}
                    ],
                    "groundingSupports": [{"segment": segment, "groundingChunkIndices": [0]}],
                },
            }
        ]
    }


def test_segment_offsets_are_utf8_bytes_not_characters():
    byte_start, byte_end, char_start, _ = offsets_of(NON_ASCII, "Samsung shipped One UI 9.")
    # The fixture discriminates: read as characters, these byte offsets land on
    # the third line, not the second.
    third_char_start = NON_ASCII.index("Mozilla")
    assert byte_start > third_char_start
    payload = one_support_payload(
        [{"text": NON_ASCII}], {"startIndex": byte_start, "endIndex": byte_end}
    )
    parsed = parse(payload)
    assert parsed.mode == news_module.PER_ITEM
    assert [item["text"] for item in parsed.items] == ["Samsung shipped One UI 9."]
    assert parsed.items[0]["links"] == [{"title": "x.com", "url": "https://x.example/r/1"}]
    assert parsed.dropped == 2
    assert char_start != byte_start


def test_a_segment_whose_offsets_disagree_with_its_text_is_found_by_its_text():
    # Character offsets, which the reference says the API does not send; the
    # segment's own text still places it on the right line.
    _, _, char_start, char_end = offsets_of(NON_ASCII, "Samsung shipped One UI 9.")
    payload = one_support_payload(
        [{"text": NON_ASCII}],
        {"startIndex": char_start, "endIndex": char_end, "text": "Samsung shipped One UI 9."},
    )
    parsed = parse(payload)
    assert [item["text"] for item in parsed.items] == ["Samsung shipped One UI 9."]


def test_offsets_whose_own_text_is_nowhere_in_the_answer_tie_no_bullet():
    # The offsets land squarely on the Samsung line, but the segment says it
    # supports a sentence the answer does not contain. Neither is trusted.
    byte_start, byte_end, _, _ = offsets_of(NON_ASCII, "Samsung shipped One UI 9.")
    payload = one_support_payload(
        [{"text": NON_ASCII}],
        {"startIndex": byte_start, "endIndex": byte_end, "text": "Apple shipped iOS 30."},
    )
    parsed = parse(payload)
    assert parsed.mode == news_module.SECTION_SOURCES
    assert all(item["links"] == [] for item in parsed.items)


def test_segment_offsets_are_per_part():
    parts = [
        {"thoughtSignature": "c2ln"},
        {"text": "- OpenAI shipped a new model.\n"},
        {"text": "- Google released Android 17 beta 2.\n"},
    ]
    sentence = "Google released Android 17 beta 2."
    # Offsets into Part 2 alone; in the joined text they would point at line 1.
    payload = one_support_payload(
        parts, {"partIndex": 2, "startIndex": 2, "endIndex": 2 + len(sentence)}
    )
    parsed = parse(payload)
    assert [item["text"] for item in parsed.items] == [sentence]
    assert parsed.dropped == 1


def test_a_segment_spanning_the_bullet_marker_still_ties_by_text():
    payload = one_support_payload(
        [{"text": "- OpenAI shipped a new model.\n- Another story.\n"}],
        {"text": "- OpenAI shipped a new model."},
    )
    parsed = parse(payload)
    assert [item["text"] for item in parsed.items] == ["OpenAI shipped a new model."]


def test_a_network_error_is_a_failed_section_not_a_raise():
    def boom(*args):
        raise TimeoutError

    result = build_news(dt.date(2026, 10, 15), transport=boom, key="k")
    assert result.ok is False
    assert "did not answer (TimeoutError)" in result.section["note"]


def test_parse_caps_at_eight_items():
    answer = "".join(f"- Item {i}.\n" for i in range(12))
    payload = {
        "candidates": [
            {
                "content": {"parts": [{"text": answer}]},
                "groundingMetadata": {
                    "groundingChunks": [{"web": {"uri": "https://x/1", "title": "x.com"}}],
                    "groundingSupports": [
                        {"segment": {"text": f"Item {i}."}, "groundingChunkIndices": [0]}
                        for i in range(12)
                    ],
                },
            }
        ]
    }
    assert len(parse(payload).items) == 8
    assert usage_of(payload)["cost_estimate_micro_usd"] is None


def test_a_news_failure_never_fails_the_other_sections(houston, household_user):
    brief = build(
        houston,
        household_user,
        news_builder=lambda today: build_news(today, transport=FakeGemini(status=500), key="k"),
    )
    assert [s["key"] for s in brief.sections] == ["canvas", "money", "car", "news"]
    assert brief.news_ok is False
    assert section(brief, "news")["status"] == "failed"
    assert section(brief, "canvas")["status"] == "empty"


def test_one_news_call_is_shared_by_every_member(houston, household_user, mia):
    fake = FakeGemini()
    briefs = build_for_household(
        houston,
        Trigger.SCHEDULED,
        now=NOW,
        news_builder=lambda today: build_news(today, transport=fake, key="k"),
    )
    assert len(fake.calls) == 1
    assert len(briefs) == 2
    assert briefs[0].news_call_id == briefs[1].news_call_id is not None
    assert all(b.news_ok and b.total_tokens == 1050 for b in briefs)


# =============================================================================
# Clock, storage
# =============================================================================


def test_no_timezone_uses_utc_and_says_so(household_a, household_user):
    brief = build(household_a, household_user)
    assert brief.brief_date == dt.date(2026, 10, 15)
    assert brief.notes == [NO_TIMEZONE]


def test_is_due_from_five_local_and_once_per_local_day(houston, household_user):
    # 04:59 CDT.
    assert not is_due(houston, dt.datetime(2026, 10, 15, 9, 59, tzinfo=dt.UTC))
    # 05:00 CDT.
    five = dt.datetime(2026, 10, 15, 10, 0, tzinfo=dt.UTC)
    assert BUILD_HOUR == 5 and is_due(houston, five)
    build_for_household(houston, Trigger.REFRESH, now=five, news_builder=no_news)
    assert is_due(houston, five), "a refresh does not stand in for the 05:00 build"
    build_for_household(houston, Trigger.SCHEDULED, now=five, news_builder=no_news)
    assert not is_due(houston, dt.datetime(2026, 10, 16, 4, 0, tzinfo=dt.UTC))
    # In winter (CST, UTC-6) 05:00 is 11:00 UTC.
    assert not is_due(houston, dt.datetime(2026, 12, 15, 10, 30, tzinfo=dt.UTC))
    assert is_due(houston, dt.datetime(2026, 12, 15, 11, 0, tzinfo=dt.UTC))


def test_only_the_last_fourteen_days_are_kept(houston, household_user):
    for days_ago in (20, 14, 13, 0):
        build_for_household(
            houston,
            Trigger.SCHEDULED,
            members=[household_user],
            now=NOW - dt.timedelta(days=days_ago),
            news_builder=no_news,
        )
    kept = sorted(Brief.objects.filter(household=houston).values_list("brief_date", flat=True))
    assert kept == [dt.date(2026, 10, 2), dt.date(2026, 10, 15)]


# =============================================================================
# The API
# =============================================================================


def test_latest_before_any_build_is_a_404_in_words(auth_client):
    response = auth_client.get(LATEST)
    assert response.status_code == 404
    assert response.data["detail"].startswith("No brief has been built for you yet.")


def test_refresh_builds_one_for_the_caller_and_latest_serves_it(
    auth_client, houston, household_user, mia, monkeypatch
):
    monkeypatch.setattr(news_module, "_transport", FakeGemini())
    monkeypatch.setenv(GEMINI_KEY_ENV, "k")
    made = auth_client.post(REFRESH)
    assert made.status_code == 201, made.data
    assert made.data["trigger"] == "refresh"
    assert made.data["news_ok"] is True
    assert [s["key"] for s in made.data["sections"]] == ["canvas", "money", "car", "news"]
    assert made.data["age_sentence"] == "Built just now, by a refresh."
    for forbidden in ("user", "user_id", "household", "household_id"):
        assert forbidden not in made.data
    # Only the caller got one.
    assert list(Brief.objects.values_list("user_id", flat=True)) == [household_user.pk]
    latest = auth_client.get(LATEST)
    assert latest.status_code == 200
    assert latest.data["id"] == made.data["id"]
    assert _client_for(mia).get(LATEST).status_code == 404
    run = IngestRun.objects.get(source=Source.BRIEF)
    assert run.outcome == Outcome.OK and run.rows_written == 1


def test_refresh_without_a_key_still_builds_and_says_news_failed(auth_client, houston, monkeypatch):
    monkeypatch.delenv(GEMINI_KEY_ENV, raising=False)
    made = auth_client.post(REFRESH)
    assert made.status_code == 201
    assert made.data["news_ok"] is False
    news = made.data["sections"][3]
    assert news["status"] == "failed" and news["note"] == news_module.NO_KEY


def test_refresh_while_a_build_holds_the_lock_is_a_409(auth_client, monkeypatch):
    from brief import views

    monkeypatch.setattr(views, "run_job", lambda *a, **k: IngestRun(outcome=Outcome.SKIPPED_LOCKED))
    response = auth_client.post(REFRESH)
    assert response.status_code == 409
    assert response.data["detail"] == views.LOCKED
    assert Brief.objects.count() == 0


def test_brief_routes_need_a_signed_in_member(client):
    assert client.get(LATEST).status_code == 401
    assert client.post(REFRESH).status_code == 401


def test_freshness_names_the_brief(auth_client):
    rows = {row["source"]: row for row in auth_client.get("/api/freshness").data["sources"]}
    assert rows["brief"]["sentence"] == "The daily brief has never been built."


# =============================================================================
# manage.py morning_brief
# =============================================================================


def test_the_command_builds_only_for_a_due_household(houston, household_user, monkeypatch):
    from django.utils import timezone

    monkeypatch.delenv(GEMINI_KEY_ENV, raising=False)
    monkeypatch.setattr(timezone, "now", lambda: dt.datetime(2026, 10, 15, 9, 0, tzinfo=dt.UTC))
    out = io.StringIO()
    call_command("morning_brief", stdout=out)
    assert "not due" in out.getvalue()
    assert Brief.objects.count() == 0
    assert IngestRun.objects.filter(source=Source.BRIEF).count() == 0

    monkeypatch.setattr(timezone, "now", lambda: dt.datetime(2026, 10, 15, 10, 5, tzinfo=dt.UTC))
    call_command("morning_brief", stdout=out)
    assert Brief.objects.filter(trigger=Trigger.SCHEDULED).count() == 1
    assert IngestRun.objects.get(source=Source.BRIEF).outcome == Outcome.OK
    call_command("morning_brief", stdout=out)
    assert Brief.objects.count() == 1, "built once per local day"


def test_dry_run_prints_and_writes_nothing(houston, household_user, monkeypatch):
    monkeypatch.delenv(GEMINI_KEY_ENV, raising=False)
    out = io.StringIO()
    call_command("morning_brief", "--dry-run", stdout=out)
    text = out.getvalue()
    assert "DRY RUN" in text
    assert "[Canvas] empty" in text and "[News] failed" in text
    assert Brief.objects.count() == 0
    assert IngestRun.objects.count() == 0


def test_news_result_fields_map_onto_the_row():
    fields = NewsResult(section={}, ok=False).usage_fields()
    assert set(fields) <= {f.name for f in Brief._meta.get_fields()}
