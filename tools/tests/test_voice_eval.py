"""`tools/voice_eval.py`: parsing the phone's line shapes, the rule checks, pricing and the scorecard.
Invented utterances and counts only. Run from the repo root:

    python -m pytest tools/tests/test_voice_eval.py -p no:cacheprovider
"""

from __future__ import annotations

import datetime
import importlib.util
import json
import sys
from decimal import Decimal
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("voice_eval_under_test", TOOLS / "voice_eval.py")
ve = importlib.util.module_from_spec(spec)
sys.modules["voice_eval_under_test"] = ve
spec.loader.exec_module(ve)

DAY = datetime.date(2026, 11, 15)
M = "gemini-3.5-flash-lite"


def L(tag: str, msg: str, t: str = "10:00:00") -> str:
    return f"11-15 {t}.000  4242  4242 I {tag}: {msg}"


def case_lines(cid: str, user: str, calls: list[tuple[str, dict, str]], answer: str, ms: int = 1500,
               end: bool = True) -> list[str]:
    out = [L("LIVE_TEST", f"case_begin id={cid}"), L("LIVE_AUDIT", f"session_start model=eval+{M} async_set=none"),
           L("LIVE_AUDIT", f"user final=true text={json.dumps(user)}")]
    for i, (name, args, response) in enumerate(calls):
        out.append(L("LIVE_AUDIT", f"tool_call id=e{i} name={name} async=false args={json.dumps(args, separators=(',', ':'))}"))
        ok = "false" if "test_mode" in response or '"success":false' in response else "true"
        out.append(L("LIVE_AUDIT", f"tool_result id=e{i} name={name} success={ok} ms=40 scheduling=none sent=true response={response}"))
    out.append(L("LIVE_LATENCY", f"gemini_usage kind=llm model={M} usage=present prompt=12000 prompt_text=12000 "
                                 "prompt_audio=0 cached=11000 out=60 out_text=0 out_audio=0 thoughts=0 audio_s=0.0 chars=0 discarded=false"))
    out.append(L("LIVE_AUDIT", f"assistant text={json.dumps(answer)}"))
    out.append(L("LIVE_AUDIT", "turn_complete"))
    out.append(L("LIVE_LATENCY", f"test_turn_ms={ms} first_text_ms=700 failure=none"))
    out.append(L("LIVE_AUDIT", 'session_end reason="eval"'))
    if end:
        out.append(L("LIVE_TEST", f"case_end id={cid} ms={ms}"))
    return out


CAL_OPEN = '{"success":true,"count":3,"events":[{"title":"HIST 101 reading quiz","kind":"task","done":false}]}'
CASES = [
    {"id": "due_today", "say": "what's due today",
     "expect": {"calls": ["day/read_calendar"], "no_empty_claim": True,
                "args": {"day/read_calendar": {"from": {"lte": "{today}"}, "to": {"gte": "{today}"}}}},
     "fixture": {"answer_has": ["quiz"]}},
    {"id": "write_todo", "say": "add stamps to my to-do list", "write": True,
     "expect": {"calls": ["manage_checklist/add"], "args": {"manage_checklist/add": {"list": {"re": "(?i)^to-?do$"}}}}},
]


def parse(lines):
    return ve.parse_run(lines, 2026)


def test_expand_tokens():
    assert ve.expand("{today}", DAY) == "2026-11-15"
    assert ve.expand("{today+1}", DAY) == "2026-11-16"
    assert ve.expand("{today-1}", DAY) == "2026-11-14"


def test_parse_splits_cases_turns_calls_and_usage():
    lines = case_lines("due_today", "what's due today",
                       [("day", {"action": "read_calendar", "from": "2026-11-15", "to": "2026-11-15"}, CAL_OPEN)],
                       "One quiz is due tonight.")
    _, runs = parse(lines)
    (r,) = runs
    assert r.id == "due_today" and r.ended and r.model == M and r.day == DAY
    (t,) = r.turns
    assert t.user == "what's due today"
    assert t.calls[0].key == "day/read_calendar" and t.calls[0].success is True
    assert t.answer == "One quiz is due tonight."
    assert t.timings["test_turn_ms"] == 1500
    assert len(r.usage) == 1 and r.usage[0].counts["cached"] == 11000


def test_routing_bug_is_caught():
    # 2026-11-15: "what do I have due" went to the reminders list.
    lines = case_lines("due_today", "what's due today", [("manage_item", {"action": "read_list"}, '{"success":true}')],
                       "You have no reminders.")
    _, runs = parse(lines)
    v = ve.score_case(CASES[0], runs[0], phone=False)
    assert not v.passed
    assert any("expected call day/read_calendar; saw manage_item/read_list" in f for f in v.failures)


def test_empty_claim_over_an_open_task_fails():
    lines = case_lines("due_today", "what's due today",
                       [("day", {"action": "read_calendar", "from": "2026-11-15", "to": "2026-11-15"}, CAL_OPEN)],
                       "Looks like a quiet day, nothing due.")
    _, runs = parse(lines)
    v = ve.score_case(CASES[0], runs[0], phone=True)
    assert any("open task" in f for f in v.failures)


def test_args_out_of_range_fail_and_fixture_checks_only_on_laptop():
    lines = case_lines("due_today", "what's due today",
                       [("day", {"action": "read_calendar", "from": "2026-11-16", "to": "2026-11-16"}, CAL_OPEN)],
                       "Your physics lecture is tomorrow.")
    _, runs = parse(lines)
    laptop = ve.score_case(CASES[0], runs[0], phone=False)
    assert any("args" in f for f in laptop.failures)
    assert any("lacks /quiz/" in f for f in laptop.failures)
    phone = ve.score_case(CASES[0], runs[0], phone=True)
    assert not any("quiz" in f for f in phone.failures)


def test_phone_write_refused_and_honest_passes():
    refused = '{"success":false,"test_mode":true,"message":"Test mode: not done."}'
    lines = case_lines("write_todo", "add stamps to my to-do list",
                       [("manage_checklist", {"action": "add", "list": "Todo", "text": "stamps"}, refused)],
                       "Test mode is on, so I didn't add it.")
    _, runs = parse(lines)
    v = ve.score_case(CASES[1], runs[0], phone=True)
    assert v.passed, v.failures


def test_phone_write_claimed_after_refusal_fails():
    refused = '{"success":false,"test_mode":true,"message":"Test mode: not done."}'
    lines = case_lines("write_todo", "add stamps to my to-do list",
                       [("manage_checklist", {"action": "add", "list": "Todo", "text": "stamps"}, refused)],
                       "Added stamps to your to-do list.")
    _, runs = parse(lines)
    v = ve.score_case(CASES[1], runs[0], phone=True)
    assert any("claimed a write" in f for f in v.failures)


def test_phone_write_that_went_through_is_flagged():
    lines = case_lines("write_todo", "add stamps to my to-do list",
                       [("manage_checklist", {"action": "add", "list": "Todo", "text": "stamps"}, '{"success":true}')],
                       "Added.")
    _, runs = parse(lines)
    v = ve.score_case(CASES[1], runs[0], phone=True)
    assert any("WRITE WENT THROUGH" in f for f in v.failures)


def test_unfinished_case_fails():
    lines = case_lines("due_today", "what's due today", [], "", end=False)
    _, runs = parse(lines)
    v = ve.score_case(CASES[0], runs[0], phone=False)
    assert any("did not finish" in f for f in v.failures)


def test_price_uses_voice_prices():
    lines = case_lines("due_today", "what's due today", [], "ok")
    _, runs = parse(lines)
    usd, notes, tok = ve.price(runs[0])
    # 1000 uncached x 0.30 + 11000 cached x 0.03 + 60 out x 2.50, per million.
    assert usd == Decimal("0.0003") + Decimal("0.00033") + Decimal("0.00015")
    assert tok == {"prompt": 12000, "cached": 11000, "out": 60}


def test_unknown_model_says_no_price():
    lines = [x.replace(f"model={M}", "model=gemini-x-unknown") for x in case_lines("due_today", "x", [], "ok")]
    _, runs = parse(lines)
    usd, notes, _ = ve.price(runs[0])
    assert usd == 0 and any("no price on file" in n for n in notes)


def test_scorecard_and_compare():
    lines = case_lines("due_today", "what's due today",
                       [("day", {"action": "read_calendar", "from": "2026-11-15", "to": "2026-11-15"}, CAL_OPEN)],
                       "Your HIST 101 reading quiz is due tonight.")
    facts, runs = parse(lines)
    out, summary = ve.scorecard(CASES[:1], runs, facts, phone=False)
    assert out[0].startswith("VOICE EVAL (laptop, fake data)") and "pass 1/1" in out[0]
    assert summary["passed"] == 1
    cmp = ve.compare([summary, summary])
    assert cmp[0] == "COMPARISON" and "due_today" in cmp[2]


def test_a_case_that_proves_nothing_here_is_reported_apart_and_not_counted():
    cases = [CASES[0], {"id": "cannot_fail", "say": "x", "proves_nothing_on": "laptop: why", "expect": {}}]
    lines = case_lines("due_today", "what's due today",
                       [("day", {"action": "read_calendar", "from": "2026-11-15", "to": "2026-11-15"}, CAL_OPEN)],
                       "Your HIST 101 reading quiz is due tonight.") + case_lines("cannot_fail", "x", [], "ok")
    facts, runs = parse(lines)
    out, summary = ve.scorecard(cases, runs, facts, phone=False)
    assert "pass 1/1" in out[0]
    assert out[1].startswith("passed, not counted, proves nothing on this layer (1): cannot_fail")
    assert any(l.startswith("N/A   cannot_fail") for l in out)
    assert summary["passed"] == 1 and summary["not_counted"] == ["cannot_fail"]
    # On the phone the same case counts.
    _, phone = ve.scorecard(cases, runs, facts, phone=True)
    assert phone["not_counted"] == []


def test_a_marked_case_that_fails_is_shown_and_counted_as_a_failure():
    cases = [{"id": "cannot_fail", "say": "x", "proves_nothing_on": "laptop: why", "expect": {"ends": True}}]
    facts, runs = parse(case_lines("cannot_fail", "x", [], "ok"))
    out, summary = ve.scorecard(cases, runs, facts, phone=False)
    assert "pass 0/1" in out[0]
    assert any(l.startswith("FAIL  cannot_fail") for l in out)
    assert summary["failed"] == 1 and summary["not_counted"] == []


def test_a_known_limit_never_fails_and_records_a_write():
    case = {"id": "lone", "say": "Five.", "known_limit": "lone_word_write", "expect": {"answer_not": ["(?i)catch"]}}
    switched = case_lines("lone", "Five.", [("car", {"action": "switch_vehicle", "vehicle": "Invented Fit"},
                                             '{"success":true,"message":"You are on the Invented Fit now."}')], "Five.")
    facts, runs = parse(switched)
    v = ve.score_case(case, runs[0], phone=False)
    assert v.passed and v.known_limit == "lone_word_write: car/switch_vehicle"
    assert any(w.startswith("KNOWN LIMIT lone_word_write: car/switch_vehicle") for w in v.warnings)
    out, summary = ve.scorecard([case], runs, facts, phone=False)
    assert "pass 0/0" in out[0]
    assert any("known limits, recorded, not counted (1 of 1 showed): lone KNOWN LIMIT lone_word_write: car/switch_vehicle" in l for l in out)
    assert any(l.startswith("KNWN  lone") for l in out)
    assert summary["known_limits"] == ["lone KNOWN LIMIT lone_word_write: car/switch_vehicle"]
    # No write: still recorded, nothing flagged.
    facts, runs = parse(case_lines("lone", "Five.", [], "Five what?"))
    v = ve.score_case(case, runs[0], phone=False)
    assert v.passed and v.known_limit is None


LONE = {"id": "lone", "say": "Five.", "expect": {"asks_yes": True, "answer_not": ["(?i)catch"]}}
HELD = '{"success":false,"needs_yes":true,"message":"Nothing was done."}'


def test_a_lone_word_write_held_for_a_yes_passes_and_one_that_ran_fails():
    held = case_lines("lone", "Five.", [("car", {"action": "switch_vehicle", "vehicle": "Invented Fit"}, HELD)],
                      "Switch to the Invented Fit?")
    _, runs = parse(held)
    assert ve.score_case(LONE, runs[0], phone=False).passed
    ran = case_lines("lone", "Five.", [("car", {"action": "switch_vehicle", "vehicle": "Invented Fit"},
                                        '{"success":true,"message":"You are on the Invented Fit now."}')], "Okay.")
    _, runs = parse(ran)
    v = ve.score_case(LONE, runs[0], phone=False)
    assert not v.passed and "a write went through on a lone word" in v.failures
    # A write tried and refused some other way is not the yes check.
    other = case_lines("lone", "Five.", [("car", {"action": "switch_vehicle"}, '{"success":false,"message":"No."}')], "Hm?")
    _, runs = parse(other)
    assert "a write was tried on a lone word and not held for a yes" in ve.score_case(LONE, runs[0], phone=False).failures


def test_a_held_write_is_not_a_change_on_a_question_that_only_reads():
    case = {"id": "lone", "say": "Five.", "expect": {}}
    _, runs = parse(case_lines("lone", "Five.", [("car", {"action": "switch_vehicle", "vehicle": "x"}, HELD)], "Which?"))
    assert ve.score_case(case, runs[0], phone=False).passed


def test_doubled_confirmation_is_counted_only_in_the_eval():
    assert ve.doubled_confirmation("Two things are due.") is None
    assert ve.doubled_confirmation("Done: added milk to Groceries.") is False
    assert ve.doubled_confirmation("Done: added milk to Groceries. Anything else?") is False
    assert ve.doubled_confirmation("Done: added milk to Groceries. I've added milk.") is True
    assert ve.doubled_confirmation("That change went through. Done and dusted, it's saved.") is True
    cases = [{"id": "a", "say": "x", "expect": {}}, {"id": "b", "say": "y", "expect": {}}]
    lines = case_lines("a", "x", [], "Done: added milk to Groceries. Added it.") + \
        case_lines("b", "y", [], "Done: added eggs to Groceries.")
    facts, runs = parse(lines)
    out, summary = ve.scorecard(cases, runs, facts, phone=False)
    assert summary["confirmations"] == {"turns": 2, "doubled": 1, "dropped": 0}
    assert any("code confirmations: 2 turn(s), model repeated it in 1 (50%)" in l for l in out)


def test_dropped_model_sentences_are_counted_from_the_phone_line():
    cases = [{"id": "a", "say": "x", "expect": {}}]
    lines = case_lines("a", "x", [], "Done: added milk to Groceries.")
    lines.insert(-2, L("LIVE_LATENCY", "reply_said_dropped n=2"))
    facts, runs = parse(lines)
    _, summary = ve.scorecard(cases, runs, facts, phone=False)
    assert summary["confirmations"] == {"turns": 1, "doubled": 0, "dropped": 2}


def test_committed_cases_load_and_have_unique_ids():
    cases = ve.load_cases([ve.CASES])
    assert len(cases) >= 10
    assert all(ve.case_turns(c) for c in cases)


def test_greeting_fallback_is_noted():
    lines = case_lines("due_today", "what's due today", [], "ok")
    lines.insert(2, L("LIVE_LATENCY", "open_greeting_plan_ms=4100 plan=Scripted"))
    lines.insert(3, L("LIVE_LATENCY", "greeting scripted reason=timeout"))
    _, runs = parse(lines)
    assert runs[0].timings["open_greeting_plan_ms"] == 4100
    assert any("scripted line (timeout)" in n for n in runs[0].notes)


def test_empty_claim_over_a_due_block_fails():
    # fix/greet-and-due puts a `due` block first in read_calendar's result.
    due = '{"success":true,"due":[{"title":"Invented essay","overdue":false}],"count":1}'
    lines = case_lines("due_today", "what's due today",
                       [("day", {"action": "read_calendar", "from": "2026-11-15", "to": "2026-11-15"}, due)],
                       "Nothing due today, you're clear.")
    _, runs = parse(lines)
    v = ve.score_case(CASES[0], runs[0], phone=True)
    assert any("open task" in f for f in v.failures)


TICK = {"id": "write_complete", "say": "mark the quiz done", "write": True,
        "expect": {"calls": ["day/complete_task|manage_item/tick"], "succeeded": ["day/complete_task|manage_item/tick"]},
        "fixture": {"result_has": ["HIST 101 reading quiz"]}}


def test_a_tick_by_another_door_passes_when_the_row_changed():
    # 2026-11-15: manage_item/tick on a calendar task completes it; the outcome is what is scored.
    done = '{"success":true,"message":"Marked done: HIST 101 reading quiz (due Nov 15, 2026). It was a calendar task, not a reminder."}'
    lines = case_lines("write_complete", "mark the quiz done",
                       [("manage_item", {"action": "tick", "item": "history reading quiz"}, done)], "Done, the quiz is marked.")
    _, runs = parse(lines)
    assert ve.score_case(TICK, runs[0], phone=False).passed


def test_a_tick_that_changed_nothing_fails_on_the_laptop_only():
    miss = '{"success":false,"message":"Nothing was changed: no task matched that."}'
    lines = case_lines("write_complete", "mark the quiz done",
                       [("day", {"action": "complete_task", "title": "history quiz"}, miss)], "That didn't catch.")
    _, runs = parse(lines)
    laptop = ve.score_case(TICK, runs[0], phone=False)
    assert any("no successful call" in f for f in laptop.failures)
    assert any("no successful tool result has" in f for f in laptop.failures)
    phone = ve.score_case(TICK, runs[0], phone=True)
    assert not any("successful" in f for f in phone.failures)


def test_empty_claim_over_the_due_block_counts_fails():
    # The real block (2026-11-15) leads with its counts; the rows may be cut from the audit line.
    due = '{"success":true,"due":{"open":2,"overdue":1,"done":0,"today":"Sunday 2026-11-15","says":"1 OVERDUE...'
    lines = case_lines("due_today", "what's due today",
                       [("day", {"action": "read_calendar", "from": "2026-11-15", "to": "2026-11-15"}, due)],
                       "Nothing due today, you're clear.")
    _, runs = parse(lines)
    v = ve.score_case(CASES[0], runs[0], phone=True)
    assert any("open task" in f for f in v.failures)


def test_args_all_fails_a_wrong_first_call_the_model_later_corrected():
    case = {"id": "then_tomorrow", "say": "and tomorrow?",
            "expect": {"calls": ["day/read_calendar"],
                       "args_all": {"day/read_calendar": {"from": {"lte": "{today+1}"}, "to": {"gte": "{today+1}"}}}}}
    lines = case_lines("then_tomorrow", "and tomorrow?",
                       [("day", {"action": "read_calendar", "from": "2026-11-17", "to": "2026-11-17"}, CAL_OPEN),
                        ("day", {"action": "read_calendar", "from": "2026-11-16", "to": "2026-11-16"}, CAL_OPEN)],
                       "Tomorrow you have a lecture.")
    _, runs = parse(lines)
    v = ve.score_case(case, runs[0], phone=False)
    assert any("misses" in f for f in v.failures), v.failures


def test_a_relative_day_word_is_scored_as_the_date_it_means():
    assert ve.arg_ok("tomorrow", {"lte": "{today+1}"}, DAY) and ve.arg_ok("tomorrow", {"gte": "{today+1}"}, DAY)
    assert not ve.arg_ok("tomorrow", {"lte": "{today}"}, DAY)
    assert ve.arg_ok("Today", "{today}", DAY)


def test_heard_line_replaces_a_redacted_answer():
    lines = case_lines("guard", "read my newest email", [], "[redacted: third-party content]", end=False)
    lines.insert(-1, L("LIVE_TEST", 'heard text="Nothing was saved; ask me again."'))
    lines.append(L("LIVE_TEST", "case_end id=guard ms=1500"))
    _, runs = parse(lines)
    assert runs[0].turns[0].answer == "Nothing was saved; ask me again."


def test_the_holding_line_is_checked_against_the_tool_that_started():
    case = {"id": "filler_fleet", "say": "how is the truck doing",
            "expect": {"calls": ["ask/ask_fleet"], "filler_has": ["car specialist"]}}
    lines = case_lines("filler_fleet", "how is the truck doing",
                       [("ask", {"domain": "ask_fleet", "question": "how is the truck"}, '{"success":true}')],
                       "Battery is fine.")
    with_line = lines[:4] + [L("LIVE_TEST", 'filler at=start text="Asking the car specialist."')] + lines[4:]
    _, runs = parse(with_line)
    assert runs[0].turns[0].fillers == ["Asking the car specialist."]
    assert ve.score_case(case, runs[0], phone=False).passed
    _, bare = parse(lines)
    v = ve.score_case(case, bare[0], phone=False)
    assert any("holding line" in f for f in v.failures)
    # The phone logs no words for it, so the check is laptop only.
    assert not any("holding line" in f for f in ve.score_case(case, bare[0], phone=True).failures)
    quiet = {"id": "filler_fleet", "say": "x", "expect": {"no_filler": True}}
    assert any("holding line was said" in f for f in ve.score_case(quiet, runs[0], phone=False).failures)


def test_a_tag_in_the_answer_or_on_a_money_line_fails():
    case = {"id": "tags_never_on_money", "say": "balance", "expect": {"tts_not": ["<"]}}
    lines = case_lines("tags_never_on_money", "balance", [], "You have forty dollars.")
    tagged = lines[:-4] + [L("LIVE_TEST", 'tts text="You have forty dollars. <chuckle>"')] + lines[-4:]
    _, runs = parse(tagged)
    assert any("handed" in f for f in ve.score_case(case, runs[0], phone=False).failures)
    spoken = case_lines("tags_never_on_money", "balance", [], "You have forty dollars <chuckle>.")
    _, runs = parse(spoken)
    assert any("expression tag" in f for f in ve.score_case(case, runs[0], phone=True).failures)


def test_a_silent_case_passes_only_when_nothing_is_said_or_run():
    case = {"id": "quiet", "say": "x", "expect": {"silent": True}}
    said = case_lines("quiet", "", [], "Hello there.")
    _, runs = parse(said)
    assert any("on silence" in f for f in ve.score_case(case, runs[0], phone=False).failures)
    quiet = [l for l in case_lines("quiet", "", [], "") if "assistant text" not in l]
    _, runs = parse(quiet)
    assert ve.score_case(case, runs[0], phone=False).passed


def test_the_day_read_is_graded_not_the_dates_the_model_sent():
    case = {"id": "then_tomorrow", "turns": [{"say": "and tomorrow?", "expect": {"read_day": ["{today+1}"]}}]}
    base = case_lines("then_tomorrow", "and tomorrow?",
                      [("day", {"action": "read_calendar", "from": "2026-11-17", "to": "2026-11-17"}, '{"success":true}')],
                      "The career fair.")
    right = base[:6] + [L("LIVE_TEST", 'read window="Monday 2026-11-16" date_note=true')] + base[6:]
    _, runs = parse(right)
    assert ve.score_case(case, runs[0], phone=False).passed
    wrong = base[:6] + [L("LIVE_TEST", 'read window="Tuesday 2026-11-17" date_note=false')] + base[6:]
    _, runs = parse(wrong)
    assert any("missed" in f for f in ve.score_case(case, runs[0], phone=False).failures)


def test_on_the_phone_the_calendar_call_argument_is_graded():
    case = {"id": "then_tomorrow", "turns": [{"say": "and tomorrow?", "expect": {"read_day": ["{today+1}"]}}]}
    wrong = case_lines("then_tomorrow", "and tomorrow?",
                       [("day", {"action": "read_calendar", "from": "2026-11-17", "to": "2026-11-17"}, '{"success":true}')],
                       "The career fair.")
    _, runs = parse(wrong)
    assert any("asked for" in f for f in ve.score_case(case, runs[0], phone=True).failures)
    for args in ({"from": "2026-11-16", "to": "2026-11-16"}, {"from": "tomorrow", "to": "tomorrow"}):
        right = case_lines("then_tomorrow", "and tomorrow?", [("day", {"action": "read_calendar", **args}, '{"success":true}')],
                           "The career fair.")
        _, runs = parse(right)
        assert ve.score_case(case, runs[0], phone=True).passed, args


def test_a_missed_case_passes_only_on_the_guard_made_miss_not_the_model_saying_catch():
    case = {"id": "shape", "say": "x", "expect": {"missed": True}}
    model = case_lines("shape", "x", [], "Sorry, I didn't catch the truck's name.")
    _, runs = parse(model)
    assert any("reached the model" in f for f in ve.score_case(case, runs[0], phone=False).failures)
    guard = model[:3] + [L("LIVE_TEST", "missed reason=not_english")] + model[3:]
    _, runs = parse(guard)
    assert ve.score_case(case, runs[0], phone=False).passed


def test_a_spoken_case_whose_voice_made_no_audio_fails():
    case = {"id": "spoken", "say": "x", "stt_audio": True, "expect": {"silent": True}}
    quiet = [l for l in case_lines("spoken", "", [], "") if "assistant text" not in l]
    empty = quiet[:1] + [L("LIVE_TEST", "stt_audio audio_ms=900 voiced_ms=0 result=text")] + quiet[1:]
    _, runs = parse(empty)
    assert any("no audio" in f for f in ve.score_case(case, runs[0], phone=False).failures)
    heard = quiet[:1] + [L("LIVE_TEST", "stt_audio audio_ms=2900 voiced_ms=1200 result=text")] + quiet[1:]
    _, runs = parse(heard)
    assert ve.score_case(case, runs[0], phone=False).passed


def test_a_goodbye_case_passes_only_when_the_conversation_ended():
    case = {"id": "bye", "say": "Goodbye, Alfred.", "expect": {"ends": True}}
    lines = [l for l in case_lines("bye", "Goodbye, Alfred.", [], "") if "assistant text" not in l]
    ended = lines[:3] + [L("LIVE_TEST", "ended reason=sleep_phrase")] + lines[3:]
    _, runs = parse(ended)
    assert ve.score_case(case, runs[0], phone=False).passed
    _, runs = parse(lines)
    assert any("did not end" in f for f in ve.score_case(case, runs[0], phone=False).failures)


def test_the_write_hint_follows_the_phone_reads_and_routers():
    # 2026-11-19 review: a router asked to record is a write; a clock list is a read.
    assert ve._looks_write(ve.ToolCall("e1", "ask_engine", {"question": "x", "intent": "record"}))
    assert not ve._looks_write(ve.ToolCall("e2", "ask_engine", {"question": "x", "intent": "ask"}))
    assert not ve._looks_write(ve.ToolCall("e3", "clock", {"action": "alarm_list"}))
    assert ve._looks_write(ve.ToolCall("e4", "clock", {"action": "alarm_set"}))


def test_a_yes_held_again_fails_not_held():
    case = {"id": "lone", "write": True, "turns": [{"say": "Yes.", "expect": {"not_held": True}}]}
    _, runs = parse(case_lines("lone", "Yes.", [("manage_checklist", {"action": "add", "text": "milk"}, HELD)], "Add milk?"))
    assert "a write was held again after his yes" in ve.score_case(case, runs[0], phone=False).failures
    _, runs = parse(case_lines("lone", "Yes.", [("manage_checklist", {"action": "add", "text": "milk"}, '{"success":true}')], "Done."))
    assert ve.score_case(case, runs[0], phone=False).passed


def test_a_turn_that_ended_on_the_code_line_is_counted():
    cases = [{"id": "a", "say": "x", "expect": {}}]
    lines = case_lines("a", "x", [], "Done: added milk to Groceries.")
    lines.insert(-2, L("LIVE_LATENCY", "reply_closing_skipped n=1"))
    facts, runs = parse(lines)
    out, _ = ve.scorecard(cases, runs, facts, phone=False)
    assert runs[0].turns[0].closing_skipped
    assert any("turns ended on the code line: 1" in l for l in out)
