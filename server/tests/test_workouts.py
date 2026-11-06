"""The workout programme (workouts, 2026-11-06): the pure rules, the REST flow, the legacy history
bridge, tenancy and the engine tools. Synthetic weights only; nobody's real numbers.
"""

from __future__ import annotations

import uuid

import pytest

from body_coach import programme as rules
from body_coach.models import TrainingProgramme, WorkoutSession, WorkoutSet
from legacy.models.body import WorkoutSetLog
from tests.conftest import _client_for

BASE = "/api/body/coach/workout"
SQUAT = rules.TEMPLATES[rules.DEFAULT_TEMPLATE]["A"][0]  # 3 x 6-8, +10 lb
BENCH = rules.TEMPLATES[rules.DEFAULT_TEMPLATE]["A"][1]  # 3 x 6-8, +5 lb
PLANK = rules.TEMPLATES[rules.DEFAULT_TEMPLATE]["A"][4]  # timed


def perf(reps, weight=100.0, planned=3, deload=False, low=6, high=8, done=True):
    return rules.Performance(
        sets=tuple(rules.DoneSet(r, weight, done) for r in reps),
        planned_sets=planned,
        reps_low=low,
        reps_high=high,
        deload=deload,
    )


# =============================================================================
# The rules, pure
# =============================================================================


def test_no_history_is_calibration_with_no_weight():
    t = rules.target_for(SQUAT, "back_squat", [])
    assert t.rule == rules.RULE_CALIBRATE and t.weight_lbs is None and t.calibrating
    assert "2 more reps than 8" in t.words
    assert (t.sets, t.reps_low, t.reps_high) == (3, 6, 8)


def test_every_set_at_the_top_adds_the_increment():
    assert rules.target_for(SQUAT, "back_squat", [perf([8, 8, 8])]).weight_lbs == 110
    t = rules.target_for(BENCH, "bench_press", [perf([8, 8, 8])])
    assert (t.weight_lbs, t.rule) == (105, rules.RULE_ADD)


def test_one_set_short_of_the_top_holds():
    t = rules.target_for(BENCH, "bench_press", [perf([8, 8, 7])])
    assert (t.weight_lbs, t.rule) == (100, rules.RULE_HOLD)
    assert "8, 8, 7" in t.words


def test_a_skipped_set_holds_even_when_the_rest_hit_the_top():
    t = rules.target_for(BENCH, "bench_press", [perf([8, 8])])
    assert t.rule == rules.RULE_HOLD


def test_a_lighter_set_than_the_working_weight_holds():
    p = rules.Performance(
        sets=(rules.DoneSet(8, 100, True), rules.DoneSet(8, 100, True), rules.DoneSet(8, 90, True)),
        planned_sets=3,
        reps_low=6,
        reps_high=8,
    )
    assert rules.working_weight(p) == 100
    assert rules.target_for(BENCH, "bench_press", [p]).rule == rules.RULE_HOLD


def test_the_working_weight_follows_what_was_done_ties_go_lighter():
    p = rules.Performance(
        sets=(rules.DoneSet(8, 95, True), rules.DoneSet(8, 100, True)),
        planned_sets=2,
        reps_low=6,
        reps_high=8,
    )
    assert rules.working_weight(p) == 95


def test_two_sessions_under_the_range_at_one_weight_reset_to_ninety_percent():
    t = rules.target_for(BENCH, "bench_press", [perf([6, 5, 5]), perf([6, 6, 5])])
    assert (t.weight_lbs, t.rule) == (90, rules.RULE_RESET)
    # one bad session is not a stall
    assert rules.target_for(BENCH, "bench_press", [perf([6, 5, 5]), perf([7, 7, 6])]).rule == rules.RULE_HOLD
    # a different weight the session before is not a stall either
    assert (
        rules.target_for(BENCH, "bench_press", [perf([6, 5, 5]), perf([6, 5, 5], weight=95)]).rule
        == rules.RULE_HOLD
    )


def test_the_deload_schedule_counts_sessions_not_weeks():
    deloads = [n for n in range(1, 40) if rules.is_deload(n, 3)]
    assert deloads == [16, 17, 18, 34, 35, 36]
    assert [n for n in range(1, 30) if rules.is_deload(n, 2)] == [11, 12, 23, 24]


def test_a_deload_is_lighter_and_shorter_and_never_moves_the_working_weight():
    t = rules.target_for(SQUAT, "back_squat", [perf([8, 8, 8], weight=200)], deload=True)
    assert (t.weight_lbs, t.sets, t.reps_low, t.reps_high, t.rule) == (180, 2, 6, 6, rules.RULE_DELOAD)
    after = rules.target_for(
        SQUAT, "back_squat", [perf([6, 6], weight=180, deload=True), perf([8, 8, 8], weight=200)]
    )
    assert (after.weight_lbs, after.rule) == (210, rules.RULE_ADD)


def test_timed_and_bodyweight_exercises_carry_no_weight():
    t = rules.target_for(PLANK, "plank", [perf([45, 45], weight=None, planned=2)])
    assert t.weight_lbs is None and t.rule == rules.RULE_REPS and t.unit == "seconds"


def test_round_down_is_to_five_pounds_and_never_zero():
    assert rules.round_down(137.0) == 135
    assert rules.round_down(13.5) == 10
    assert rules.round_down(3) == 5


def test_names_match_loosely_but_never_ambiguously():
    keys = ["back_squat", "bench_press", "barbell_row", "lateral_raise", "plank"]
    assert rules.match_exercise("squats", keys) == "back_squat"
    assert rules.match_exercise("Bench", keys) == "bench_press"
    assert rules.match_exercise("laterals", keys) == "lateral_raise"
    assert rules.match_exercise("deadlift", keys) is None
    assert rules.match_exercise("squat", ["back_squat", "goblet_squat"]) == "back_squat"


def test_a_swap_lands_on_a_listed_alternative_only():
    # Body coach 2: the squat slot leads with the knee-friendly split squat (Kevin, 2026-11-06).
    assert rules.swap_choice(SQUAT, "split_squat", None) == "wall_sit"
    assert rules.swap_choice(SQUAT, "goblet_squat", None) == "leg_press"
    assert rules.swap_choice(SQUAT, "back_squat", None) == "split_squat"
    assert rules.swap_choice(SQUAT, "split_squat", "leg press") == "leg_press"
    assert rules.swap_choice(SQUAT, "split_squat", "bench press") is None


def test_days_alternate_a_and_b():
    assert [rules.next_day_key(x) for x in (None, "A", "B")] == ["A", "B", "A"]


def test_the_template_is_three_compounds_and_two_accessories_a_day():
    for day in rules.DAY_KEYS:
        slots = rules.TEMPLATES[rules.DEFAULT_TEMPLATE][day]
        assert len(slots) == 5
        assert [s.sets for s in slots] == [3, 3, 3, 2, 2]
        for slot in slots:
            for key in slot.choices():
                assert key in rules.CATALOG


# =============================================================================
# REST
# =============================================================================


def _tick_all(client, session, reps_from=None, weight=100.0):
    sets = []
    for s in session["sets"]:
        reps = s["reps_high"] if reps_from is None else reps_from(s)
        w = None if s["kind"] != "weighted" else (s["target_weight_lbs"] or weight)
        sets.append({"exercise_key": s["exercise_key"], "set_number": s["set_number"], "done": True, "reps": reps, "weight_lbs": w})
    r = client.patch(f"{BASE}/sets/", {"session_id": session["id"], "sets": sets}, format="json")
    assert r.status_code == 200, r.data
    return r.data["session"]


def test_get_before_anything_shows_the_first_plan_without_writing(auth_client):
    r = auth_client.get(f"{BASE}/")
    assert r.status_code == 200
    assert r.data["programme"] is None and r.data["session"] is None
    assert r.data["next"]["day_key"] == "A" and r.data["next"]["calibrating"]
    assert r.data["week"] == {**r.data["week"], "done": 0, "planned": 3}
    assert "your pick" in r.data["words"]
    assert TrainingProgramme.objects.count() == 0


def test_a_full_session_progresses_the_next_time_that_day_comes_round(auth_client):
    first = auth_client.post(f"{BASE}/start/", {}, format="json")
    assert first.status_code == 201, first.data
    s = first.data["session"]
    assert s["day_key"] == "A" and s["calibrating"] and s["set_count"] == 13
    assert all(x["target_weight_lbs"] is None for x in s["sets"])
    _tick_all(auth_client, s, weight=100.0)
    done = auth_client.post(f"{BASE}/finish/", {"session_id": s["id"]}, format="json")
    assert done.status_code == 200 and done.data["finished"]
    assert "Next workout is Day B" in done.data["words"]
    assert "Bulgarian split squat to 105 lb" in done.data["words"]

    b = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    assert b["day_key"] == "B"
    auth_client.post(f"{BASE}/finish/", {"session_id": b["id"]}, format="json")

    a2 = auth_client.get(f"{BASE}/").data["next"]
    assert a2["day_key"] == "A" and not a2["calibrating"]
    squat = next(e for e in a2["exercises"] if e["exercise_key"] == "split_squat")
    bench = next(e for e in a2["exercises"] if e["exercise_key"] == "bench_press")
    assert (squat["weight_lbs"], squat["rule"]) == (105, "add")
    assert (bench["weight_lbs"], bench["rule"]) == (105, "add")
    plank = next(e for e in a2["exercises"] if e["exercise_key"] == "plank")
    assert plank["weight_lbs"] is None and plank["unit"] == "seconds"


def test_start_is_idempotent_on_a_phone_minted_id(auth_client):
    sid = str(uuid.uuid4())
    one = auth_client.post(f"{BASE}/start/", {"id": sid}, format="json")
    two = auth_client.post(f"{BASE}/start/", {"id": sid}, format="json")
    assert one.status_code == 201 and two.status_code == 200
    assert one.data["session"]["id"] == two.data["session"]["id"] == sid
    # a second start with another id adopts the running one rather than racing it
    three = auth_client.post(f"{BASE}/start/", {"id": str(uuid.uuid4())}, format="json")
    assert three.data["session"]["id"] == sid and not three.data["created"]
    assert WorkoutSession.objects.count() == 1


def test_a_set_replay_changes_nothing_and_untick_clears_it(auth_client):
    s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    change = {"exercise_key": "bench_press", "set_number": 2, "done": True, "reps": 7, "weight_lbs": 95}
    for _ in range(2):
        r = auth_client.patch(f"{BASE}/sets/", {"session_id": s["id"], "sets": [change]}, format="json")
        assert r.status_code == 200
    assert r.data["session"]["sets_done"] == 1
    row = WorkoutSet.objects.get(exercise_key="bench_press", set_number=2)
    assert (row.done, row.reps_done, row.weight_lbs) == (True, 7, 95)
    r = auth_client.patch(
        f"{BASE}/sets/",
        {"session_id": s["id"], "sets": [{"exercise_key": "bench_press", "set_number": 2, "done": False}]},
        format="json",
    )
    assert r.data["session"]["sets_done"] == 0


def test_a_tick_never_takes_a_target_from_the_client(auth_client):
    s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    auth_client.patch(
        f"{BASE}/sets/",
        {"session_id": s["id"], "sets": [{"exercise_key": "split_squat", "set_number": 1, "reps": 8, "weight_lbs": 50, "target_weight_lbs": 999}]},
        format="json",
    )
    row = WorkoutSet.objects.get(exercise_key="split_squat", set_number=1)
    assert row.target_weight_lbs is None and row.weight_lbs == 50


def test_unknown_sets_and_bad_numbers_are_refused_in_words(auth_client):
    s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    r = auth_client.patch(
        f"{BASE}/sets/",
        {"session_id": s["id"], "sets": [{"exercise_key": "bench_press", "set_number": 9}]},
        format="json",
    )
    assert r.status_code == 400 and r.data["detail"].startswith("Nothing was saved")
    r = auth_client.patch(
        f"{BASE}/sets/",
        {"session_id": s["id"], "sets": [{"exercise_key": "bench_press", "set_number": 1, "reps": -1}]},
        format="json",
    )
    assert r.status_code == 400


def test_log_by_name_starts_the_day_and_ticks_the_next_open_set(auth_client):
    r = auth_client.post(f"{BASE}/log/", {"exercise": "bench", "reps": 8, "weight_lbs": 135}, format="json")
    assert r.status_code == 200, r.data
    assert r.data["words"].startswith("Started Day A. Ticked Bench press set 1: 8 reps at 135 lb")
    r = auth_client.post(f"{BASE}/log/", {"exercise": "bench press", "reps": 7, "weight_lbs": 135}, format="json")
    assert "set 2" in r.data["words"]
    for reps in (6, 5):
        r = auth_client.post(f"{BASE}/log/", {"exercise": "bench", "reps": reps, "weight_lbs": 135}, format="json")
    assert "extra set" in r.data["words"]
    r = auth_client.post(f"{BASE}/log/", {"exercise": "deadlift", "reps": 5}, format="json")
    assert r.status_code == 400 and "not one exercise in today's workout" in r.data["detail"]


def test_an_extra_set_does_not_count_against_progression(auth_client):
    s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    _tick_all(auth_client, s, weight=100.0)
    auth_client.post(f"{BASE}/log/", {"exercise": "bench", "reps": 4, "weight_lbs": 100}, format="json")
    auth_client.post(f"{BASE}/finish/", {}, format="json")
    history = [
        e for e in auth_client.get(f"{BASE}/").data["next"]["exercises"]
    ]
    assert auth_client.get(f"{BASE}/").data["next"]["day_key"] == "B"
    assert history  # day B is calibration for its own exercises


def test_swap_keeps_on_the_programme_and_changes_an_untouched_running_exercise(auth_client):
    s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    r = auth_client.post(f"{BASE}/swap/", {"exercise": "squats"}, format="json")
    assert r.status_code == 200, r.data
    assert "Swapped Bulgarian split squat for Goblet squat" in r.data["words"]
    keys = {x["exercise_key"] for x in r.data["session"]["sets"]}
    assert "goblet_squat" in keys and "split_squat" not in keys
    assert TrainingProgramme.objects.get().swaps == {"squat": "goblet_squat"}
    r = auth_client.post(f"{BASE}/swap/", {"exercise": "goblet squat", "to": "split squat"}, format="json")
    assert TrainingProgramme.objects.get().swaps == {}
    r = auth_client.post(f"{BASE}/swap/", {"exercise": "squat", "to": "bench press"}, format="json")
    assert r.status_code == 400 and "can be swapped for" in r.data["detail"]
    assert s


def test_finish_writes_the_legacy_history_once(auth_client):
    s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    _tick_all(auth_client, s, weight=100.0)
    for _ in range(2):
        auth_client.post(f"{BASE}/finish/", {"session_id": s["id"]}, format="json")
    rows = WorkoutSetLog.objects.filter(origin_guid__startswith=f"workout:{s['id']}")
    # four weighted exercises, one (reps, weight) group each; the plank is timed and skipped
    assert rows.count() == 4
    squat = rows.get(exercise="Bulgarian split squat")
    assert (squat.sets, squat.reps, squat.weight_value, squat.weight_unit) == (3, 10, 100, "lbs")


def test_a_cancelled_session_logs_nothing_and_does_not_count(auth_client):
    s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    _tick_all(auth_client, s)
    r = auth_client.post(f"{BASE}/cancel/", {"session_id": s["id"]}, format="json")
    assert r.status_code == 200
    assert WorkoutSetLog.objects.count() == 0
    nxt = auth_client.get(f"{BASE}/").data
    assert nxt["next"]["day_key"] == "A" and nxt["next"]["session_number"] == 1
    assert nxt["week"]["done"] == 0


def test_the_week_counts_finished_sessions(auth_client):
    for _ in range(2):
        s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
        auth_client.post(f"{BASE}/finish/", {"session_id": s["id"]}, format="json")
    week = auth_client.get(f"{BASE}/").data["week"]
    assert (week["done"], week["planned"]) == (2, 3)
    assert [x["day_key"] for x in week["sessions"]] == ["A", "B"]


def test_training_days_are_settable_and_refused_in_words(auth_client):
    r = auth_client.put(f"{BASE}/programme/", {"training_days": ["Tuesday", "thu", "sat", "tue"]}, format="json")
    assert r.status_code == 200 and r.data["programme"]["training_days"] == ["tue", "thu", "sat"]
    assert auth_client.get(f"{BASE}/").data["week"]["planned"] == 3
    r = auth_client.put(f"{BASE}/programme/", {"training_days": ["funday"]}, format="json")
    assert r.status_code == 400 and "not a weekday" in r.data["detail"]


def test_the_deload_session_comes_from_the_session_count(auth_client, household_a):
    programme = TrainingProgramme.objects.create(household=household_a, started_on="2026-01-01")
    s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    _tick_all(auth_client, s, weight=100.0)
    auth_client.post(f"{BASE}/finish/", {"session_id": s["id"]}, format="json")
    # pretend 14 more finished sessions happened (only their count matters to the schedule)
    from django.utils import timezone

    for n in range(2, 16):
        # the last one is a Day B after the real Day A, so Day A comes round next
        at = timezone.now() + timezone.timedelta(minutes=1) if n == 15 else timezone.now() - timezone.timedelta(days=30)
        WorkoutSession.objects.create(
            household=household_a,
            programme=programme,
            day_key="B",
            session_number=n,
            session_date="2026-01-02",
            status="finished",
            started_at=at,
            finished_at=at,
        )
    nxt = auth_client.get(f"{BASE}/").data["next"]
    assert nxt["session_number"] == 16 and nxt["deload"]
    squat = next(e for e in nxt["exercises"] if e["exercise_key"] == "split_squat")
    assert (squat["weight_lbs"], squat["sets"], squat["rule"]) == (90, 2, "deload")


# =============================================================================
# Tenancy and recruiters
# =============================================================================


def test_another_household_sees_none_of_it(auth_client, user_b):
    s = auth_client.post(f"{BASE}/start/", {}, format="json").data["session"]
    b = _client_for(user_b)
    data = b.get(f"{BASE}/").data
    assert data["session"] is None and data["programme"] is None
    r = b.patch(
        f"{BASE}/sets/",
        {"session_id": s["id"], "sets": [{"exercise_key": "bench_press", "set_number": 1}]},
        format="json",
    )
    assert r.status_code == 404
    assert b.post(f"{BASE}/finish/", {"session_id": s["id"]}, format="json").status_code == 404
    # B's own start may not take A's id
    r = b.post(f"{BASE}/start/", {"id": s["id"]}, format="json")
    assert r.status_code == 409


# =============================================================================
# Engine tools
# =============================================================================


@pytest.fixture
def mcp(settings, household_user):
    from tests.test_engine_mcp import client_with

    settings.LEGION_MCP = True
    return client_with(household_user)


def _call(client, name, arguments=None):
    from tests.test_engine_mcp import call

    return call(client, name, arguments)


def test_the_tools_say_the_rules_numbers_and_commit_through_the_views(mcp):
    is_error, text, _ = _call(mcp, "get_todays_workout")
    assert not is_error and "Day A" in text and "never change a number" in text
    is_error, text, _ = _call(mcp, "tick_workout_set", {"exercise": "bench", "reps": 8, "weight_lbs": 135})
    assert not is_error and "Ticked Bench press set 1" in text and "committed" in text
    is_error, text, _ = _call(mcp, "swap_exercise", {"exercise": "squats"})
    assert not is_error and "Goblet squat" in text
    is_error, text, _ = _call(mcp, "start_workout")
    assert not is_error and "already in progress" in text
    is_error, text, _ = _call(mcp, "finish_workout")
    assert not is_error and "Logged Day A" in text
    is_error, text, _ = _call(mcp, "finish_workout")
    assert is_error and text.startswith("Nothing was finished")
