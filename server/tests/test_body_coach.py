"""The fat-loss loop (fat-loss tickets 03 to 08): the profile, the energy estimate, the weight
trend and waist log, the two-weekly proposal, saved meals, the weekly line and the engine tools.

**Synthetic people only.** Nobody's real numbers appear here: a 30-year-old 178 cm man at 95 kg,
a 41-year-old 165 cm woman at 70 kg, and round-number trend fixtures.
"""

from __future__ import annotations

import re
import uuid
from datetime import UTC, date, datetime, time, timedelta

import pytest
from django.db import IntegrityError, transaction
from django.db.models.functions import Now

from body_coach import adaptation, energy, trend, weekly
from body_coach.models import BodyProfile, EnergyProposal, SavedMeal
from legacy.enums import Provenance
from legacy.models.body import BodyweightLog, Goal
from legacy.models.pantry import MealLog, MealTarget
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

BASE = "/api/body/coach"
THIS_YEAR = datetime.now(UTC).year


def today() -> date:
    return datetime.now(UTC).date()


# =============================================================================
# Row builders (UTC household: no timezone set, so local days are UTC days)
# =============================================================================


def _at(day: date, hour: int = 12) -> datetime:
    return datetime.combine(day, time(hour), tzinfo=UTC)


def weigh(household, days_ago: int, value: float, unit: str = "lbs", hour: int = 7, deleted=False):
    return BodyweightLog.objects.create(
        id=uuid.uuid4(),
        household=household,
        weight_value=value,
        weight_unit=unit,
        logged_at=_at(today() - timedelta(days=days_ago), hour),
        trust_tier="REPORTED",
        provenance=Provenance.USER,
        created_at=Now(),
        updated_at=Now(),
        deleted_at=Now() if deleted else None,
        origin_guid=str(uuid.uuid4()),
    )


def eat(household, days_ago: int, kcal: int | None, protein=30.0, deleted=False):
    return MealLog.objects.create(
        id=uuid.uuid4(),
        household=household,
        description="synthetic meal",
        calories_kcal=kcal,
        protein_g=protein,
        carbs_g=None,
        fat_g=None,
        logged_at=_at(today() - timedelta(days=days_ago)),
        source_image_path=None,
        trust_tier="REPORTED",
        provenance=Provenance.USER,
        created_at=Now(),
        updated_at=Now(),
        deleted_at=Now() if deleted else None,
        origin_guid=str(uuid.uuid4()),
    )


def target(household, kcal: int, days_ago: int = 40, protein=180.0, carbs=200.0, fat=70.0):
    return MealTarget.objects.create(
        id=uuid.uuid4(),
        household=household,
        calories_kcal=kcal,
        protein_g=protein,
        carbs_g=carbs,
        fat_g=fat,
        effective_from_date=today() - timedelta(days=days_ago),
        provenance=Provenance.USER,
        created_at=Now(),
        updated_at=Now(),
        deleted_at=None,
        origin_guid=str(uuid.uuid4()),
    )


def goal(household, value: float, unit: str = "lbs", statement="reach a lighter weight"):
    return Goal.objects.create(
        id=uuid.uuid4(),
        household=household,
        lineage_id=1,
        aspect="bio",
        statement=statement,
        target_value=value,
        unit=unit,
        metric_key="bodyweight_lbs",
        status="active",
        created_at_client=datetime.now(UTC),
        provenance=Provenance.USER,
        created_at=Now(),
        updated_at=Now(),
        deleted_at=None,
        origin_guid=str(uuid.uuid4()),
    )


def synthetic_man(client):
    """30 years old, 178 cm, moderately active."""
    response = client.put(
        f"{BASE}/profile/",
        {"height_cm": 178, "sex": "male", "birth_year": THIS_YEAR - 30,
         "activity_level": "moderate"},
        format="json",
    )
    assert response.status_code == 200, response.data
    return response.data


def series(household, start_days_ago: int, first_lbs: float, per_day: float, every=1, kcal=None):
    """One weigh-in every `every` days from `start_days_ago` to yesterday, falling `per_day`
    lbs a day; a meal of `kcal` each day when given."""
    for i, ago in enumerate(range(start_days_ago, 0, -1)):
        if i % every == 0:
            weigh(household, ago, first_lbs + per_day * i)
        if kcal is not None:
            eat(household, ago, kcal)


# =============================================================================
# The math, on synthetic people
# =============================================================================


def test_mifflin_st_jeor_for_a_man_and_a_woman():
    man = energy.bmr_kcal(weight_kg=95, height_cm=178, age=30, sex="male")
    woman = energy.bmr_kcal(weight_kg=70, height_cm=165, age=41, sex="female")
    assert man == pytest.approx(10 * 95 + 6.25 * 178 - 5 * 30 + 5)  # 1917.5
    assert woman == pytest.approx(10 * 70 + 6.25 * 165 - 5 * 41 - 161)  # 1365.25


@pytest.mark.parametrize(
    ("level", "factor"),
    [("sedentary", 1.2), ("light", 1.375), ("moderate", 1.55), ("very", 1.725)],
)
def test_each_activity_level_multiplies_the_bmr(level, factor):
    lbs = 95 * trend.LBS_PER_KG
    out = energy.estimate(
        profile=energy.ProfileInputs(
            height_mm=1780, sex="male", birth_year=THIS_YEAR - 30, activity_level=level
        ),
        this_year=THIS_YEAR,
        weight_lbs=lbs,
        weight_source="latest",
        goal_lbs=None,
        observed=None,
    )
    assert out["bmr_kcal"] == 1918
    assert out["activity_factor"] == factor
    assert out["formula_maintenance_kcal"] == round(1917.5 * factor)
    assert out["basis"] == "formula"
    assert out["estimate"] is True
    assert "estimate" in out["words"]
    assert "Mifflin-St Jeor" in out["basis_words"]


def test_the_suggested_target_and_rounding_once():
    lbs = 95 * trend.LBS_PER_KG
    out = energy.estimate(
        profile=energy.ProfileInputs(
            height_mm=1780, sex="male", birth_year=THIS_YEAR - 30, activity_level="moderate"
        ),
        this_year=THIS_YEAR,
        weight_lbs=lbs,
        weight_source="latest",
        goal_lbs=None,
        observed=None,
    )
    # 1917.5 x 1.55 = 2972.125; 0.75% x 95 kg x 7700 / 7 / 100 = 783.75 -> 2188.375.
    assert out["maintenance_kcal"] == 2972
    assert out["suggested_target_kcal"] == 2188
    assert out["target_rate_pct"] == 0.75
    assert out["protein_g"] == 190
    assert out["protein_basis"] == "current weight"


def test_protein_is_against_the_lower_of_goal_and_current_weight():
    assert energy.protein_g(2.0, 95, 85) == (170, "goal weight")
    assert energy.protein_g(2.0, 70, 75) == (140, "current weight")
    assert energy.protein_g(1.6, 70, None) == (pytest.approx(112), "current weight")


def test_no_target_at_or_below_800_kcal():
    # A 45 kg, 150 cm, 70-year-old woman, sedentary, at 1.0% a week: 1051.8 - 495 = 556.8.
    out = energy.estimate(
        profile=energy.ProfileInputs(
            height_mm=1500, sex="female", birth_year=THIS_YEAR - 70,
            activity_level="sedentary", target_rate_bp=100,
        ),
        this_year=THIS_YEAR,
        weight_lbs=45 * trend.LBS_PER_KG,
        weight_source="latest",
        goal_lbs=None,
        observed=None,
    )
    assert out["maintenance_kcal"] == 1052
    assert out["suggested_target_kcal"] is None
    assert "800 kcal" in out["words"]
    assert energy.suggested_target_kcal(1600, 1.0, 100) is None  # 1600 - 1100 = 500


def test_missing_inputs_produce_no_figure_and_are_named():
    out = energy.estimate(
        profile=energy.ProfileInputs(height_mm=1650, sex="female"),
        this_year=THIS_YEAR,
        weight_lbs=None,
        weight_source=None,
        goal_lbs=None,
        observed=None,
    )
    assert out["missing"] == ["birth year", "activity level", "a weigh-in"]
    for key in ("bmr_kcal", "maintenance_kcal", "suggested_target_kcal", "protein_g", "basis"):
        assert out[key] is None, key
    assert "birth year" in out["words"]


def test_kg_readings_are_converted_never_averaged_raw():
    assert trend.to_lbs(100, "kg") == pytest.approx(220.462, abs=1e-3)
    assert trend.to_lbs(200, "lbs") == 200
    assert trend.to_lbs(200, None) == 200
    assert trend.to_lbs(200, "stone") is None
    day = date(2026, 1, 1)
    means = trend.daily_means([(day, trend.to_lbs(100, "kg")), (day, 220.0)])
    assert means[day] == pytest.approx((220.462 + 220.0) / 2, abs=1e-3)


def test_the_ewma_seeds_on_the_first_reading_and_carries_gaps():
    d0 = date(2026, 1, 1)
    points = trend.ewma({d0: 200.0, d0 + timedelta(days=3): 190.0}, d0 + timedelta(days=4))
    assert [p.day for p in points] == [d0 + timedelta(days=i) for i in range(5)]
    assert [p.weight_lbs for p in points] == [200.0, None, None, 190.0, None]
    assert [p.trend_lbs for p in points] == pytest.approx([200, 200, 200, 199, 199])


def test_the_rate_is_over_14_days_and_null_before_then():
    d0 = date(2026, 1, 1)
    daily = {d0 + timedelta(days=i): 200.0 - 0.5 * i for i in range(30)}
    points = trend.ewma(daily, d0 + timedelta(days=29))
    assert trend.rate(points, d0 + timedelta(days=13)) is None
    r = trend.rate(points, d0 + timedelta(days=29))
    start = trend.trend_on(points, d0 + timedelta(days=15))
    end = trend.trend_on(points, d0 + timedelta(days=29))
    assert r.lbs_per_week == pytest.approx((end - start) / 2)
    assert r.pct_per_week == pytest.approx((end - start) / start * 100 / 2)
    assert r.pct_per_week < 0  # negative is losing


def test_early_water_is_flagged_for_three_weeks():
    d0 = date(2026, 1, 1)
    assert trend.early_water(d0, d0 + timedelta(days=20)) is True
    assert trend.early_water(d0, d0 + timedelta(days=21)) is False
    assert trend.early_water(None, d0) is False


def test_the_milestone_gives_no_eta_while_flagged_or_moving_away():
    common = {"goal_id": "g", "statement": "s", "target_lbs": 180.0, "start_lbs": 200.0,
              "current_lbs": 195.0}
    m = trend.milestone(**common, rate_lbs_per_week=-1.0, water=False)
    assert m.remaining_lbs == 15 and m.progress_pct == 25 and m.weeks_to_go == 15
    assert trend.milestone(**common, rate_lbs_per_week=-1.0, water=True).weeks_to_go is None
    assert trend.milestone(**common, rate_lbs_per_week=0.5, water=False).weeks_to_go is None


def _evidence(**overrides):
    base = dict(
        window=adaptation.window_for(date(2026, 3, 1)),
        intake_days=14,
        weigh_in_days=14,
        mean_intake_kcal=2500.0,
        first_weigh_in=date(2026, 1, 1),
        trend_start_lbs=200.0,
        trend_end_lbs=200.0,
    )
    base.update(overrides)
    return adaptation.Evidence(**base)


def test_the_window_is_the_14_days_ending_yesterday():
    w = adaptation.window_for(date(2026, 3, 15))
    assert (w.start, w.end) == (date(2026, 3, 1), date(2026, 3, 14))
    assert len(w.days()) == 14


@pytest.mark.parametrize(
    "overrides",
    [
        {"intake_days": 10},
        {"weigh_in_days": 7},
        {"first_weigh_in": date(2026, 2, 12)},  # window starts 2026-02-15: under 7 days after
    ],
)
def test_under_the_gate_there_is_no_proposal(overrides):
    e = _evidence(**overrides)
    assert not adaptation.evidence_met(e)
    assert adaptation.propose(e, target_rate_bp=75, current_target_kcal=2200,
                              made_recently=False) is None


def test_no_proposal_without_a_target_or_within_14_days_of_the_last():
    e = _evidence()
    assert adaptation.propose(e, target_rate_bp=75, current_target_kcal=None,
                              made_recently=False) is None
    assert adaptation.propose(e, target_rate_bp=75, current_target_kcal=2200,
                              made_recently=True) is None


def test_in_band_there_is_no_proposal():
    # 200 -> 197.2 lbs over 14 days is 0.7% a week.
    e = _evidence(trend_end_lbs=197.2)
    assert adaptation.observed_rate_pct(e) == pytest.approx(-0.7)
    assert adaptation.propose(e, target_rate_bp=75, current_target_kcal=2200,
                              made_recently=False) is None


def test_off_band_both_ways_is_clamped_to_150():
    flat = _evidence()
    down = adaptation.propose(flat, target_rate_bp=75, current_target_kcal=2200,
                              made_recently=False)
    assert down.proposed_target_kcal == 2050
    assert down.observed_maintenance_kcal == 2500
    assert down.observed_rate_bp == 0
    fast = _evidence(mean_intake_kcal=1800.0, trend_end_lbs=192.0)  # 2% a week
    up = adaptation.propose(fast, target_rate_bp=75, current_target_kcal=1800,
                            made_recently=False)
    assert up.proposed_target_kcal == 1950
    # Maintenance = 1800 + 8 lbs in kg x 7700 / 14.
    assert up.observed_maintenance_kcal == round(1800 + 8 * trend.KG_PER_LB * 7700 / 14)


def test_a_proposal_at_or_below_800_is_never_made():
    e = _evidence(mean_intake_kcal=1000.0)
    assert adaptation.propose(e, target_rate_bp=100, current_target_kcal=900,
                              made_recently=False) is None


def test_the_waist_change_is_against_four_to_six_weeks_back():
    d = date(2026, 3, 1)
    assert trend.waist_change_mm([(d, 900), (d + timedelta(days=28), 880)]) == -20
    assert trend.waist_change_mm([(d, 900), (d + timedelta(days=14), 880)]) is None
    assert trend.waist_change_mm([]) is None


# =============================================================================
# The weekly line: never about absence, logging, days, streaks or engagement
# =============================================================================

FORBIDDEN = re.compile(
    r"miss|skip|streak|log|day|engag|absen|haven't|have not|forgot|again|since you|\bback\b",
    re.IGNORECASE,
)


@pytest.mark.parametrize(
    "inputs",
    [
        dict(trend_lbs=210.0, rate_pct_per_week=-0.7, rate_lbs_per_week=-1.5,
             goal_remaining_lbs=20.0, goal_target_lbs=190.0, waist_change_mm=-20),
        dict(trend_lbs=210.0, rate_pct_per_week=0.3, rate_lbs_per_week=0.6,
             goal_remaining_lbs=None, goal_target_lbs=None, waist_change_mm=None),
        dict(trend_lbs=190.0, rate_pct_per_week=-1.4, rate_lbs_per_week=-2.6,
             goal_remaining_lbs=0.0, goal_target_lbs=190.0, waist_change_mm=0),
    ],
)
def test_weekly_facts_state_the_trend_and_never_engagement(inputs):
    out = weekly.weekly_line(
        weekly.WeeklyInputs(weekly_line_on=True, weigh_in_days_last_14=10, **inputs)
    )
    assert out["say"] is True
    assert "lbs" in out["facts"] and "band" in out["facts"]
    assert not FORBIDDEN.search(out["facts"]), out["facts"]


def test_weekly_line_is_quiet_when_off_thin_or_rateless():
    common = dict(trend_lbs=200.0, rate_pct_per_week=-0.7, rate_lbs_per_week=-1.4,
                  goal_remaining_lbs=None, goal_target_lbs=None, waist_change_mm=None)
    off = weekly.weekly_line(weekly.WeeklyInputs(False, 14, **common))
    thin = weekly.weekly_line(weekly.WeeklyInputs(True, 7, **common))
    rateless = weekly.weekly_line(
        weekly.WeeklyInputs(True, 10, **{**common, "rate_pct_per_week": None})
    )
    for out in (off, thin, rateless):
        assert out["say"] is False and out["facts"] == "" and out["reason"]


# =============================================================================
# Profile: partial PUT, words on refusal, private to its member
# =============================================================================


def test_profile_starts_absent_and_names_what_is_missing(auth_client):
    body = auth_client.get(f"{BASE}/profile/").data
    assert body["exists"] is False
    assert body["missing"] == ["height", "sex", "birth year", "activity level", "a weigh-in"]
    assert body["target_rate_pct"] == 0.75 and body["protein_g_per_kg"] == 2.0
    assert body["weekly_line_on"] is True
    assert body["goal_weight_lbs"] is None


def test_profile_put_is_partial_and_takes_inches(auth_client, household_a):
    first = auth_client.put(f"{BASE}/profile/", {"height_in": 70}, format="json").data
    assert first["exists"] is True and first["height_cm"] == 177.8
    second = auth_client.put(
        f"{BASE}/profile/", {"sex": "female", "target_rate_pct": 0.5}, format="json"
    ).data
    assert second["height_cm"] == 177.8 and second["sex"] == "female"
    assert second["target_rate_pct"] == 0.5
    weigh(household_a, 1, 154)
    third = auth_client.get(f"{BASE}/profile/").data
    assert third["missing"] == ["birth year", "activity level"]
    assert BodyProfile.objects.get().target_rate_bp == 50


@pytest.mark.parametrize(
    ("body", "words"),
    [
        ({"sex": "other"}, "male or female"),
        ({"activity_level": "athlete"}, "sedentary (1.2), light (1.375)"),
        ({"target_rate_pct": 1.5}, "between 0.25 and 1.00"),
        ({"protein_g_per_kg": 3}, "between 1.6 and 2.2"),
        ({"height_cm": 17.8}, "Height must be between"),
        ({"birth_year": THIS_YEAR - 5}, "for adults"),
        ({"weekly_line_on": "no"}, "true or false"),
        ({"weight": 90}, "Fields it takes"),
        ({"height_cm": 170, "height_in": 67}, "not both"),
    ],
)
def test_profile_refusals_name_the_allowed_values(auth_client, body, words):
    response = auth_client.put(f"{BASE}/profile/", body, format="json")
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was saved.")
    assert words in response.data["detail"]
    assert not BodyProfile.objects.exists()


def test_the_database_holds_the_ranges(household_user, household_a):
    for bad in ({"target_rate_bp": 101}, {"protein_tenths_g_per_kg": 15}, {"sex": "x"}):
        with pytest.raises(IntegrityError), transaction.atomic():
            BodyProfile.objects.create(household=household_a, owner_user=household_user, **bad)


def test_a_profile_is_private_to_its_member(auth_client, household_a):
    mia = _member(household_a, "mia@example.com")
    mia_client = _client_for(mia)
    synthetic_man(auth_client)
    theirs = mia_client.get(f"{BASE}/profile/").data
    assert theirs["exists"] is False and theirs["height_cm"] is None
    mia_client.put(f"{BASE}/profile/", {"height_cm": 165, "sex": "female"}, format="json")
    assert auth_client.get(f"{BASE}/profile/").data["sex"] == "male"
    assert BodyProfile.objects.count() == 2
    # Energy reads the caller's own profile only.
    assert mia_client.get(f"{BASE}/energy/").data["activity_level"] is None
    assert auth_client.get(f"{BASE}/energy/").data["activity_level"] == "moderate"


def test_the_database_refuses_a_profile_owned_by_a_non_member(household_a, user_b):
    with pytest.raises(Exception, match="not a member"), transaction.atomic():
        BodyProfile.objects.create(household=household_a, owner_user=user_b)


# =============================================================================
# Energy over REST
# =============================================================================


def test_energy_with_no_profile_names_the_inputs_and_guesses_nothing(auth_client):
    body = auth_client.get(f"{BASE}/energy/").data
    assert body["estimate"] is True and body["basis"] is None
    assert body["missing"] == ["height", "sex", "birth year", "activity level", "a weigh-in"]
    assert body["maintenance_kcal"] is None and body["protein_g"] is None
    assert body["current_target"] is None and body["proposal"] is None


def test_energy_on_the_formula_from_a_kg_reading(auth_client, household_a):
    synthetic_man(auth_client)
    weigh(household_a, 1, 95, unit="kg")
    body = auth_client.get(f"{BASE}/energy/").data
    assert body["missing"] == []
    assert body["weight_source"] == "latest"
    assert body["weight_lbs"] == round(95 * trend.LBS_PER_KG, 1)
    assert body["bmr_kcal"] == 1918
    assert body["formula_maintenance_kcal"] == 2972 == body["maintenance_kcal"]
    assert body["basis"] == "formula" and body["basis_days"] is None
    assert body["suggested_target_kcal"] == 2188
    assert body["protein_g"] == 190 and body["protein_basis"] == "current weight"
    assert "estimate" in body["words"]


def test_protein_uses_a_kg_goal_when_lower(auth_client, household_a):
    synthetic_man(auth_client)
    weigh(household_a, 1, 95, unit="kg")
    goal(household_a, 85, unit="kg")
    body = auth_client.get(f"{BASE}/energy/").data
    assert body["protein_g"] == 170 and body["protein_basis"] == "goal weight"


def test_deleted_weigh_ins_never_count(auth_client, household_a):
    synthetic_man(auth_client)
    weigh(household_a, 1, 300, deleted=True)
    body = auth_client.get(f"{BASE}/energy/").data
    assert body["weight_lbs"] is None and "a weigh-in" in body["missing"]


# =============================================================================
# Trend and waist over REST
# =============================================================================


def test_trend_points_carry_gaps_and_the_rate(auth_client, household_a):
    series(household_a, 30, 200.0, -0.2, every=2)
    body = auth_client.get(f"{BASE}/trend/", {"days": 90}).data
    assert body["unit"] == "lbs" and body["rate_days"] == 14
    assert body["points"][0]["day"] == (today() - timedelta(days=30)).isoformat()
    assert body["points"][-1]["day"] == today().isoformat()
    assert body["points"][1]["weight_lbs"] is None  # a gap day: raw null, trend carried
    assert body["points"][1]["trend_lbs"] == body["points"][0]["trend_lbs"]
    assert body["rate_pct_per_week"] < 0 and body["rate_lbs_per_week"] < 0
    assert body["early_water"] is False
    assert body["band"] == {"low_pct": 0.5, "high_pct": 1.0}
    assert "estimate" in body["words"]


def test_trend_says_there_is_no_rate_before_14_days(auth_client, household_a):
    series(household_a, 10, 200.0, -0.3)
    body = auth_client.get(f"{BASE}/trend/").data
    assert body["rate_pct_per_week"] is None and body["in_band"] is None
    assert body["early_water"] is True
    assert "not 14 days of history" in body["words"]
    assert "water" in body["words"]


def test_the_milestone_from_a_kg_goal(auth_client, household_a):
    series(household_a, 40, 220.0, -0.3)
    goal(household_a, 90, unit="kg")  # 198.4 lbs
    stone = auth_client.get(f"{BASE}/trend/").data["milestone"]
    assert stone["target_lbs"] == round(90 * trend.LBS_PER_KG, 1)
    assert stone["start_lbs"] == 220.0
    assert 0 < stone["progress_pct"] < 100
    assert stone["remaining_lbs"] > 0
    assert stone["weeks_to_go"] is not None and stone["weeks_to_go"] > 0


def test_no_eta_while_the_first_weigh_in_is_under_three_weeks(auth_client, household_a):
    series(household_a, 18, 220.0, -0.5)
    goal(household_a, 200)
    body = auth_client.get(f"{BASE}/trend/").data
    assert body["early_water"] is True
    assert body["milestone"]["weeks_to_go"] is None
    assert "no date is given" in body["words"]


def test_trend_days_out_of_range_is_refused_in_words(auth_client):
    response = auth_client.get(f"{BASE}/trend/", {"days": 2})
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was read.")


def test_waist_round_trip_in_both_units(auth_client):
    first = auth_client.post(
        f"{BASE}/waist/",
        {"waist_cm": 90, "measured_on": (today() - timedelta(days=28)).isoformat()},
        format="json",
    )
    assert first.status_code == 201, first.data
    assert first.data["waist_cm"] == 90.0 and first.data["waist_in"] == 35.4
    second = auth_client.post(f"{BASE}/waist/", {"waist_in": 34.6}, format="json")
    assert second.status_code == 201 and second.data["measured_on"] == today().isoformat()
    listed = auth_client.get(f"{BASE}/waist/").data["results"]
    assert [row["id"] for row in listed] == [second.data["id"], first.data["id"]]
    trend_body = auth_client.get(f"{BASE}/trend/").data
    assert trend_body["waist_change_in_28d"] == round((879 - 900) / 25.4, 1)
    gone = auth_client.delete(f"{BASE}/waist/{first.data['id']}/")
    assert gone.status_code == 204
    assert len(auth_client.get(f"{BASE}/waist/").data["results"]) == 1


@pytest.mark.parametrize(
    "body",
    [{}, {"waist_in": 34, "waist_cm": 86}, {"waist_cm": -1}, {"waist_in": "wide"},
     {"waist_cm": 80, "measured_on": "2999-01-01"}],
)
def test_waist_refusals_are_in_words(auth_client, body):
    response = auth_client.post(f"{BASE}/waist/", body, format="json")
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was saved.")


# =============================================================================
# The proposal, end to end
# =============================================================================


def _off_band_flat(household):
    """35 days at a flat 200 lbs, eating 2500 a day, a 2200 target: off band, slow."""
    series(household, 35, 200.0, 0.0, kcal=2500)
    return target(household, 2200)


def test_an_off_band_trend_makes_one_proposal_with_its_evidence(auth_client, household_a):
    _off_band_flat(household_a)
    body = auth_client.get(f"{BASE}/energy/").data
    assert body["basis"] == "trend" and body["basis_days"] == 14
    assert body["observed_maintenance_kcal"] == 2500 == body["maintenance_kcal"]
    assert "own trend, 14 days" in body["basis_words"]
    proposal = body["proposal"]
    assert proposal["current_target_kcal"] == 2200
    assert proposal["proposed_target_kcal"] == 2050
    assert proposal["observed_rate_pct"] == 0.0
    assert proposal["intake_days"] == 14 and proposal["weigh_in_days"] == 14
    assert proposal["window_end"] == (today() - timedelta(days=1)).isoformat()
    assert "estimate" in proposal["words"]
    row = EnergyProposal.objects.get()
    assert row.mean_intake_kcal == 2500.0
    assert row.trend_start_lbs == pytest.approx(200.0)
    assert row.trend_end_lbs == pytest.approx(200.0)
    assert row.target_rate_bp == 75 and row.observed_maintenance_kcal == 2500
    # Idempotent: a second read makes nothing new.
    again = auth_client.get(f"{BASE}/energy/").data
    assert again["proposal"]["id"] == proposal["id"]
    assert EnergyProposal.objects.count() == 1


def test_a_fast_loss_proposes_a_raise_clamped_to_150(auth_client, household_a):
    series(household_a, 45, 230.0, -0.5, kcal=1800)
    target(household_a, 1800)
    proposal = auth_client.get(f"{BASE}/energy/").data["proposal"]
    assert proposal["proposed_target_kcal"] == 1950
    assert proposal["observed_rate_pct"] < -1.0


def test_in_band_and_under_gate_make_no_proposal(auth_client, household_a, household_b, user_b):
    series(household_a, 35, 200.0, -0.2, kcal=2300)  # about 0.7% a week
    target(household_a, 2300)
    assert auth_client.get(f"{BASE}/energy/").data["proposal"] is None
    # Household B: ten intake days only.
    client_b = _client_for(user_b)
    series(household_b, 35, 200.0, 0.0)
    for ago in range(1, 11):
        eat(household_b, ago, 2500)
    target(household_b, 2200)
    body_b = client_b.get(f"{BASE}/energy/").data
    assert body_b["proposal"] is None and body_b["basis"] != "trend"
    assert EnergyProposal.objects.count() == 0


def test_deleted_meals_never_count_toward_the_gate(auth_client, household_a):
    series(household_a, 35, 200.0, 0.0)
    for ago in range(1, 15):
        eat(household_a, ago, 2500, deleted=ago > 5)
        eat(household_a, ago, None)  # no estimate: not an intake day
    target(household_a, 2200)
    assert auth_client.get(f"{BASE}/energy/").data["proposal"] is None


def test_accept_writes_a_copy_forward_target_for_today(auth_client, household_a):
    _off_band_flat(household_a)
    proposal = auth_client.get(f"{BASE}/energy/").data["proposal"]
    response = auth_client.post(f"{BASE}/proposals/{proposal['id']}/accept/")
    assert response.status_code == 200, response.data
    assert response.data["accepted"] is True
    stored = response.data["meal_target"]
    assert stored["calories_kcal"] == 2050
    assert stored["effective_from_date"] == today().isoformat()
    assert (stored["protein_g"], stored["carbs_g"], stored["fat_g"]) == (180.0, 200.0, 70.0)
    assert "estimate" in response.data["words"]
    row = EnergyProposal.objects.get()
    assert row.status == "accepted" and row.meal_target_origin_guid == stored["origin_guid"]
    # The phone's body feed carries it.
    feed = auth_client.get("/api/body/meal_targets/", {"since": "1970-01-01T00:00:00Z"}).data
    assert stored["origin_guid"] in {r["origin_guid"] for r in feed["results"]}
    current = auth_client.get(f"{BASE}/energy/").data["current_target"]
    assert current["calories_kcal"] == 2050
    again = auth_client.post(f"{BASE}/proposals/{proposal['id']}/accept/")
    assert again.status_code == 409 and "already accepted" in again.data["detail"]


def test_accept_updates_a_target_already_dated_today(auth_client, household_a):
    _off_band_flat(household_a)
    today_row = target(household_a, 2200, days_ago=0, protein=150.0)
    # The proposal is made against today's row.
    proposal = auth_client.get(f"{BASE}/energy/").data["proposal"]
    auth_client.post(f"{BASE}/proposals/{proposal['id']}/accept/")
    today_row.refresh_from_db()
    assert today_row.calories_kcal == 2050 and today_row.protein_g == 150.0
    assert MealTarget.objects.filter(effective_from_date=today()).count() == 1


def test_dismiss_writes_nothing_to_the_targets(auth_client, household_a):
    _off_band_flat(household_a)
    before = list(MealTarget.objects.values_list("id", "calories_kcal"))
    proposal = auth_client.get(f"{BASE}/energy/").data["proposal"]
    response = auth_client.post(f"{BASE}/proposals/{proposal['id']}/dismiss/")
    assert response.status_code == 200 and response.data["dismissed"] is True
    assert list(MealTarget.objects.values_list("id", "calories_kcal")) == before
    assert EnergyProposal.objects.get().status == "dismissed"
    assert auth_client.get(f"{BASE}/energy/").data["proposal"] is None
    refused = auth_client.post(f"{BASE}/proposals/{proposal['id']}/dismiss/")
    assert refused.status_code == 409 and refused.data["detail"].startswith("Nothing was")


def test_one_pending_at_a_time(auth_client, household_a):
    _off_band_flat(household_a)
    auth_client.get(f"{BASE}/energy/")
    old = EnergyProposal.objects.get()
    EnergyProposal.objects.filter(pk=old.pk).update(
        created_at=datetime.now(UTC) - timedelta(days=15)
    )
    auth_client.get(f"{BASE}/energy/")
    statuses = sorted(EnergyProposal.objects.values_list("status", flat=True))
    assert statuses == ["expired", "pending"]
    with pytest.raises(IntegrityError), transaction.atomic():
        EnergyProposal.objects.filter(pk=old.pk).update(status="pending")


# =============================================================================
# Saved meals
# =============================================================================


def test_saved_meal_round_trip_and_log(auth_client):
    created = auth_client.post(
        f"{BASE}/saved-meals/",
        {"name": "Usual breakfast", "description": "oats and eggs", "calories_kcal": 520,
         "protein_g": 38},
        format="json",
    )
    assert created.status_code == 201, created.data
    assert created.data["estimate"] is True and created.data["carbs_g"] is None
    clash = auth_client.post(
        f"{BASE}/saved-meals/", {"name": "usual BREAKFAST", "calories_kcal": 1, "protein_g": 1},
        format="json",
    )
    assert clash.status_code == 400 and "already a saved meal" in clash.data["detail"]
    logged = auth_client.post(f"{BASE}/saved-meals/{created.data['id']}/log/")
    assert logged.status_code == 201, logged.data
    assert "estimate" in logged.data["words"]
    meals = MealLog.objects.all()
    assert meals.count() == 1
    meal = meals.get()
    assert (meal.description, meal.calories_kcal, meal.protein_g) == ("oats and eggs", 520, 38)
    assert meal.trust_tier == "REPORTED" and meal.provenance == "USER"
    assert logged.data["meal_log"]["origin_guid"] == meal.origin_guid
    assert auth_client.delete(f"{BASE}/saved-meals/{created.data['id']}/").status_code == 204
    assert MealLog.objects.count() == 1  # the logged meal stays
    assert auth_client.get(f"{BASE}/saved-meals/").data["results"] == []


def test_save_a_meal_from_a_logged_one(auth_client, household_a):
    meal = eat(household_a, 0, 640, protein=42.0)
    by_guid = auth_client.post(
        f"{BASE}/saved-meals/", {"name": "lunch", "from_meal_log": meal.origin_guid},
        format="json",
    )
    assert by_guid.status_code == 201, by_guid.data
    assert by_guid.data["calories_kcal"] == 640 and by_guid.data["protein_g"] == 42.0
    by_id = auth_client.post(
        f"{BASE}/saved-meals/", {"name": "lunch 2", "from_meal_log": str(meal.id)},
        format="json",
    )
    assert by_id.status_code == 201
    missing = auth_client.post(
        f"{BASE}/saved-meals/", {"name": "x", "from_meal_log": "nope"}, format="json"
    )
    assert missing.status_code == 404 and missing.data["detail"].startswith("Nothing was saved.")


# =============================================================================
# Weekly line over REST
# =============================================================================


def test_weekly_line_over_rest(auth_client, household_a):
    assert auth_client.get(f"{BASE}/weekly-line/").data["say"] is False
    series(household_a, 30, 210.0, -0.2)
    goal(household_a, 190)
    line = auth_client.get(f"{BASE}/weekly-line/").data
    assert line["say"] is True and "goal" in line["facts"]
    assert not FORBIDDEN.search(line["facts"]), line["facts"]
    auth_client.put(f"{BASE}/profile/", {"weekly_line_on": False}, format="json")
    off = auth_client.get(f"{BASE}/weekly-line/").data
    assert off["say"] is False and "turned off" in off["reason"]


def test_weekly_line_is_quiet_on_a_thin_fortnight(auth_client, household_a):
    series(household_a, 30, 210.0, -0.2, every=3)  # 4 or 5 weigh-ins in the last 14
    line = auth_client.get(f"{BASE}/weekly-line/").data
    assert line["say"] is False and line["facts"] == ""


# =============================================================================
# Tenancy, recruiters
# =============================================================================


def test_households_never_see_each_others_coach_rows(auth_client, household_a, user_b):
    client_b = _client_for(user_b)
    _off_band_flat(household_a)
    meal = auth_client.post(
        f"{BASE}/saved-meals/", {"name": "alpha meal", "calories_kcal": 500, "protein_g": 30},
        format="json",
    ).data
    waist = auth_client.post(f"{BASE}/waist/", {"waist_cm": 90}, format="json").data
    proposal = auth_client.get(f"{BASE}/energy/").data["proposal"]
    synthetic_man(auth_client)

    assert client_b.get(f"{BASE}/saved-meals/").data["results"] == []
    assert client_b.get(f"{BASE}/waist/").data["results"] == []
    assert client_b.get(f"{BASE}/profile/").data["exists"] is False
    energy_b = client_b.get(f"{BASE}/energy/").data
    assert energy_b["proposal"] is None and energy_b["current_target"] is None
    assert client_b.get(f"{BASE}/trend/").data["points"] == []
    assert client_b.delete(f"{BASE}/saved-meals/{meal['id']}/").status_code == 404
    assert client_b.post(f"{BASE}/saved-meals/{meal['id']}/log/").status_code == 404
    assert client_b.delete(f"{BASE}/waist/{waist['id']}/").status_code == 404
    assert client_b.post(f"{BASE}/proposals/{proposal['id']}/accept/").status_code == 404
    assert client_b.post(f"{BASE}/proposals/{proposal['id']}/dismiss/").status_code == 404
    assert SavedMeal.objects.filter(pk=meal["id"]).exists()
    assert EnergyProposal.objects.get().status == "pending"
    # B may use A's saved-meal name; names are unique per household.
    same = client_b.post(
        f"{BASE}/saved-meals/", {"name": "alpha meal", "calories_kcal": 1, "protein_g": 1},
        format="json",
    )
    assert same.status_code == 201


COACH_ROUTES = [
    ("get", "profile/"), ("put", "profile/"), ("get", "energy/"), ("get", "trend/"),
    ("get", "waist/"), ("post", "waist/"), ("delete", f"waist/{uuid.uuid4()}/"),
    ("get", "saved-meals/"), ("post", "saved-meals/"),
    ("delete", f"saved-meals/{uuid.uuid4()}/"), ("post", f"saved-meals/{uuid.uuid4()}/log/"),
    ("post", f"proposals/{uuid.uuid4()}/accept/"), ("post", f"proposals/{uuid.uuid4()}/dismiss/"),
    ("get", "weekly-line/"),
]


def test_a_recruiter_is_refused_every_coach_route(db):
    from household.models import DeviceToken, Household, HouseholdMember, User
    from household.recruiter_routes import denied_refusal

    visit = Household.objects.create(name="Demo visit coach test", is_demo_visit=True)
    visitor = User.objects.create_user(email=f"visitor-{uuid.uuid4().hex[:12]}@demo.invalid")
    HouseholdMember.objects.create(user=visitor, household=visit, role=HouseholdMember.RECRUITER)
    _token, raw = DeviceToken.issue(visitor, "Demo visit", DeviceToken.SCOPE_READ)
    from rest_framework.test import APIClient

    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    expected = denied_refusal(
        "the body aspect (health, food, sleep, workouts) is not part of the demo"
    )
    for method, path in COACH_ROUTES:
        response = getattr(client, method)(f"{BASE}/{path}", {}, format="json")
        assert response.status_code == 403, (method, path, response.status_code)
        assert response.data["detail"] == expected, (method, path)
    assert not BodyProfile.objects.exists()


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

    return call(client, name, arguments or {})


def test_tools_set_the_profile_and_stop_the_weekly_line(mcp, auth_client):
    is_error, text, structured = _call(
        mcp, "set_body_profile",
        {"height_cm": 165, "sex": "female", "birth_year": THIS_YEAR - 41,
         "activity_level": "sedentary"},
    )
    assert not is_error, text
    assert "committed" in text and "a weigh-in" in text
    is_error, text, _ = _call(mcp, "set_body_profile", {"weekly_line_on": False})
    assert not is_error and "weekly weight line is off" in text
    assert auth_client.get(f"{BASE}/profile/").data["weekly_line_on"] is False
    is_error, text, _ = _call(mcp, "set_body_profile", {"sex": "neither"})
    assert is_error and text.startswith("Nothing was")


def test_tool_energy_estimate_says_estimate_and_missing(mcp, household_a):
    is_error, text, structured = _call(mcp, "get_energy_estimate")
    assert not is_error, text
    assert "estimate" in text and "Missing" in text and "never guess" in text
    weigh(household_a, 1, 70, unit="kg")
    _call(mcp, "set_body_profile",
          {"height_cm": 165, "sex": "female", "birth_year": THIS_YEAR - 41,
           "activity_level": "sedentary"})
    is_error, text, structured = _call(mcp, "get_energy_estimate")
    assert structured["maintenance_kcal"] == 1638  # 1365.25 x 1.2
    assert "1638 kcal" in text and "estimate" in text


def test_tools_log_and_delete_a_saved_meal_by_loose_name(mcp):
    is_error, text, _ = _call(
        mcp, "save_meal", {"name": "Usual Breakfast", "calories_kcal": 500, "protein_g": 35}
    )
    assert not is_error, text
    assert "estimates" in text and "nothing was logged" in text
    is_error, text, _ = _call(mcp, "log_saved_meal", {"name": "my usual breakfast"})
    assert not is_error, text
    assert "estimate" in text and MealLog.objects.count() == 1
    is_error, text, _ = _call(mcp, "log_saved_meal", {"name": "dinner"})
    assert is_error and text.startswith("Nothing was logged.") and "Usual Breakfast" in text
    is_error, text, _ = _call(mcp, "list_saved_meals")
    assert "Usual Breakfast" in text and "estimate" in text
    is_error, text, _ = _call(mcp, "delete_saved_meal", {"name": "usual breakfast"})
    assert not is_error and not SavedMeal.objects.exists()


def test_tools_log_waist_and_read_the_trend(mcp, household_a):
    is_error, text, _ = _call(mcp, "log_waist", {"waist_in": 34.5})
    assert not is_error and "34.5 in" in text
    series(household_a, 20, 200.0, -0.3)
    is_error, text, structured = _call(mcp, "get_weight_trend")
    assert not is_error and "estimate" in text and "water" in text
    assert "points" not in structured


def test_tools_accept_and_dismiss_the_pending_proposal(mcp, household_a):
    is_error, text, _ = _call(mcp, "accept_energy_proposal")
    assert is_error and "no pending energy proposal" in text
    _off_band_flat(household_a)
    is_error, text, _ = _call(mcp, "accept_energy_proposal")
    assert not is_error, text
    assert "2050 kcal" in text and "committed" in text
    assert MealTarget.objects.filter(effective_from_date=today(), calories_kcal=2050).exists()
    EnergyProposal.objects.update(created_at=datetime.now(UTC) - timedelta(days=15))
    MealTarget.objects.filter(effective_from_date=today()).update(calories_kcal=2200)
    is_error, text, _ = _call(mcp, "dismiss_energy_proposal")
    assert not is_error and "unchanged" in text


# =============================================================================
# The profile's own goal weight (the phone's goals do not reach the engine)
# =============================================================================


def test_protein_uses_the_profile_goal_when_there_is_no_goals_row(auth_client, household_a):
    synthetic_man(auth_client)
    weigh(household_a, 1, 95, unit="kg")
    saved = auth_client.put(f"{BASE}/profile/", {"goal_weight_kg": 85}, format="json").data
    assert saved["goal_weight_lbs"] == round(85 * trend.LBS_PER_KG, 1)
    body = auth_client.get(f"{BASE}/energy/").data
    assert body["protein_g"] == 170 and body["protein_basis"] == "goal weight"


def test_the_milestone_from_the_profile_goal(auth_client, household_a):
    series(household_a, 40, 220.0, -0.3)
    auth_client.put(f"{BASE}/profile/", {"goal_weight_lbs": 200}, format="json")
    stone = auth_client.get(f"{BASE}/trend/").data["milestone"]
    assert stone["goal_id"] is None and stone["statement"] == "Goal weight"
    assert stone["target_lbs"] == 200.0 and stone["start_lbs"] == 220.0
    assert stone["remaining_lbs"] > 0 and stone["weeks_to_go"] is not None


def test_a_goals_row_wins_over_the_profile(auth_client, household_a):
    series(household_a, 40, 220.0, -0.3)
    auth_client.put(f"{BASE}/profile/", {"goal_weight_lbs": 150}, format="json")
    row = goal(household_a, 200, statement="reach a lighter weight")
    stone = auth_client.get(f"{BASE}/trend/").data["milestone"]
    assert stone["goal_id"] == str(row.id) and stone["target_lbs"] == 200.0


def test_the_profile_goal_clears_with_null(auth_client, household_a):
    series(household_a, 40, 220.0, -0.3)
    auth_client.put(f"{BASE}/profile/", {"goal_weight_lbs": 200}, format="json")
    cleared = auth_client.put(f"{BASE}/profile/", {"goal_weight_lbs": None}, format="json")
    assert cleared.status_code == 200 and cleared.data["goal_weight_lbs"] is None
    assert auth_client.get(f"{BASE}/trend/").data["milestone"] is None


@pytest.mark.parametrize(
    "body",
    [{"goal_weight_lbs": 0}, {"goal_weight_lbs": 1000}, {"goal_weight_kg": 500},
     {"goal_weight_lbs": "light"}, {"goal_weight_lbs": 180, "goal_weight_kg": 80}],
)
def test_goal_weight_refusals_are_in_words(auth_client, body):
    response = auth_client.put(f"{BASE}/profile/", body, format="json")
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was saved.")
    assert not BodyProfile.objects.exists()


def test_a_profile_goal_is_private_to_its_member(auth_client, household_a):
    series(household_a, 40, 220.0, -0.3)
    auth_client.put(f"{BASE}/profile/", {"goal_weight_lbs": 200}, format="json")
    mia = _client_for(_member(household_a, "mia@example.com"))
    assert mia.get(f"{BASE}/trend/").data["milestone"] is None
    assert mia.get(f"{BASE}/profile/").data["goal_weight_lbs"] is None


def test_tool_sets_the_goal_weight(mcp, auth_client):
    is_error, text, _ = _call(mcp, "set_body_profile", {"goal_weight_lbs": 190})
    assert not is_error, text
    assert "Goal weight 190.0 lbs" in text
    assert auth_client.get(f"{BASE}/profile/").data["goal_weight_lbs"] == 190.0
