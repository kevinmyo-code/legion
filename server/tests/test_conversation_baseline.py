"""procedures ticket 10: the baseline measurement, against fixture conversations.

The fixtures are hand-written in the three writers' real line formats: the phone's (LIVE_AUDIT
plus its LIVE_LATENCY route / investigate lines, `LiveLatency.kt`), the head unit's (the same
audit lines, no sub-agent lines, `ConvoAudit.kt`) and the web record's
(`devicelogs/web_conversation.py`: engine-written tool lines with an `outcome`).
"""

from __future__ import annotations

import gzip
import uuid

import pytest
from django.core.management import call_command

from devicelogs.baseline import Totals, asks_in, median, p95, render
from devicelogs.models import DeviceLog

SECRET_WORDS = ("Brakes", "grinding", "Nakamura", "4242", "Pizza")


def L(time: str, msg: str, tag: str = "LIVE_AUDIT", tid: int = 5679) -> str:
    return f"11-02 {time}  1234  {tid} I {tag}: {msg}"


# Phone: a car ask through ask_fleet (router POST, async result), then a money ask of two direct
# tools in two waves, the second failing.
PHONE = [
    L("08:00:00.000", "session_start model=gemini-3.8-live async_set=ask_fleet"),
    L("08:00:02.000", 'user final=false text=" Brakes grinding,"'),
    L("08:00:02.400", 'user final=false text=" anything due on the car?"'),
    L("08:00:02.600",
      'tool_call id=c1 name=ask_fleet async=true args={"question":"Brakes grinding?"}'),
    L("08:00:03.000", 'assistant text="One moment."'),
    L("08:00:03.100", 'user final=true text="Brakes grinding, anything due on the car?"'),
    L("08:00:03.200", "turn_complete"),
    L("08:00:04.500", "route_ms=1900 route_post_ms=700 route_tools_ms=1100 route_tool_count=2 "
      "route_tools=get_next_service,check_recalls tool=ask_fleet model=gemini-3.5-flash-lite "
      "result=Success", tag="LIVE_LATENCY"),
    L("08:00:04.510", 'tool_result id=c1 name=ask_fleet success=true ms=1910 scheduling=WHEN_IDLE '
      'sent=true response={"success":true,"answer":"Oil change at Nakamura due."}'),
    L("08:00:05.400", 'assistant text="Oil change due."'),
    L("08:00:06.000", "turn_complete"),
    L("08:00:10.000", 'user final=true text="What did I spend at Pizza place?"'),
    L("08:00:10.300", "tool_call id=m1 name=get_monthly_spend async=false args={}"),
    L("08:00:10.700", 'tool_result id=m1 name=get_monthly_spend success=true ms=400 '
      'scheduling=none sent=true response={"total":"4242"}'),
    L("08:00:11.000", "tool_call id=m2 name=get_balance async=false args={}"),
    L("08:00:11.200", 'tool_result id=m2 name=get_balance success=false ms=200 scheduling=none '
      'sent=true response={"error":"timed out"}'),
    L("08:00:12.000", 'assistant text="You spent 42 dollars; the balance could not be read."'),
    L("08:00:13.000", "turn_complete"),
    L("08:00:14.000", 'session_end reason="stopped"'),
]

# Head unit: one ask_engine about the car, no sub-agent line, one call never answered.
HEAD_UNIT = [
    L("17:30:00.000", "session_start model=gemini-3.8-live async_set=none"),
    L("17:30:01.000", 'user final=true text="When is the car due for service?"'),
    L("17:30:01.500", 'tool_call id=e1 name=ask_engine async=false '
      'args={"question":"when is the car due for service","intent":"ASK"}'),
    L("17:30:05.500", 'tool_result id=e1 name=ask_engine success=true ms=4000 scheduling=none '
      'sent=true response={"answer":"In 900 miles."}'),
    L("17:30:06.000", 'assistant text="In about 900 miles."'),
    L("17:30:07.000", "turn_complete"),
    L("17:30:09.000", 'user final=true text="And recalls?"'),
    L("17:30:09.400", 'tool_call id=e2 name=ask_engine async=false '
      'args={"question":"open recalls on the car","intent":"ASK"}'),
    L("17:30:20.000", 'session_end reason="drive ended"'),
]


def web(tool_lines: list[str], user: str = "How much is in my accounts?") -> list[str]:
    return [
        L("12:00:00.000", "session_start model=gemini-live async_set=none", tid=2),
        L("12:00:01.000", f'user final=true text="{user}"', tid=1),
        *tool_lines,
        L("12:00:03.000", 'assistant text="Here it is."', tid=1),
        L("12:00:03.500", "turn_complete", tid=1),
    ]


WEB_MONEY = web([
    L("12:00:01.500", "tool_call id=w1 name=monthly_spend async=false args={}", tid=2),
    L("12:00:01.700", "tool_ms=200 tool=monthly_spend sent=true async=false", "LIVE_LATENCY", 2),
    L("12:00:01.700", 'tool_result id=w1 name=monthly_spend success=true ms=200 scheduling=none '
      'sent=true response={"success":true,"outcome":"ok","message":"4242"}', tid=2),
    L("12:00:02.000", "tool_call id=w2 name=account_balances async=false args={}", tid=2),
    L("12:00:02.300", 'tool_result id=w2 name=account_balances success=false ms=300 '
      'scheduling=none sent=true response={"success":false,"outcome":"refused",'
      '"message":"not for you"}', tid=2),
])
WEB_MORNING = web([
    L("12:00:01.500", "tool_call id=w1 name=list_events async=false args={}", tid=2),
    L("12:00:01.500", "tool_call id=w2 name=list_checklists async=false args={}", tid=2),
    L("12:00:01.800", 'tool_result id=w1 name=list_events success=true ms=300 scheduling=none '
      'sent=true response={"success":true,"outcome":"ok","message":"x"}', tid=2),
    L("12:00:01.900", 'tool_result id=w2 name=list_checklists success=true ms=400 '
      'scheduling=none sent=true response={"success":true,"outcome":"ok","message":"y"}', tid=2),
], user="What's my day?")


def test_a_phone_capture_splits_into_asks_with_their_costs():
    car, money = asks_in(PHONE, "phone")
    assert car.klass() == "car"
    assert car.tools == ["ask_fleet"] and car.inner_tools == {"get_next_service", "check_recalls"}
    assert (car.live_turns, car.router_posts, car.round_trips) == (2, 1, 3)
    # From the last user chunk (02.400) to the holding line, and to the line after the result.
    assert (car.first_audio, car.answer) == (600, 3000)
    assert not car.errors

    assert money.klass() == "money"
    assert (money.live_turns, money.round_trips) == (3, 3)
    assert money.answer == 2000
    assert money.errors == {"failed": 1, "timeout": 1}


def test_the_head_units_engine_calls_are_classed_by_words_and_flagged_unlogged():
    first, second = asks_in(HEAD_UNIT, "head unit")
    assert first.klass() == "car" and first.by_words
    assert first.unlogged_engine_calls == 1 and first.subagent_posts == 0
    assert first.answer == 5000
    assert second.errors == {"no result": 1}
    assert second.answer is None


def test_web_outcomes_and_classes():
    (money,) = asks_in(WEB_MONEY, "web")
    assert money.klass() == "money"
    assert money.errors == {"refused": 1}
    (morning,) = asks_in(WEB_MORNING, "web")
    assert morning.klass() == "morning"
    assert morning.live_turns == 2  # two calls in one wave


def test_an_ask_with_no_tool_and_two_classes():
    plain = [
        L("09:00:00.000", "session_start model=m async_set=none"),
        L("09:00:01.000", 'user final=true text="Hello"'),
        L("09:00:01.800", 'assistant text="Hi."'),
        L("09:00:02.000", "turn_complete"),
        L("09:00:03.000", 'user final=true text="Car and money?"'),
        L("09:00:03.100", "tool_call id=a name=next_service async=false args={}"),
        L("09:00:03.100", "tool_call id=b name=monthly_spend async=false args={}"),
        L("09:00:03.500", "tool_result id=a name=next_service success=true ms=400 "
          "scheduling=none sent=true response={}"),
        L("09:00:03.600", "tool_result id=b name=monthly_spend success=true ms=500 "
          "scheduling=none sent=true response={}"),
        L("09:00:04.000", 'assistant text="Both fine."'),
    ]
    hello, both = asks_in(plain, "phone")
    assert hello.klass() == "no tool" and hello.answer == 800 and hello.round_trips == 1
    assert both.klass() == "mixed"


def test_median_and_p95():
    assert median([]) == "-" and median([3, 1, 2]) == "2" and median([1, 2, 3, 4]) == "2.5"
    assert p95(list(range(19))) == "-"
    assert p95(list(range(1, 21))) == "19"


def _store(household, label: str, lines: list[str], *, visit: bool = False) -> None:
    body = ("\n".join(lines) + "\n").encode("utf-8")
    DeviceLog.objects.create(
        household=None if visit else household,
        demo_visitor=visit,
        batch_id=uuid.uuid4(),
        device_label=label,
        reason="conversation",
        line_count=len(lines),
        raw_bytes=len(body),
        body_gz=gzip.compress(body),
    )


@pytest.mark.django_db
def test_the_command_prints_tables_and_no_transcript_text(household_a, capsys):
    # The phone's session arrives in two uploads; they are joined into one stream.
    _store(household_a, "samsung SM-A256U", PHONE[:8])
    _store(household_a, "samsung SM-A256U", PHONE[8:])
    _store(household_a, "rockchip px6", HEAD_UNIT)
    _store(household_a, "web", WEB_MONEY)
    _store(household_a, "web", WEB_MORNING)
    _store(None, "web", WEB_MONEY, visit=True)

    call_command("conversation_baseline", "--days", "7")
    out = capsys.readouterr().out
    assert "Batches read: 5" in out and "Sessions: 4" in out and "Asks: 6" in out
    phone_car = (
        "| samsung SM-A256U | car | 1 | 0 | 1 | 2 | 1 | 0 | 3 / - | 600 / - | 3000 / - | 0 |"
    )
    assert phone_car in out
    assert "| rockchip px6 | car | 2 | 2 |" in out
    assert "| web | money | 1 | 0 | 2 | 3 | 0 | 0 | 3 / - | 2000 / - | 2000 / - | 1 |" in out
    assert "| money | 1 | 500 / - |" in out
    assert "web (recruiter visit)" not in out
    for secret in SECRET_WORDS:
        assert secret not in out, secret
    assert "text=" not in out and "response=" not in out and "args=" not in out

    call_command("conversation_baseline", "--days", "7", "--include-visits", "--numbered")
    numbered = capsys.readouterr().out.splitlines()
    assert numbered[0].startswith("0001 # Conversation baseline")
    assert any("| web (recruiter visit) | money | 1 |" in line for line in numbered)


@pytest.mark.django_db
def test_the_command_with_nothing_recorded(capsys):
    call_command("conversation_baseline")
    assert "Asks: 0" in capsys.readouterr().out


def test_render_with_no_asks_still_prints_the_headers():
    lines = render([], Totals(), window="test")
    assert lines[0] == "# Conversation baseline (procedures ticket 10)"
