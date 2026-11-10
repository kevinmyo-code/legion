"""Body coach 2 (2026-11-06): the programme catalog and its filters, programme change proposals,
and what to eat from what's left. Synthetic people and numbers only.
"""

from __future__ import annotations

import itertools
import uuid
from datetime import time, timedelta

import pytest

from body_coach import programme as rules
from body_coach import programme_change as change
from body_coach import suggest
from body_coach.models import ProgrammeProposal, SavedMeal, TrainingProgramme
from legacy.models.pantry import MealLog, ReceiptLineItem
from tests.test_body_coach import eat, target, today
from tests.test_ingest_api import RECEIPT_URL, a_receipt

WORKOUT = "/api/body/coach/workout"
MEALS = "/api/body/coach/meal-options"


# =============================================================================
# The catalog and its filters, pure
# =============================================================================


def _all_filters():
    for n in range(len(rules.EQUIPMENT)):
        for excluded in itertools.combinations(rules.EQUIPMENT, n):
            for k in range(len(rules.JOINTS) + 1):
                for avoid in itertools.combinations(rules.JOINTS, k):
                    for cardio in (False, True):
                        yield rules.Filters(frozenset(excluded), frozenset(avoid), cardio)


def test_every_template_resolves_under_every_filter_without_a_repeat_or_a_banned_exercise():
    for template in rules.TEMPLATES:
        for f in _all_filters():
            for day in rules.day_keys(template):
                resolved = rules.resolve_day(template, day, {}, f)
                held = [r.exercise for r in resolved if r.exercise]
                assert len(held) == len(set(held)), (template, day, f)
                for key in held:
                    ex = rules.CATALOG[key]
                    assert any(e not in f.exclude_equipment for e in ex.equipment), (key, f)
                    assert not set(ex.joints) & f.avoid, (key, f)
                assert (resolved[-1].slot is rules.CARDIO_SLOT) == f.cardio
                for r in resolved:
                    assert r.exercise or r.paused_because


def test_every_choice_is_in_the_catalog_and_every_range_names_a_choice():
    for template, days in rules.TEMPLATES.items():
        for slots in days.values():
            for slot in slots:
                for key in slot.choices():
                    assert key in rules.CATALOG, (template, slot.key, key)
                for key, low, high in slot.ranges:
                    assert key in slot.choices() and 0 < low <= high


def test_the_default_lower_body_is_knee_friendly():
    """Kevin, 2026-11-06: split squats, wall sits and hinges by default; no heavy deep squat
    unless he asks for it."""
    for template in rules.TEMPLATES:
        for day in rules.day_keys(template):
            for _slot, key in rules.slots_for(template, day):
                assert rules.KNEE not in rules.CATALOG[key].joints, (template, day, key)


def test_home_means_a_kettlebell_and_bodyweight_only():
    home = rules.at_home(rules.Filters(cardio=True))
    for template in rules.TEMPLATES:
        for day in rules.day_keys(template):
            for _, key in rules.slots_for(template, day, {}, home):
                assert set(rules.CATALOG[key].equipment) & rules.HOME_EQUIPMENT, key
    assert rules.slots_for(rules.DEFAULT_TEMPLATE, "A", {}, home)[-1][1] == "brisk_walk"


def test_a_choice_with_its_own_range_gets_it():
    slot = rules.TEMPLATES[rules.DEFAULT_TEMPLATE]["B"][3]  # legs: wall sit, 30-60 s
    hold = rules.target_for(slot, "wall_sit", [])
    assert (hold.unit, hold.reps_low, hold.reps_high) == ("seconds", 30, 60)
    split = rules.target_for(slot, "split_squat", [])
    assert (split.unit, split.reps_low, split.reps_high) == ("reps", 8, 10)
    assert split.calibrating


def test_upper_lower_cycles_four_days_and_a_foreign_day_starts_it():
    assert [rules.next_day_key(x, rules.UPPER_LOWER) for x in (None, "A", "B", "C", "D")] == [
        "A", "B", "C", "D", "A",
    ]
    assert rules.next_day_key("B", rules.UPPER_LOWER) == "C"
    assert rules.next_day_key("C") == "A"


def test_cardio_is_minutes_and_adds_minutes():
    t = rules.target_for(rules.CARDIO_SLOT, "brisk_walk", [])
    assert (t.unit, t.sets, t.reps_low, t.reps_high, t.weight_lbs) == ("minutes", 1, 10, 20, None)
    assert "minutes" in t.words


# =============================================================================
# Programme changes, pure
# =============================================================================

FULL3 = change.State(rules.DEFAULT_TEMPLATE, ("mon", "wed", "fri"))


def _after(before=FULL3, **choices):
    return change.build_after(before, change.read_choices(choices))


def test_four_sessions_default_to_upper_lower_and_two_to_full_body():
    four = _after(sessions_per_week=4)
    assert (four.template, len(four.training_days)) == (rules.UPPER_LOWER, 4)
    two = _after(four, sessions_per_week=2)
    assert (two.template, len(two.training_days)) == (rules.DEFAULT_TEMPLATE, 2)
    kept = _after(four, sessions_per_week=3, split="upper_lower")
    assert kept.template == rules.UPPER_LOWER


def test_other_split_flips_and_a_named_split_wins():
    assert _after(split="other").template == rules.UPPER_LOWER
    assert _after(split="full_body") == FULL3


@pytest.mark.parametrize(
    "bad",
    [
        {"sessions_per_week": 5},
        {"sessions_per_week": True},
        {"training_days": ["mon"]},
        {"split": "bro"},
        {"avoid": ["elbow"]},
        {"without_equipment": ["rope"]},
        {"cardio": "yes"},
        {"swap_to": "bench"},
    ],
)
def test_choices_outside_the_schema_refuse(bad):
    with pytest.raises(change.ChangeRefused) as exc:
        _after(**bad)
    assert exc.value.message.startswith("Nothing was proposed")


def test_no_barbell_and_a_sore_shoulder_filter_every_day():
    after = _after(without_equipment=["barbell"], avoid=["shoulder"])
    for key in change.held(after):
        ex = rules.CATALOG[key]
        assert ex.equipment != (rules.BARBELL,) and rules.SHOULDER not in ex.joints
    d = change.diff(FULL3, after)
    dropped = {x["name"]: x["because"] for x in d["dropped"]}
    assert dropped["Bench press"] == "needs a barbell"
    assert dropped["Lateral raise"] == "loads the shoulder"
    text = change.words(FULL3, after, d, change.read_choices({"avoid": ["shoulder"]}))
    assert "see a physio" in text and text.endswith("Nothing changes until you accept.")
    assert "Kettlebell" not in text or "kettlebell" in text.lower()


def test_a_slot_with_no_allowed_choice_is_paused_and_named():
    # The press slot: every choice loads the shoulder except incline dumbbell press.
    after = _after(avoid=["shoulder"], without_equipment=["dumbbell"])
    paused = [p for day in change.days_preview(after) for p in day["paused"]]
    assert any(p["usually"] == "Overhead press" for p in paused)
    d = change.diff(FULL3, after)
    assert "Overhead press slot paused" in change.words(FULL3, after, d, change.Choices())


def test_a_swap_picks_a_listed_allowed_choice_or_refuses():
    after = _after(swap_exercise="bench", swap_to="dumbbell bench")
    assert after.swaps == {"push": "dumbbell_bench_press"}
    nxt = _after(swap_exercise="bench")
    assert nxt.swaps == {"push": "dumbbell_bench_press"}
    with pytest.raises(change.ChangeRefused, match="can be swapped for"):
        _after(swap_exercise="bench", swap_to="leg curl")
    with pytest.raises(change.ChangeRefused, match="set to avoid"):
        _after(without_equipment=["machine"], swap_exercise="bench", swap_to="machine chest press")
    with pytest.raises(change.ChangeRefused, match="not one exercise in the programme"):
        _after(swap_exercise="zercher squat")


def test_a_split_change_carries_his_picks_where_the_new_slot_offers_them():
    before = change.State(
        rules.DEFAULT_TEMPLATE, ("mon", "wed", "fri"), swaps={"push": "dumbbell_bench_press"}
    )
    after = _after(before, split="upper_lower")
    assert after.swaps.get("u1_push") == "dumbbell_bench_press"


def test_the_words_are_short_and_name_what_carries():
    after = _after(sessions_per_week=4)
    d = change.diff(FULL3, after, trained={"bench_press", "barbell_row"})
    text = change.words(FULL3, after, d, change.Choices(sessions_per_week=4))
    assert text.startswith("Proposed: 4 sessions a week, upper / lower.")
    assert "keep their progress" in text and "calibration" in text
    assert "mon" not in text.lower().split()


# =============================================================================
# Proposals over REST
# =============================================================================


def _start_finish_all_top(client, location=None):
    body = {"location": location} if location else {}
    s = client.post(f"{WORKOUT}/start/", body, format="json").data["session"]
    sets = [
        {
            "exercise_key": x["exercise_key"],
            "set_number": x["set_number"],
            "done": True,
            "reps": x["reps_high"],
            "weight_lbs": 100.0 if x["kind"] == "weighted" else None,
        }
        for x in s["sets"]
    ]
    assert client.patch(f"{WORKOUT}/sets/", {"session_id": s["id"], "sets": sets},
                        format="json").status_code == 200
    assert client.post(f"{WORKOUT}/finish/", {"session_id": s["id"]}, format="json").data[
        "finished"
    ]
    return s


def test_propose_then_accept_changes_the_programme_and_carries_progress(auth_client):
    _start_finish_all_top(auth_client)  # Day A: bench 3 x 8 @ 100, all at the top
    r = auth_client.post(f"{WORKOUT}/proposals/", {"sessions_per_week": 4}, format="json")
    assert r.status_code == 201, r.data
    proposal = r.data["proposal"]
    assert proposal["after"]["template"] == rules.UPPER_LOWER
    assert "Nothing changes until you accept" in r.data["words"]
    assert auth_client.get(f"{WORKOUT}/").data["proposal"]["id"] == proposal["id"]
    assert TrainingProgramme.objects.get().template == rules.DEFAULT_TEMPLATE

    ok = auth_client.post(f"{WORKOUT}/proposals/{proposal['id']}/accept/")
    assert ok.status_code == 200, ok.data
    assert "4 sessions a week, upper / lower" in ok.data["words"]
    assert "Next workout is Upper 1" in ok.data["words"]
    body = auth_client.get(f"{WORKOUT}/").data
    assert body["proposal"] is None
    assert body["programme"]["sessions_per_week"] == 4
    nxt = body["next"]
    assert nxt["day_label"] == "Upper 1"
    by_key = {e["exercise_key"]: e for e in nxt["exercises"]}
    assert (by_key["bench_press"]["weight_lbs"], by_key["bench_press"]["rule"]) == (105, "add")
    assert by_key["dumbbell_curl"]["calibrating"]
    assert ProgrammeProposal.objects.get().status == "accepted"


def test_accept_refuses_when_the_programme_moved_and_a_new_proposal_expires_the_old(auth_client):
    first = auth_client.post(f"{WORKOUT}/proposals/", {"cardio": True}, format="json").data
    second = auth_client.post(f"{WORKOUT}/proposals/", {"sessions_per_week": 2},
                              format="json").data
    assert ProgrammeProposal.objects.get(pk=first["proposal"]["id"]).status == "expired"
    stale = auth_client.post(f"{WORKOUT}/proposals/{first['proposal']['id']}/accept/")
    assert stale.status_code == 409 and "already expired" in stale.data["detail"]
    # The programme moves under the pending one (an immediate swap), so accept refuses.
    swapped = auth_client.post(f"{WORKOUT}/swap/", {"exercise": "bench"}, format="json")
    assert swapped.status_code == 200
    moved = auth_client.post(f"{WORKOUT}/proposals/{second['proposal']['id']}/accept/")
    assert moved.status_code == 409 and moved.data["detail"].startswith("Nothing was accepted")
    assert TrainingProgramme.objects.get().day_list() == list(rules.DEFAULT_TRAINING_DAYS)


def test_a_change_to_nothing_is_refused_and_dismiss_keeps_the_programme(auth_client):
    same = auth_client.post(f"{WORKOUT}/proposals/", {"sessions_per_week": 3}, format="json")
    assert same.status_code == 400 and "already is that" in same.data["detail"]
    assert ProgrammeProposal.objects.count() == 0
    p = auth_client.post(f"{WORKOUT}/proposals/", {"avoid": ["knee"]}, format="json").data
    r = auth_client.post(f"{WORKOUT}/proposals/{p['proposal']['id']}/dismiss/")
    assert r.status_code == 200 and "stays as it was" in r.data["words"]
    assert TrainingProgramme.objects.get().avoid == []
    gone = auth_client.post(f"{WORKOUT}/proposals/{uuid.uuid4()}/accept/")
    assert gone.status_code == 404


def test_alternatives_are_a_read_filtered_by_the_programme(auth_client):
    r = auth_client.get(f"{WORKOUT}/alternatives/bench/")
    assert r.status_code == 200, r.data
    names = [o["name"] for o in r.data["options"]]
    assert "Dumbbell bench press" in names and "Bench press" not in names
    assert "These are the only options" in r.data["words"]
    assert auth_client.get(f"{WORKOUT}/alternatives/zercher/").status_code == 404
    assert ProgrammeProposal.objects.count() == 0


def test_a_home_session_uses_home_variants_and_a_running_one_moves_home(auth_client):
    s = auth_client.post(f"{WORKOUT}/start/", {}, format="json").data["session"]
    assert s["location"] == "gym"
    assert any(x["exercise_key"] == "bench_press" for x in s["sets"])
    moved = auth_client.post(f"{WORKOUT}/start/", {"location": "home"}, format="json").data
    keys = {x["exercise_key"] for x in moved["session"]["sets"]}
    assert moved["session"]["location"] == "home" and "bench_press" not in keys
    assert keys <= {k for k, e in rules.CATALOG.items() if set(e.equipment) & rules.HOME_EQUIPMENT}
    bad = auth_client.post(f"{WORKOUT}/start/", {"location": "beach"}, format="json")
    assert bad.status_code == 400


def test_the_next_session_is_due_while_the_week_is_short_whatever_the_weekday(auth_client):
    body = auth_client.get(f"{WORKOUT}/").data
    assert body["training_day"] is True and body["words"].startswith("Next session is Day A")


# =============================================================================
# What to eat, pure
# =============================================================================


def _c(i, kcal, protein, source=suggest.SOURCE_SAVED, name=None):
    basis = suggest.BASIS_SAVED if source == suggest.SOURCE_SAVED else suggest.BASIS_AS_BOUGHT
    return suggest.Candidate(f"{source}:{i}", source, name or f"meal {i}", kcal, protein,
                             basis=basis)


T2000 = suggest.Targets(2000, "meal target", 160.0)


def _s(clock=time(12, 30), targets=T2000, eaten=None, saved=(), groceries=(), week=None):
    return suggest.suggest(
        clock=clock,
        targets=targets,
        eaten=eaten or suggest.Eaten(1000, 80.0, meals=2),
        saved=list(saved),
        groceries=list(groceries),
        week=week,
    )


def test_only_what_fits_ranked_protein_per_kcal_inside_the_meal_share():
    b = _s(saved=[_c(1, 450, 30), _c(2, 300, 40), _c(3, 1200, 90), _c(4, 600, 70)])
    assert b["kcal_left"] == 1000 and b["protein_left_g"] == 80
    assert b["this_meal_kcal"] == 500  # midday: half of what is left
    assert [o["id"] for o in b["options"]] == ["saved_meal:2", "saved_meal:1", "saved_meal:4"]
    assert b["options"][2]["fits_this_meal"] is False
    assert all(o["estimate"] for o in b["options"])
    assert "Every figure is an estimate" in b["words"]


def test_with_protein_met_the_closest_fit_to_the_share_comes_first():
    b = _s(eaten=suggest.Eaten(1000, 170.0), saved=[_c(1, 200, 40), _c(2, 480, 10)])
    assert b["protein_left_g"] == 0 and b["options"][0]["id"] == "saved_meal:2"
    assert "protein is met" in b["words"]


def test_over_is_one_neutral_line_and_the_lightest_options():
    week = suggest.Week(days=6, mean_kcal=1950.0)
    b = _s(eaten=suggest.Eaten(2600, 150.0), saved=[_c(i, 100 * i, 10) for i in range(1, 6)],
           week=week)
    assert b["state"] == "over" and len(b["options"]) == 3
    assert [o["kcal"] for o in b["options"]] == [100, 200, 300]
    assert "Over by 600 kcal today" in b["words"]
    assert "average 1950 kcal a day, on target" in b["words"]
    for word in ("should", "must", "guilt", "bad", "skip"):
        assert word not in b["words"].lower()


def test_late_with_little_left_says_so_and_offers_small_options():
    b = _s(clock=time(22, 10), eaten=suggest.Eaten(1850, 140.0, meals=3),
           saved=[_c(1, 120, 24), _c(2, 500, 40)])
    assert b["state"] == "late_little_left"
    assert [o["id"] for o in b["options"]] == ["saved_meal:1"]
    assert "late and little is left" in b["words"]


def test_under_800_in_the_evening_suggests_a_proper_meal_first():
    b = _s(clock=time(19, 0), eaten=suggest.Eaten(500, 30.0, meals=1),
           saved=[_c(1, 150, 30), _c(2, 700, 45)])
    assert b["state"] == "under_floor" and b["options"][0]["id"] == "saved_meal:2"
    assert "under 800" in b["words"] and "proper meal" in b["words"]


def test_no_target_ranks_by_protein_and_says_so():
    b = _s(targets=suggest.Targets(None, None, None), saved=[_c(1, 400, 20), _c(2, 200, 30)])
    assert b["state"] == "no_target" and b["kcal_left"] is None
    assert b["options"][0]["id"] == "saved_meal:2"
    assert "no calorie target" in b["words"]


def test_kcal_and_protein_are_the_targets_and_carbs_and_fat_show_only_when_known():
    b = _s(eaten=suggest.Eaten(1000, 80.0, carbs_g=110.0, fat_g=None))
    assert "carbs_g" not in b["target"] and "fat_g" not in b["target"]
    assert b["eaten"]["carbs_g"] == 110 and b["eaten"]["fat_g"] is None
    assert "110 g carbs" in b["words"] and "g fat" not in b["words"]


def test_groceries_are_whole_items_as_bought_and_pairs_must_fit():
    tuna = _c("t", 200, 40, suggest.SOURCE_GROCERY, "Tuna")
    cakes = _c("r", 150, 3, suggest.SOURCE_GROCERY, "Rice cakes")
    oats = _c("o", 3800, 130, suggest.SOURCE_GROCERY, "Oats 1 kg")
    b = _s(groceries=[tuna, cakes, oats])
    ids = [o["id"] for o in b["options"]]
    assert "grocery:t" in ids and "combo:r+t" in ids and "grocery:o" not in ids
    combo = next(o for o in b["options"] if o["id"] == "combo:r+t")
    assert (combo["kcal"], combo["protein_g"]) == (350, 43)
    assert b["too_big_as_bought"] == ["Oats 1 kg"]
    assert all(o["basis"] == "as bought" for o in b["options"])


# =============================================================================
# What to eat over REST, and "log that"
# =============================================================================


def _groceries(client, days_ago=2, sha="sha-coach2"):
    payload = a_receipt(
        content_sha256=sha,
        purchase_date=(today() - timedelta(days=days_ago)).isoformat(),
        total_cents=1242,
        subtotal_cents=1150,
        tax_cents=92,
        items=[
            {"name": "GREEK YOGURT CUP", "quantity": 1, "total_price_cents": 450,
             "estimated_calories_kcal": 150, "estimated_protein_g": 15},
            {"name": "TUNA CAN", "quantity": 1, "total_price_cents": 700,
             "estimated_calories_kcal": 120, "estimated_protein_g": 26},
        ],
    )
    r = client.post(RECEIPT_URL, payload, format="json")
    assert r.status_code == 201, r.data


def test_the_read_lists_saved_meals_and_recent_groceries_and_writes_nothing(
    auth_client, household_a
):
    target(household_a, 2000, protein=160.0)
    eat(household_a, 0, 600, protein=40.0)
    SavedMeal.objects.create(household=household_a, name="synthetic bowl", calories_kcal=450,
                             protein_g=45)
    _groceries(auth_client)
    _groceries(auth_client, days_ago=20, sha="sha-old")
    before = MealLog.objects.count()
    r = auth_client.get(f"{MEALS}/")
    assert r.status_code == 200, r.data
    names = {o["name"] for o in r.data["options"]}
    assert {"synthetic bowl", "GREEK YOGURT CUP", "TUNA CAN"} <= names
    assert r.data["kcal_left"] == 1400 and r.data["protein_left_g"] == 120
    assert MealLog.objects.count() == before
    assert ProgrammeProposal.objects.count() == 0


def test_a_reversed_grocery_is_not_on_hand(auth_client, household_a):
    target(household_a, 2000)
    _groceries(auth_client)
    tuna = ReceiptLineItem.objects.get(name="TUNA CAN")
    ReceiptLineItem.objects.create(
        id=uuid.uuid4(), receipt=tuna.receipt, name="TUNA CAN", quantity=1,
        total_price_cents=-700, reversal_of=tuna, provenance=tuna.provenance,
        created_at=tuna.created_at, household=household_a,
    )
    names = {o["name"] for o in auth_client.get(f"{MEALS}/").data["options"]}
    assert "TUNA CAN" not in names and "GREEK YOGURT CUP" in names


def test_log_that_writes_the_engines_figures_by_id_and_nothing_else(auth_client, household_a):
    target(household_a, 2000)
    _groceries(auth_client)
    meal = SavedMeal.objects.create(household=household_a, name="synthetic bowl",
                                    calories_kcal=450, protein_g=45)
    r = auth_client.post(f"{MEALS}/log/", {"option_id": f"saved_meal:{meal.id}"}, format="json")
    assert r.status_code == 201, r.data
    assert (r.data["meal_log"]["calories_kcal"], r.data["meal_log"]["protein_g"]) == (450, 45)
    tuna = ReceiptLineItem.objects.get(name="TUNA CAN")
    yog = ReceiptLineItem.objects.get(name="GREEK YOGURT CUP")
    combo = f"combo:{min(tuna.id, yog.id, key=str)}+{max(tuna.id, yog.id, key=str)}"
    r = auth_client.post(f"{MEALS}/log/", {"option_id": combo}, format="json")
    assert r.status_code == 201 and r.data["meal_log"]["calories_kcal"] == 270
    assert "as bought" in r.data["meal_log"]["description"]
    smuggled = auth_client.post(
        f"{MEALS}/log/", {"option_id": f"grocery:{tuna.id}", "calories_kcal": 1}, format="json"
    )
    assert smuggled.status_code == 400
    for bad in ("nope", f"grocery:{uuid.uuid4()}", f"saved_meal:{uuid.uuid4()}", "pizza:1"):
        refused = auth_client.post(f"{MEALS}/log/", {"option_id": bad}, format="json")
        assert refused.status_code in (400, 404), bad
        assert refused.data["detail"].startswith("Nothing was logged")
    assert MealLog.objects.count() == 2


# =============================================================================
# The engine tools
# =============================================================================


@pytest.fixture
def mcp(settings, household_user):
    from tests.test_engine_mcp import client_with

    settings.LEGION_MCP = True
    return client_with(household_user)


def _call(client, name, arguments=None):
    from tests.test_engine_mcp import call

    return call(client, name, arguments)


def test_the_tools_propose_read_back_and_accept_only_through_the_engine(mcp):
    is_error, text, _ = _call(mcp, "accept_programme_change")
    assert is_error and "no pending programme proposal" in text
    is_error, text, _ = _call(mcp, "propose_programme_change", {"avoid": ["knee"], "cardio": True})
    assert not is_error and "Read this proposal back" in text and "has not changed" in text
    assert TrainingProgramme.objects.get().cardio is False
    is_error, text, _ = _call(mcp, "accept_programme_change")
    assert not is_error and "committed" in text and "walking finisher" in text
    assert TrainingProgramme.objects.get().avoid == ["knee"]
    is_error, text, _ = _call(mcp, "dismiss_programme_change")
    assert is_error and text.startswith("Nothing was dismissed")
    is_error, text, _ = _call(mcp, "get_exercise_alternatives", {"exercise": "bench"})
    assert not is_error and "Recommend only from these" in text
    is_error, text, _ = _call(mcp, "start_workout", {"location": "home"})
    assert not is_error and "at home" in text


def test_the_meal_tools_list_ids_and_log_by_id(mcp, household_a):
    target(household_a, 2000)
    meal = SavedMeal.objects.create(household=household_a, name="synthetic bowl",
                                    calories_kcal=450, protein_g=45)
    is_error, text, _ = _call(mcp, "suggest_meal")
    assert not is_error and f"saved_meal:{meal.id}" in text and "Suggest only from" in text
    is_error, text, _ = _call(mcp, "log_meal_option", {"option_id": f"saved_meal:{meal.id}"})
    assert not is_error and "committed one meal entry" in text
    is_error, text, _ = _call(mcp, "log_meal_option", {"option_id": "saved_meal:nope"})
    assert is_error and text.startswith("Nothing was logged")
